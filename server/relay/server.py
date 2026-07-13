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
"""

from __future__ import annotations

import asyncio
import json
import logging
import os
import time

import aiohttp
from aiohttp import web

from envelope import Envelope, EnvelopeError
from pairing import PairingManager
from push_buffer import PushBuffer
from rate_limiter import RateLimiter

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
log = logging.getLogger("relay")

HOST = os.environ.get("RELAY_HOST", "0.0.0.0")
PORT = int(os.environ.get("RELAY_PORT", "8767"))

PAIRING_RATE_LIMIT_ATTEMPTS = 10
PAIRING_RATE_LIMIT_WINDOW_SECONDS = 60.0

# Clés de app[...] — tout l'état mutable vit sur l'Application plutôt qu'en
# variables module-level, pour que create_app() produise des instances
# réellement isolées (indispensable pour les tests, qui créent une app par cas).
KEY_PAIRING_MANAGER = web.AppKey("pairing_manager", PairingManager)
KEY_PUSH_BUFFER = web.AppKey("push_buffer", PushBuffer)
KEY_RATE_LIMITER = web.AppKey("pairing_rate_limiter", RateLimiter)
KEY_ACTIVE_CONNECTIONS: web.AppKey[dict[str, web.WebSocketResponse]] = web.AppKey("active_connections", dict)
KEY_ADMIN_TOKEN = web.AppKey("admin_token", str)
KEY_HERMES_API_BASE_URL = web.AppKey("hermes_api_base_url", str)
KEY_HERMES_API_TOKEN = web.AppKey("hermes_api_token", str)


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
    return web.json_response({"code": code, "ttl_seconds": 600})


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

    session = request.app[KEY_PAIRING_MANAGER].redeem(code, device_hash)
    if session is None:
        return web.json_response({"error": "invalid_or_expired_code"}, status=400)

    log.info("Pairing réussi pour device_hash=%s...", device_hash[:8])
    return web.json_response({"session_token": session.token})


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


async def handle_chat_stream(request: web.Request) -> web.StreamResponse:
    """Proxy SSE : POST /api/sessions/{id}/chat/stream -> Hermes, forward en enveloppes WSS."""
    device_hash = _require_session(request)
    if device_hash is None:
        return web.json_response({"error": "unauthorized"}, status=401)

    session_id = request.match_info["session_id"]
    body = await request.read()

    hermes_api_base_url = request.app[KEY_HERMES_API_BASE_URL]
    hermes_api_token = request.app[KEY_HERMES_API_TOKEN]

    upstream_url = f"{hermes_api_base_url}/api/sessions/{session_id}/chat/stream"
    headers = {"Content-Type": "application/json"}
    if hermes_api_token:
        headers["Authorization"] = f"Bearer {hermes_api_token}"

    response = web.StreamResponse(
        status=200,
        headers={"Content-Type": "text/event-stream", "Cache-Control": "no-cache"},
    )
    await response.prepare(request)

    ws = request.app[KEY_ACTIVE_CONNECTIONS].get(device_hash)

    async with aiohttp.ClientSession() as client:
        async with client.post(upstream_url, data=body, headers=headers) as upstream:
            async for chunk in upstream.content.iter_any():
                await response.write(chunk)
                if ws is not None and not ws.closed:
                    envelope = Envelope(
                        channel="chat", type="stream_chunk", payload={"raw": chunk.decode("utf-8", errors="replace")}
                    ).to_dict()
                    await ws.send_json(envelope)

    await response.write_eof()
    return response


# ─────────────────────────── WebSocket ───────────────────────────


async def handle_ws(request: web.Request) -> web.WebSocketResponse:
    pairing_manager = request.app[KEY_PAIRING_MANAGER]
    token = request.query.get("token")
    session = pairing_manager.touch(token) if token else None
    if session is None:
        ws = web.WebSocketResponse()
        await ws.prepare(request)
        await ws.close(code=4401, message=b"invalid_or_expired_session")
        return ws

    device_hash = session.device_hash
    active_connections = request.app[KEY_ACTIVE_CONNECTIONS]

    ws = web.WebSocketResponse(heartbeat=30)
    await ws.prepare(request)

    previous = active_connections.get(device_hash)
    if previous is not None and not previous.closed:
        await previous.close(code=4000, message=b"superseded_by_new_connection")

    active_connections[device_hash] = ws
    log.info("WS connecté device_hash=%s...", device_hash[:8])

    for pending_envelope in request.app[KEY_PUSH_BUFFER].drain(device_hash):
        await ws.send_json(pending_envelope)

    try:
        async for msg in ws:
            if msg.type == aiohttp.WSMsgType.TEXT:
                await _dispatch_inbound(ws, msg.data)
            elif msg.type == aiohttp.WSMsgType.ERROR:
                log.warning("WS erreur device_hash=%s...: %s", device_hash[:8], ws.exception())
    finally:
        if active_connections.get(device_hash) is ws:
            del active_connections[device_hash]
        log.info("WS déconnecté device_hash=%s...", device_hash[:8])

    return ws


async def _dispatch_inbound(ws: web.WebSocketResponse, raw: str) -> None:
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

    # Les canaux chat/proactive/bridge sont acquittés ici ; le routage métier
    # complet (bridge -> device tools, chat -> session Hermes) arrive avec
    # les étapes suivantes du portage.
    log.info("Reçu channel=%s type=%s id=%s", envelope.channel, envelope.type, envelope.id)


# ─────────────────────────── App factory ───────────────────────────


def create_app(
    *,
    admin_token: str = "",
    hermes_api_base_url: str = "http://127.0.0.1:8443",
    hermes_api_token: str = "",
    pairing_rate_limit_attempts: int = PAIRING_RATE_LIMIT_ATTEMPTS,
    pairing_rate_limit_window_seconds: float = PAIRING_RATE_LIMIT_WINDOW_SECONDS,
) -> web.Application:
    app = web.Application()

    app[KEY_PAIRING_MANAGER] = PairingManager()
    app[KEY_PUSH_BUFFER] = PushBuffer()
    app[KEY_RATE_LIMITER] = RateLimiter(pairing_rate_limit_attempts, pairing_rate_limit_window_seconds)
    app[KEY_ACTIVE_CONNECTIONS] = {}
    app[KEY_ADMIN_TOKEN] = admin_token
    app[KEY_HERMES_API_BASE_URL] = hermes_api_base_url
    app[KEY_HERMES_API_TOKEN] = hermes_api_token

    app.router.add_get("/health", handle_health)
    app.router.add_post("/pairing/create", handle_pairing_create)
    app.router.add_post("/pairing/register", handle_pairing_register)
    app.router.add_post("/phone/message", handle_phone_message)
    app.router.add_get("/phone/replies", handle_phone_replies)
    app.router.add_get("/phone/outbound", handle_phone_outbound)
    app.router.add_post("/api/sessions/{session_id}/chat/stream", handle_chat_stream)
    app.router.add_get("/ws", handle_ws)
    return app


def create_app_from_env() -> web.Application:
    return create_app(
        admin_token=os.environ.get("RELAY_ADMIN_TOKEN", ""),
        hermes_api_base_url=os.environ.get("HERMES_API_BASE_URL", "http://127.0.0.1:8443"),
        hermes_api_token=os.environ.get("HERMES_API_TOKEN", ""),
    )


if __name__ == "__main__":
    log.info("Relay Server démarrage sur http://%s:%d", HOST, PORT)
    web.run_app(create_app_from_env(), host=HOST, port=PORT)
