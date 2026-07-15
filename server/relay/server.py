"""Relay Server — connexion WebSocket permanente multiplexée entre l'app
Android et l'agent Hermes, avec pairing QR/TOFU, push buffer, et proxy SSE.

Usage :
    python server.py

Prérequis :
    pip install -r requirements.txt

Variables d'environnement :
    RELAY_HOST          (défaut: 0.0.0.0)
    RELAY_PORT          (défaut: 8767)
    HERMES_API_BASE_URL (défaut: http://127.0.0.1:8443) — base URL de l'agent Hermes
    HERMES_API_TOKEN    (optionnel) — Bearer token vers Hermes, si requis
    RELAY_SESSIONS_PATH (optionnel) — chemin du fichier de persistance des
                         sessions (défaut: ~/.hermes/hasan-relay-sessions.json)
"""

from __future__ import annotations

import asyncio
import json
import logging
import os
import time
from pathlib import Path

import aiohttp
from aiohttp import web

from bridge_commands import BridgeCommandRegistry, CommandTimeoutError
from chat_stream import ChatSessionRegistry
from envelope import Envelope, EnvelopeError
from pairing import DEFAULT_SESSIONS_PATH, PairingManager
from push_buffer import PushBuffer
from rate_limiter import RateLimiter

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
log = logging.getLogger("relay")

HOST = os.environ.get("RELAY_HOST", "0.0.0.0")
PORT = int(os.environ.get("RELAY_PORT", "8767"))

PAIRING_RATE_LIMIT_ATTEMPTS = 10
PAIRING_RATE_LIMIT_WINDOW_SECONDS = 60.0

# Délai laissé au client pour envoyer son message d'authentification après
# l'upgrade WS, avant fermeture (4401).
WS_AUTH_TIMEOUT_SECONDS = 10.0

# chat/health et chat/clarify_response sont des opérations ponctuelles
# (une requête, une réponse) — timeout court, sans rapport avec le watchdog
# généreux de chat_stream.py conçu pour des tool calls longs.
CHAT_RPC_TIMEOUT_SECONDS = 8.0

# Clés de app[...] — tout l'état mutable vit sur l'Application plutôt qu'en
# variables module-level, pour que create_app() produise des instances
# réellement isolées (indispensable pour les tests, qui créent une app par cas).
KEY_PAIRING_MANAGER = web.AppKey("pairing_manager", PairingManager)
KEY_PUSH_BUFFER = web.AppKey("push_buffer", PushBuffer)
KEY_RATE_LIMITER = web.AppKey("pairing_rate_limiter", RateLimiter)
KEY_ACTIVE_CONNECTIONS: web.AppKey[dict[str, web.WebSocketResponse]] = web.AppKey("active_connections", dict)
KEY_BRIDGE_COMMANDS = web.AppKey("bridge_commands", BridgeCommandRegistry)
KEY_CHAT_SESSIONS = web.AppKey("chat_sessions", ChatSessionRegistry)
KEY_ADMIN_TOKEN = web.AppKey("admin_token", str)
KEY_HERMES_API_BASE_URL = web.AppKey("hermes_api_base_url", str)
KEY_HERMES_API_TOKEN = web.AppKey("hermes_api_token", str)
KEY_PUBLIC_URL = web.AppKey("public_url", str)


def _client_ip(request: web.Request) -> str:
    forwarded = request.headers.get("X-Forwarded-For")
    if forwarded:
        return forwarded.split(",")[0].strip()
    return request.remote or "unknown"


def _require_session(request: web.Request) -> str | None:
    """Extrait et valide le Bearer token. Retourne le device_hash ou None."""
    auth = request.headers.get("Authorization", "")
    if not auth.startswith("Bearer "):
        return None
    token = auth.removeprefix("Bearer ").strip()
    session = request.app[KEY_PAIRING_MANAGER].touch(token)
    return session.device_hash if session else None


# ─────────────────────────── Routes HTTP ───────────────────────────


async def handle_health(request: web.Request) -> web.Response:
    return web.json_response({"status": "ok", "timestamp": time.time()})


async def handle_pairing_create(request: web.Request) -> web.Response:
    """Génère un nouveau code de pairing. Protégé par RELAY_ADMIN_TOKEN.

    Si RELAY_ADMIN_TOKEN n'est pas défini, la route est désactivée (503) plutôt
    que de rester ouverte sans authentification — pas de valeur par défaut
    permissive côté secret admin.
    """
    admin_token = request.app[KEY_ADMIN_TOKEN]
    if not admin_token:
        return web.json_response({"error": "admin_token_not_configured"}, status=503)

    auth = request.headers.get("Authorization", "")
    if auth != f"Bearer {admin_token}":
        return web.json_response({"error": "unauthorized"}, status=401)

    code = request.app[KEY_PAIRING_MANAGER].create_pairing_code()
    response = {"code": code, "ttl_seconds": 600}

    public_url = request.app[KEY_PUBLIC_URL]
    if public_url:
        response["relay_url"] = public_url

    return web.json_response(response)


async def handle_pairing_register(request: web.Request) -> web.Response:
    ip = _client_ip(request)
    if not request.app[KEY_RATE_LIMITER].allow(ip):
        return web.json_response({"error": "rate_limited"}, status=429)

    try:
        body = await request.json()
    except json.JSONDecodeError:
        return web.json_response({"error": "invalid_json"}, status=400)

    code = body.get("code")
    device_hash = body.get("device_hash")
    if not isinstance(code, str) or not isinstance(device_hash, str):
        return web.json_response({"error": "missing_fields"}, status=400)

    result = request.app[KEY_PAIRING_MANAGER].redeem(code, device_hash)
    if result is None:
        return web.json_response({"error": "invalid_or_expired_code"}, status=400)

    log.info("Pairing réussi pour device_hash=%s...", device_hash[:8])
    return web.json_response({
        "session_token": result.session.token,
        "refresh_token": result.refresh_token,
    })


async def handle_pairing_refresh(request: web.Request) -> web.Response:
    """Échange un refresh_token contre un nouveau (session_token, refresh_token).

    Permet à l'app de renouveler sa session sans re-scanner de QR quand le
    session_token approche de son expiration (voir SessionTokenStore.kt
    isLikelyExpired côté client) — rotation stricte, l'ancien refresh_token
    est immédiatement invalidé qu'il soit réutilisé ou non.
    """
    ip = _client_ip(request)
    if not request.app[KEY_RATE_LIMITER].allow(ip):
        return web.json_response({"error": "rate_limited"}, status=429)

    try:
        body = await request.json()
    except json.JSONDecodeError:
        return web.json_response({"error": "invalid_json"}, status=400)

    refresh_token = body.get("refresh_token")
    if not isinstance(refresh_token, str) or not refresh_token:
        return web.json_response({"error": "missing_refresh_token"}, status=400)

    result = request.app[KEY_PAIRING_MANAGER].refresh(refresh_token)
    if result is None:
        return web.json_response({"error": "invalid_or_expired_refresh_token"}, status=401)

    log.info("Session renouvelée via refresh_token pour device_hash=%s...", result.session.device_hash[:8])
    return web.json_response({
        "session_token": result.session.token,
        "refresh_token": result.refresh_token,
    })


async def handle_phone_message(request: web.Request) -> web.Response:
    """Entrée pour le plugin Hermes : pousse un message vers l'app (via push buffer + WS live)."""
    device_hash = _require_session(request)
    if device_hash is None:
        return web.json_response({"error": "unauthorized"}, status=401)

    try:
        body = await request.json()
    except json.JSONDecodeError:
        return web.json_response({"error": "invalid_json"}, status=400)

    text = body.get("text")
    if not isinstance(text, str) or not text:
        return web.json_response({"error": "missing_text"}, status=400)

    envelope = Envelope(channel="proactive", type="message", payload={"text": text}).to_dict()

    active_connections = request.app[KEY_ACTIVE_CONNECTIONS]
    ws = active_connections.get(device_hash)
    if ws is not None and not ws.closed:
        await ws.send_json(envelope)
    else:
        request.app[KEY_PUSH_BUFFER].push(device_hash, envelope)

    return web.json_response({"delivered": ws is not None and not ws.closed})


async def handle_phone_replies(request: web.Request) -> web.Response:
    """Long-poll utilisé par le plugin Hermes pour récupérer les réponses de l'app.

    Contrat (voir plugin/hasan_delivery/adapter.py) : bloque jusqu'à
    `timeout` secondes (query param, défaut 30) ou jusqu'à ce qu'au moins une
    réponse soit disponible, puis retourne {"replies": [{id, text, timestamp}, ...]}.

    La file de réponses sortantes de l'app n'est pas encore câblée (aucune
    route WS n'écrit dedans pour l'instant) — ce endpoint attend donc
    toujours le plein timeout et répond liste vide. Sera alimenté quand
    l'app enverra ses réponses via le canal `chat`/`bridge` du WS (étapes
    suivantes du portage Android).
    """
    device_hash = _require_session(request)
    if device_hash is None:
        return web.json_response({"error": "unauthorized"}, status=401)

    try:
        timeout = float(request.query.get("timeout", "30"))
    except ValueError:
        timeout = 30.0
    timeout = max(0.0, min(timeout, 60.0))

    await asyncio.sleep(timeout)
    return web.json_response({"replies": []})


async def handle_phone_outbound(request: web.Request) -> web.Response:
    device_hash = _require_session(request)
    if device_hash is None:
        return web.json_response({"error": "unauthorized"}, status=401)
    return web.json_response({"pending": request.app[KEY_PUSH_BUFFER].pending_count(device_hash)})


async def handle_bridge_command(request: web.Request) -> web.Response:
    """Entrée pour le plugin Hermes (tool function-calling) : exécute une
    capability sur le téléphone via le canal `bridge` du WS, attend le
    résultat borné dans le temps (voir bridge_commands.py).

    Contrat requête : {"capability": str, "params": dict} — même vocabulaire
    que côté Android (CapabilityExecutor.execute(capability, params)).
    Retourne le résultat de la capability tel que renvoyé par le téléphone,
    ou une erreur si le device n'est pas connecté / ne répond pas à temps.
    """
    device_hash = _require_session(request)
    if device_hash is None:
        return web.json_response({"error": "unauthorized"}, status=401)

    try:
        body = await request.json()
    except json.JSONDecodeError:
        return web.json_response({"error": "invalid_json"}, status=400)

    capability = body.get("capability")
    if not isinstance(capability, str) or not capability:
        return web.json_response({"error": "missing_capability"}, status=400)
    params = body.get("params")
    if params is None:
        params = {}
    if not isinstance(params, dict):
        return web.json_response({"error": "params_must_be_object"}, status=400)

    ws = request.app[KEY_ACTIVE_CONNECTIONS].get(device_hash)
    if ws is None or ws.closed:
        return web.json_response({"error": "device_not_connected"}, status=503)

    def envelope_factory(command_id: str, capability: str, params: dict) -> dict:
        return Envelope(
            channel="bridge",
            type="command",
            payload={"command_id": command_id, "capability": capability, "params": params},
        ).to_dict()

    registry = request.app[KEY_BRIDGE_COMMANDS]
    try:
        result = await registry.send_and_wait(
            ws,
            command_id=None,
            capability=capability,
            params=params,
            envelope_factory=envelope_factory,
        )
    except CommandTimeoutError:
        return web.json_response({"error": "command_timeout"}, status=504)

    return web.json_response(result)


# ─────────────────────────── WebSocket ───────────────────────────


async def _authenticate_ws(ws: web.WebSocketResponse, pairing_manager: PairingManager):
    """Attend le premier message applicatif et valide le session_token qu'il porte.

    Retourne la Session si valide, None sinon (message absent/malformé/timeout/
    token invalide) — l'appelant ferme la connexion dans tous les cas None.
    """
    try:
        msg = await asyncio.wait_for(ws.receive(), timeout=WS_AUTH_TIMEOUT_SECONDS)
    except asyncio.TimeoutError:
        log.warning("WS auth: timeout — aucun message reçu sous %ss", WS_AUTH_TIMEOUT_SECONDS)
        return None

    if msg.type != aiohttp.WSMsgType.TEXT:
        log.warning("WS auth: premier message n'est pas TEXT (type=%s)", msg.type)
        return None

    try:
        data = json.loads(msg.data)
        envelope = Envelope.from_dict(data)
    except (json.JSONDecodeError, EnvelopeError) as exc:
        log.warning("WS auth: enveloppe invalide: %s", exc)
        return None

    if envelope.channel != "system" or envelope.type != "auth":
        log.warning("WS auth: attendu channel=system type=auth, reçu channel=%s type=%s", envelope.channel, envelope.type)
        return None

    token = envelope.payload.get("session_token")
    if not isinstance(token, str) or not token:
        log.warning("WS auth: session_token manquant ou invalide dans le payload")
        return None

    return pairing_manager.touch(token)


async def handle_ws(request: web.Request) -> web.WebSocketResponse:
    """Upgrade WS puis authentification via le premier message applicatif.

    Le session_token ne transite JAMAIS dans l'URL (query param) : un token en
    query string finit dans les logs d'accès du reverse proxy, l'historique
    navigateur si testé au navigateur, et potentiellement des logs d'infra
    tiers — un canal de fuite qu'on ne maîtrise pas entièrement. Le client
    doit envoyer, comme premier message après l'upgrade, une enveloppe
    {channel: "system", type: "auth", payload: {session_token: "..."}}.
    Timeout AUTH_TIMEOUT_SECONDS pour recevoir ce message, sinon fermeture.
    """
    pairing_manager = request.app[KEY_PAIRING_MANAGER]

    ws = web.WebSocketResponse(heartbeat=30)
    await ws.prepare(request)

    session = await _authenticate_ws(ws, pairing_manager)
    if session is None:
        await ws.close(code=4401, message=b"invalid_or_expired_session")
        return ws

    device_hash = session.device_hash
    active_connections = request.app[KEY_ACTIVE_CONNECTIONS]

    previous = active_connections.get(device_hash)
    if previous is not None and not previous.closed:
        # Réassigner AVANT de fermer `previous` : `previous.close()` va
        # réveiller la boucle `async for msg in ws` du handle_ws précédent,
        # dont le bloc `finally` teste `active_connections.get(device_hash) is
        # ws` pour décider s'il doit nettoyer (voir plus bas) — la nouvelle
        # valeur doit déjà être en place à ce moment-là pour que ce test soit
        # `False` côté ancienne connexion, sans quoi les tours de chat en
        # cours seraient annulés à tort (cf. test
        # test_chat_stream_survives_ws_reconnect_resolves_fresh_socket).
        active_connections[device_hash] = ws
        await previous.close(code=4000, message=b"superseded_by_new_connection")
    else:
        active_connections[device_hash] = ws
    log.info("WS connecté device_hash=%s...", device_hash[:8])

    for pending_envelope in request.app[KEY_PUSH_BUFFER].drain(device_hash):
        await ws.send_json(pending_envelope)

    bridge_commands = request.app[KEY_BRIDGE_COMMANDS]
    chat_sessions = request.app[KEY_CHAT_SESSIONS]
    try:
        async for msg in ws:
            if msg.type == aiohttp.WSMsgType.TEXT:
                await _dispatch_inbound(
                    ws,
                    msg.data,
                    bridge_commands=bridge_commands,
                    chat_sessions=chat_sessions,
                    device_hash=device_hash,
                    active_connections=active_connections,
                    hermes_api_base_url=request.app[KEY_HERMES_API_BASE_URL],
                    hermes_api_token=request.app[KEY_HERMES_API_TOKEN],
                )
            elif msg.type == aiohttp.WSMsgType.ERROR:
                log.warning("WS erreur device_hash=%s...: %s", device_hash[:8], ws.exception())
    finally:
        if active_connections.get(device_hash) is ws:
            del active_connections[device_hash]
            # Seulement si CETTE connexion était bien la connexion active du
            # device — si elle a été supersédée par une nouvelle (voir
            # test_second_ws_connection_supersedes_first), les tours de chat
            # en cours doivent survivre, la nouvelle connexion les récupère
            # via la re-résolution de `ws` dans send_envelope (chat_stream.py).
            chat_sessions.cancel_all_for_device(device_hash)
        log.info("WS déconnecté device_hash=%s...", device_hash[:8])

    return ws


async def _handle_chat_health(
    ws: web.WebSocketResponse, envelope: Envelope, hermes_api_base_url: str
) -> None:
    """chat/health — ping applicatif vers Hermes (pas le relay lui-même, déjà
    couvert par system/ping). Requête/réponse ponctuelle, corrélée par
    envelope.id — pas de session_id, pas de ChatSessionRegistry (conçu pour
    un flux de N enveloppes, pas un aller-retour unique)."""
    payload: dict = {}
    try:
        async with aiohttp.ClientSession() as client:
            async with client.get(
                f"{hermes_api_base_url}/health",
                timeout=aiohttp.ClientTimeout(total=CHAT_RPC_TIMEOUT_SECONDS),
            ) as response:
                if response.status == 200:
                    payload = {"ok": True}
                else:
                    payload = {"ok": False, "http_status": response.status}
    except (aiohttp.ClientError, asyncio.TimeoutError) as exc:
        payload = {"ok": False, "message": str(exc)}

    await ws.send_json(
        Envelope(channel="chat", type="health_result", payload=payload, id=envelope.id).to_dict()
    )


async def _handle_chat_clarify_response(
    ws: web.WebSocketResponse, envelope: Envelope, hermes_api_base_url: str
) -> None:
    """chat/clarify_response — répond à une clarification en attente côté
    Hermes, pendant que le tour chat/send original reste ouvert en parallèle
    (même format que l'ancien POST HTTP /api/sessions/{id}/clarify-response)."""
    session_id = envelope.payload.get("session_id")
    clarify_id = envelope.payload.get("clarify_id")
    response_text = envelope.payload.get("response")

    if (
        not isinstance(session_id, str) or not session_id
        or not isinstance(clarify_id, str) or not clarify_id
        or not isinstance(response_text, str) or not response_text
    ):
        await ws.send_json(
            Envelope(
                channel="chat",
                type="clarify_response_result",
                payload={"session_id": session_id, "clarify_id": clarify_id, "ok": False},
                id=envelope.id,
            ).to_dict()
        )
        return

    ok = False
    try:
        async with aiohttp.ClientSession() as client:
            async with client.post(
                f"{hermes_api_base_url}/api/sessions/{session_id}/clarify-response",
                json={"clarify_id": clarify_id, "response": response_text},
                timeout=aiohttp.ClientTimeout(total=CHAT_RPC_TIMEOUT_SECONDS),
            ) as response:
                ok = response.status < 300
    except (aiohttp.ClientError, asyncio.TimeoutError) as exc:
        log.warning("chat/clarify_response échoué pour session_id=%s: %s", session_id, exc)

    await ws.send_json(
        Envelope(
            channel="chat",
            type="clarify_response_result",
            payload={"session_id": session_id, "clarify_id": clarify_id, "ok": ok},
            id=envelope.id,
        ).to_dict()
    )


async def _dispatch_inbound(
    ws: web.WebSocketResponse,
    raw: str,
    *,
    bridge_commands: BridgeCommandRegistry,
    chat_sessions: ChatSessionRegistry,
    device_hash: str,
    active_connections: dict[str, web.WebSocketResponse],
    hermes_api_base_url: str,
    hermes_api_token: str,
) -> None:
    try:
        data = json.loads(raw)
        envelope = Envelope.from_dict(data)
    except (json.JSONDecodeError, EnvelopeError) as exc:
        await ws.send_json({"version": 1, "channel": "system", "type": "error", "payload": {"error": str(exc)}})
        return

    if envelope.channel == "system" and envelope.type == "ping":
        pong = Envelope(channel="system", type="pong", payload={}).to_dict()
        await ws.send_json(pong)
        return

    if envelope.channel == "bridge" and envelope.type == "command_result":
        command_id = envelope.payload.get("command_id")
        result = envelope.payload.get("result")
        if isinstance(command_id, str) and isinstance(result, dict):
            resolved = bridge_commands.resolve(command_id, result)
            if not resolved:
                log.warning("Résultat bridge reçu pour une commande inconnue/expirée id=%s", command_id)
        else:
            log.warning("Enveloppe bridge/command_result malformée id=%s", envelope.id)
        return

    if envelope.channel == "chat" and envelope.type == "send":
        session_id = envelope.payload.get("session_id")
        text = envelope.payload.get("text")
        if not isinstance(session_id, str) or not session_id or not isinstance(text, str) or not text:
            await ws.send_json(
                Envelope(
                    channel="chat",
                    type="error",
                    payload={"session_id": session_id, "message": "session_id et text sont requis"},
                ).to_dict()
            )
            return

        async def send_envelope(sid: str, ev_type: str, payload_extra: dict) -> None:
            current_ws = active_connections.get(device_hash)
            if current_ws is None or current_ws.closed:
                log.info("chat/%s abandonné pour session_id=%s — device déconnecté", ev_type, sid)
                return
            payload = {"session_id": sid, **payload_extra}
            await current_ws.send_json(Envelope(channel="chat", type=ev_type, payload=payload).to_dict())

        await chat_sessions.start(
            device_hash=device_hash,
            session_id=session_id,
            text=text,
            hermes_base_url=hermes_api_base_url,
            hermes_token=hermes_api_token,
            send_envelope=send_envelope,
        )
        return

    if envelope.channel == "chat" and envelope.type == "cancel":
        session_id = envelope.payload.get("session_id")
        if isinstance(session_id, str) and session_id:
            chat_sessions.cancel(session_id)
        return

    if envelope.channel == "chat" and envelope.type == "health":
        await _handle_chat_health(ws, envelope, hermes_api_base_url)
        return

    if envelope.channel == "chat" and envelope.type == "clarify_response":
        await _handle_chat_clarify_response(ws, envelope, hermes_api_base_url)
        return

    # Le canal proactive est acquitté ici (pas de routage métier entrant attendu).
    log.info("Reçu channel=%s type=%s id=%s", envelope.channel, envelope.type, envelope.id)


# ─────────────────────────── App factory ───────────────────────────


def create_app(
    *,
    admin_token: str = "",
    hermes_api_base_url: str = "http://127.0.0.1:8443",
    hermes_api_token: str = "",
    public_url: str = "",
    pairing_rate_limit_attempts: int = PAIRING_RATE_LIMIT_ATTEMPTS,
    pairing_rate_limit_window_seconds: float = PAIRING_RATE_LIMIT_WINDOW_SECONDS,
    sessions_path: Path | None = None,
) -> web.Application:
    """[sessions_path] : None = pas de persistance disque (défaut — utilisé par
    les tests, qui ne doivent jamais toucher le disque ni interférer entre eux
    via un fichier partagé). Un vrai chemin active la persistance JSON — voir
    [create_app_from_env] pour la valeur de production."""
    app = web.Application()

    app[KEY_PAIRING_MANAGER] = PairingManager(sessions_path=sessions_path)
    app[KEY_PUSH_BUFFER] = PushBuffer()
    app[KEY_RATE_LIMITER] = RateLimiter(pairing_rate_limit_attempts, pairing_rate_limit_window_seconds)
    app[KEY_ACTIVE_CONNECTIONS] = {}
    app[KEY_BRIDGE_COMMANDS] = BridgeCommandRegistry()
    app[KEY_CHAT_SESSIONS] = ChatSessionRegistry()
    app[KEY_ADMIN_TOKEN] = admin_token
    app[KEY_HERMES_API_BASE_URL] = hermes_api_base_url
    app[KEY_HERMES_API_TOKEN] = hermes_api_token
    app[KEY_PUBLIC_URL] = public_url.rstrip("/")

    app.router.add_get("/health", handle_health)
    app.router.add_post("/pairing/create", handle_pairing_create)
    app.router.add_post("/pairing/register", handle_pairing_register)
    app.router.add_post("/pairing/refresh", handle_pairing_refresh)
    app.router.add_post("/phone/message", handle_phone_message)
    app.router.add_get("/phone/replies", handle_phone_replies)
    app.router.add_get("/phone/outbound", handle_phone_outbound)
    app.router.add_post("/bridge/command", handle_bridge_command)
    app.router.add_get("/ws", handle_ws)
    return app


def create_app_from_env() -> web.Application:
    sessions_path_env = os.environ.get("RELAY_SESSIONS_PATH", "").strip()
    sessions_path = Path(sessions_path_env) if sessions_path_env else DEFAULT_SESSIONS_PATH
    return create_app(
        admin_token=os.environ.get("RELAY_ADMIN_TOKEN", ""),
        hermes_api_base_url=os.environ.get("HERMES_API_BASE_URL", "http://127.0.0.1:8443"),
        hermes_api_token=os.environ.get("HERMES_API_TOKEN", ""),
        public_url=os.environ.get("RELAY_PUBLIC_URL", ""),
        sessions_path=sessions_path,
    )


if __name__ == "__main__":
    log.info("Relay Server démarrage sur http://%s:%d", HOST, PORT)
    web.run_app(create_app_from_env(), host=HOST, port=PORT)
