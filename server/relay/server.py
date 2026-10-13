"""Relay Server — connexion WebSocket permanente multiplexée entre l'app
Android et l'agent Hermes, avec pairing QR, push buffer, et proxy SSE.

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
    WEBUI_URL           (optionnel) — URL publique hermes-webui, incluse dans
                         la réponse de POST /pairing/create pour que l'app
                         s'auto-configure au scan du QR (voir docs/webui-migration.md).
                         Valeur à copier depuis ~/hermes-webui/.env — pas de
                         lecture croisée de fichier entre les deux services.
    WEBUI_PASSWORD      (optionnel) — doit être défini SI ET SEULEMENT SI
                         WEBUI_URL l'est aussi (les deux ou aucun, voir
                         handle_pairing_create) — même valeur que
                         HERMES_WEBUI_PASSWORD dans ~/hermes-webui/.env.
    RELAY_FCM_CREDENTIALS_PATH (optionnel) — chemin vers le fichier JSON de
                         service account Firebase (obtenu depuis la console
                         Firebase du projet, Paramètres du projet > Comptes de
                         service > Générer une nouvelle clé privée). Si absent,
                         les notifications proactives restent fonctionnelles
                         via le WebSocket + push buffer, simplement sans réveil
                         FCM quand l'app est hors ligne — dégradation
                         gracieuse, pas un prérequis dur.
"""

from __future__ import annotations

import asyncio
import functools
import hmac
import json
import logging
import os
import time
from pathlib import Path
from typing import Any

import aiohttp
from aiohttp import web

from bridge_commands import BridgeCommandRegistry, CommandTimeoutError
from chat_stream import ChatSessionRegistry
from device_watch import DeviceChangeSignal
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

# chat/health est une opération ponctuelle (une requête, une réponse) —
# timeout court, sans rapport avec le watchdog généreux de chat_stream.py
# conçu pour des tool calls longs.
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
# hermes-webui (chat) — serveur distinct du relay, config exposée uniquement
# via /pairing/create pour que l'app puisse s'auto-configurer au scan du QR.
# Absents par défaut : un déploiement relay sans hermes-webui reste
# fonctionnel pour le bridge, juste sans ces deux champs dans la réponse.
KEY_WEBUI_URL = web.AppKey("webui_url", str)
KEY_WEBUI_PASSWORD = web.AppKey("webui_password", str)
# Signal "un device a changé" (connexion/déconnexion/capabilities) — pour
# GET /devices/watch, consommé par plugin/hasan_delivery pour savoir quand
# relire GET /devices sans avoir à faire du polling serré. Voir device_watch.py.
KEY_DEVICE_WATCH = web.AppKey("device_watch", DeviceChangeSignal)
# Instance firebase_admin.App initialisée si RELAY_FCM_CREDENTIALS_PATH est
# configuré, None sinon (déploiement sans FCM reste pleinement fonctionnel —
# voir _init_fcm_app/_send_fcm_wake). Type Any : firebase-admin est une
# dépendance optionnelle, pas importée au niveau module.
KEY_FCM_APP: web.AppKey[Any] = web.AppKey("fcm_app", object)


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


def _is_admin(request: web.Request) -> bool:
    admin_token = request.app[KEY_ADMIN_TOKEN]
    if not admin_token:
        return False
    auth = request.headers.get("Authorization", "")
    return hmac.compare_digest(auth.encode("utf-8"), f"Bearer {admin_token}".encode("utf-8"))


def _resolve_messaging_device(
    request: web.Request, requested_device_id: str | None
) -> tuple[str | None, web.Response | None]:
    """Device visé par un appel de messagerie (/phone/message, /phone/outbound).

    Deux appelants possibles, retourne (device_hash, None) ou (None, erreur) :

    - le plugin Hermes, authentifié par l'admin_token (comme /bridge/command) :
      le device est [requested_device_id] s'il est fourni, sinon le device
      appairé vu le plus récemment — transparent tant qu'un seul téléphone est
      appairé, ce qui est le cas courant ;
    - un appelant muni d'un session_token (historique) : le device est celui
      de la session.

    L'admin_token est préféré pour le plugin parce qu'un session_token est
    remplacé à chaque POST /pairing/refresh du téléphone (voir
    PairingManager.refresh) : une copie de ce token conservée côté plugin
    devenait invalide au premier renouvellement, et la messagerie tombait en
    401 jusqu'à une intervention manuelle.
    """
    if not _is_admin(request):
        device_hash = _require_session(request)
        if device_hash is None:
            return None, web.json_response({"error": "unauthorized"}, status=401)
        return device_hash, None

    devices = request.app[KEY_PAIRING_MANAGER].list_devices()
    if requested_device_id:
        if not any(s.device_hash == requested_device_id for s in devices):
            return None, web.json_response({"error": "unknown_device"}, status=404)
        return requested_device_id, None
    if not devices:
        return None, web.json_response({"error": "no_paired_device"}, status=409)
    return max(devices, key=lambda s: s.last_seen_at).device_hash, None


# ─────────────────────────── Routes HTTP ───────────────────────────


async def handle_health(request: web.Request) -> web.Response:
    return web.json_response({"status": "ok", "timestamp": time.time()})


_VERSION_FILE = Path(__file__).parent / "VERSION"


def _read_relay_version() -> str:
    try:
        return _VERSION_FILE.read_text(encoding="utf-8").strip()
    except OSError:
        return "unknown"


def _read_relay_commit() -> str | None:
    """SHA du commit courant si le déploiement est un checkout git, sinon None.

    Best-effort : un déploiement peut aussi être une simple copie de fichiers
    sans .git (ou git absent du PATH) — ne jamais faire échouer /version pour ça.
    """
    import subprocess

    try:
        result = subprocess.run(
            ["git", "rev-parse", "HEAD"],
            cwd=Path(__file__).parent,
            capture_output=True,
            text=True,
            timeout=5,
        )
        if result.returncode == 0:
            return result.stdout.strip()
    except (OSError, subprocess.SubprocessError):
        pass
    return None


async def handle_version(request: web.Request) -> web.Response:
    return web.json_response({
        "version": _read_relay_version(),
        "commit": _read_relay_commit(),
    })


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

    # hermes-webui optionnel — seulement si les deux champs sont configurés
    # (WEBUI_URL/WEBUI_PASSWORD sur hermes-relay.service), jamais l'un sans
    # l'autre pour ne pas produire un QR incomplet côté app.
    webui_url = request.app[KEY_WEBUI_URL]
    webui_password = request.app[KEY_WEBUI_PASSWORD]
    if webui_url and webui_password:
        response["webui_url"] = webui_url
        response["webui_password"] = webui_password

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


async def _send_fcm_wake(app: web.Application, device_hash: str) -> None:
    """Réveil FCM data-only best-effort — ne doit JAMAIS faire échouer
    /phone/message : le message reste bufferisé (push_buffer) quoi qu'il
    arrive, FCM n'est qu'un accélérateur de livraison quand l'app n'a pas de
    WS actif. Silencieux si firebase-admin n'est pas configuré (déploiement
    sans FCM) ou si ce device n'a jamais transmis de token (app non mise à
    jour, ou FCM indisponible côté device).

    Payload strictement data-only ({"type": "wake"}) — jamais de bloc
    `notification`, jamais le texte du message ni le device_hash : Google ne
    doit voir qu'un signal de réveil opaque, jamais le contenu (voir
    HasanFirebaseMessagingService côté app, qui va chercher le vrai texte via
    GET /phone/pending, un canal privé TLS vers ce relay).
    """
    fcm_app = app.get(KEY_FCM_APP)
    if fcm_app is None:
        return  # FCM non configuré côté serveur — comportement pré-FCM inchangé
    session = app[KEY_PAIRING_MANAGER].get_session_by_device_hash(device_hash)
    if session is None or not session.fcm_token:
        return

    from firebase_admin import messaging  # import tardif, module optionnel

    message = messaging.Message(
        data={"type": "wake"},
        token=session.fcm_token,
        android=messaging.AndroidConfig(priority="high"),
    )
    try:
        # app doit être passé en keyword — messaging.send(message, dry_run,
        # app) : un 3e positionnel atterrit sur dry_run, pas app, et déclenche
        # un envoi factice (fake_message_id) sans jamais toucher le device.
        await asyncio.get_event_loop().run_in_executor(
            None, functools.partial(messaging.send, message, app=fcm_app)
        )
        log.info("Réveil FCM envoyé device_hash=%s...", device_hash[:8])
    except Exception as exc:
        # Token possiblement périmé (désinstall, reset FCM) — log seulement,
        # ne pas invalider automatiquement (une invalidation erronée coûte
        # cher : prochain réveil silencieusement perdu jusqu'à la prochaine
        # connexion WS). Nettoyage best-effort laissé à une itération future
        # si le volume d'erreurs le justifie.
        log.warning("Échec envoi FCM device_hash=%s...: %s", device_hash[:8], exc)


async def handle_phone_message(request: web.Request) -> web.Response:
    """Entrée pour le plugin Hermes : pousse un message vers l'app (via push buffer + WS live).

    Corps : {"text": str, "device_id"?: str} — device_id n'a de sens qu'avec
    l'admin_token, voir [_resolve_messaging_device].
    """
    # Authentifier avant de lire le corps : un appelant non authentifié ne
    # doit rien apprendre du format attendu.
    if not _is_admin(request) and _require_session(request) is None:
        return web.json_response({"error": "unauthorized"}, status=401)

    try:
        body = await request.json()
    except json.JSONDecodeError:
        return web.json_response({"error": "invalid_json"}, status=400)
    if not isinstance(body, dict):
        return web.json_response({"error": "invalid_json"}, status=400)

    requested = body.get("device_id")
    device_hash, error = _resolve_messaging_device(request, requested if isinstance(requested, str) else None)
    if error is not None:
        return error

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
        await _send_fcm_wake(request.app, device_hash)

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

    Accepte l'admin_token (plugin Hermes) ou un session_token. Avec
    l'admin_token, aucun device n'est exigé : le plugin peut ainsi se
    connecter avant tout appairage, au lieu de rester en erreur de
    configuration jusqu'au premier QR scanné.
    """
    if not _is_admin(request) and _require_session(request) is None:
        return web.json_response({"error": "unauthorized"}, status=401)

    try:
        timeout = float(request.query.get("timeout", "30"))
    except ValueError:
        timeout = 30.0
    timeout = max(0.0, min(timeout, 60.0))

    await asyncio.sleep(timeout)
    return web.json_response({"replies": []})


async def handle_phone_outbound(request: web.Request) -> web.Response:
    device_hash, error = _resolve_messaging_device(request, request.query.get("device_id"))
    if error is not None:
        return error
    return web.json_response({"pending": request.app[KEY_PUSH_BUFFER].pending_count(device_hash)})


async def handle_phone_pending(request: web.Request) -> web.Response:
    """Draine (vide) le push buffer de ce device sans passer par le WS —
    utilisé par HasanFirebaseMessagingService.onMessageReceived() après un
    réveil FCM data-only : l'app n'a aucune raison de rouvrir tout le
    WebSocket juste pour récupérer 1-2 messages en tâche de fond (coûteux en
    latence/énergie). Contrat volontairement identique à ce que drain()
    fournit déjà au WS connect (voir handle_ws) : les enveloppes sont
    retirées du buffer ici, pas juste consultées — /phone/pending n'est PAS
    idempotent, un second appel immédiat renvoie liste vide. Si l'app rouvre
    le WS juste après un drainage HTTP, aucune redélivraison (le buffer est
    déjà vide).

    Distinct de GET /phone/outbound (qui reste un peek — juste un compteur,
    lecture seule, contrat inchangé pour ne pas casser un appelant existant).
    """
    device_hash = _require_session(request)
    if device_hash is None:
        return web.json_response({"error": "unauthorized"}, status=401)
    envelopes = request.app[KEY_PUSH_BUFFER].drain(device_hash)
    return web.json_response({"messages": envelopes})


async def handle_fcm_token(request: web.Request) -> web.Response:
    """Reçoit le token FCM courant du device — appelé par
    HasanFirebaseMessagingService.onNewToken() côté app, indépendamment du
    cycle de vie du WS (un token peut être régénéré par Firebase alors que
    l'app est tuée/hors connexion, donc le canal WS system/capabilities,
    envoyé seulement à onOpen(), ne suffirait pas pour une resynchronisation
    immédiate). Voie canonique unique de synchronisation du token FCM.
    """
    device_hash = _require_session(request)
    if device_hash is None:
        return web.json_response({"error": "unauthorized"}, status=401)

    try:
        body = await request.json()
    except json.JSONDecodeError:
        return web.json_response({"error": "invalid_json"}, status=400)

    fcm_token = body.get("fcm_token")
    if fcm_token is not None and not isinstance(fcm_token, str):
        return web.json_response({"error": "invalid_fcm_token"}, status=400)
    # fcm_token == None accepté explicitement (désenregistrement, ex: appel
    # depuis un futur "désactiver notifications proactives" dans Réglages).

    request.app[KEY_PAIRING_MANAGER].update_fcm_token(device_hash, fcm_token)
    return web.json_response({"ok": True})


async def handle_capabilities(request: web.Request) -> web.Response:
    """Capabilities annoncées par le device (envelope system/capabilities), pour
    plugin/hasan_delivery/tools.py — évite de dupliquer les schémas des capabilities
    téléphone côté Python : l'app reste la seule source de vérité (Capability.kt).

    Liste vide (pas d'erreur) si le device ne s'est encore jamais connecté depuis
    l'ajout de ce mécanisme, ou n'a annoncé aucune capability activée+autorisée.
    """
    device_hash = _require_session(request)
    if device_hash is None:
        return web.json_response({"error": "unauthorized"}, status=401)
    session = request.app[KEY_PAIRING_MANAGER].get_session_by_device_hash(device_hash)
    capabilities = session.capabilities if session and session.capabilities else []
    return web.json_response({"capabilities": capabilities})


async def handle_devices_list(request: web.Request) -> web.Response:
    """GET /devices — admin-authed, liste tous les devices connus (persistés),
    avec leur état de connexion live et leurs capabilities. Consommé par
    plugin/hasan_delivery/tools.py pour construire l'union des tools
    function-calling à travers tous les devices appairés (voir
    handle_pairing_create pour le modèle d'auth repris ici : admin_token, pas
    session_token — cet endpoint est intrinsèquement cross-device, aucun
    session_token de device particulier ne pourrait le scoper correctement).
    """
    admin_token = request.app[KEY_ADMIN_TOKEN]
    if not admin_token:
        return web.json_response({"error": "admin_token_not_configured"}, status=503)
    auth = request.headers.get("Authorization", "")
    if auth != f"Bearer {admin_token}":
        return web.json_response({"error": "unauthorized"}, status=401)

    active_connections = request.app[KEY_ACTIVE_CONNECTIONS]
    devices = []
    for session in request.app[KEY_PAIRING_MANAGER].list_devices():
        ws = active_connections.get(session.device_hash)
        devices.append({
            "device_id": session.device_hash,
            "label": session.device_label,
            "connected": ws is not None and not ws.closed,
            "capabilities": session.capabilities or [],
        })
    return web.json_response({"devices": devices})


async def handle_devices_watch(request: web.Request) -> web.Response:
    """GET /devices/watch?timeout=30 — long-poll admin-authed : bloque jusqu'à
    ce que l'ensemble des devices OU les capabilities/label d'un device
    changent, ou jusqu'à `timeout` secondes. Retourne juste un signal, pas
    l'état lui-même — l'appelant relit GET /devices après réveil (voir
    device_watch.py pour le pourquoi d'un signal unique non granulaire).
    """
    admin_token = request.app[KEY_ADMIN_TOKEN]
    if not admin_token:
        return web.json_response({"error": "admin_token_not_configured"}, status=503)
    auth = request.headers.get("Authorization", "")
    if auth != f"Bearer {admin_token}":
        return web.json_response({"error": "unauthorized"}, status=401)

    try:
        timeout = float(request.query.get("timeout", "30"))
    except ValueError:
        timeout = 30.0
    timeout = max(0.0, min(timeout, 60.0))

    changed = await request.app[KEY_DEVICE_WATCH].wait(timeout)
    return web.json_response({"changed": changed})


async def handle_bridge_command(request: web.Request) -> web.Response:
    """Entrée pour le plugin Hermes (tool function-calling) : exécute une
    capability sur UN device explicite via le canal `bridge` du WS, attend le
    résultat borné dans le temps (voir bridge_commands.py).

    Authentification par admin_token (pas session_token) — le token de
    session est intrinsèquement scopé à un device, alors que ce endpoint
    cible n'importe quel device connu depuis que le plugin gère plusieurs
    devices simultanément ; le plugin agit comme un opérateur de confiance
    cross-device, pas comme un device particulier (même modèle que
    GET /devices).

    Contrat requête : {"device_id": str, "capability": str, "params": dict}
    — device_id correspond au device_hash retourné par GET /devices.
    Retourne le résultat de la capability tel que renvoyé par le téléphone,
    ou une erreur si le device n'est pas connecté / ne répond pas à temps.
    """
    admin_token = request.app[KEY_ADMIN_TOKEN]
    if not admin_token:
        return web.json_response({"error": "admin_token_not_configured"}, status=503)
    auth = request.headers.get("Authorization", "")
    if auth != f"Bearer {admin_token}":
        return web.json_response({"error": "unauthorized"}, status=401)

    try:
        body = await request.json()
    except json.JSONDecodeError:
        return web.json_response({"error": "invalid_json"}, status=400)

    device_id = body.get("device_id")
    if not isinstance(device_id, str) or not device_id:
        return web.json_response({"error": "missing_device_id"}, status=400)
    capability = body.get("capability")
    if not isinstance(capability, str) or not capability:
        return web.json_response({"error": "missing_capability"}, status=400)
    params = body.get("params")
    if params is None:
        params = {}
    if not isinstance(params, dict):
        return web.json_response({"error": "params_must_be_object"}, status=400)

    ws = request.app[KEY_ACTIVE_CONNECTIONS].get(device_id)
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

    # heartbeat=30 (ping serveur + timeout de réception au même délai) s'est révélé
    # trop agressif sur liaison mobile réelle : dès qu'un pong accusait ne serait-ce
    # qu'une légère latence/gigue, aiohttp fermait la connexion — observé en pratique
    # comme un cycle de reconnexion toutes les 30-55s en continu (voir latency.log,
    # "sent ping but didn't receive pong within 30000ms"). 60s laisse une marge large
    # sans pour autant tarder à détecter une vraie coupure (le watchdog applicatif
    # HERMES_WATCHDOG_TIMEOUT_SECONDS=300s de chat_stream.py reste la protection de
    # dernier recours sur un tour de chat bloqué).
    ws = web.WebSocketResponse(heartbeat=60)
    await ws.prepare(request)

    session = await _authenticate_ws(ws, pairing_manager)
    if session is None:
        await ws.close(code=4401, message=b"invalid_or_expired_session")
        return ws

    device_hash = session.device_hash
    active_connections = request.app[KEY_ACTIVE_CONNECTIONS]
    device_watch = request.app[KEY_DEVICE_WATCH]

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
    device_watch.bump()
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
                    pairing_manager=request.app[KEY_PAIRING_MANAGER],
                    device_watch=device_watch,
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
            device_watch.bump()
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
    ws: web.WebSocketResponse, envelope: Envelope, hermes_api_base_url: str, hermes_token: str
) -> None:
    """chat/clarify_response — relaye la réponse de l'utilisateur vers Hermes."""
    session_id = envelope.payload.get("session_id") or envelope.payload.get("id")
    clarify_id = envelope.payload.get("clarify_id")
    response_text = envelope.payload.get("response")
    if not session_id or not clarify_id or response_text is None:
        await ws.send_json(Envelope(channel="chat", type="error", id=envelope.id, payload={
            "reason": "invalid_payload",
            "message": "clarify_id, session_id et response sont requis"
        }).to_dict())
        return

    try:
        async with aiohttp.ClientSession() as client:
            async with client.post(
                f"{hermes_api_base_url}/api/sessions/{session_id}/clarify-response",
                json={"clarify_id": clarify_id, "response": response_text},
                headers={"Authorization": f"Bearer {hermes_token}"} if hermes_token else {},
                timeout=aiohttp.ClientTimeout(total=10),
            ) as resp:
                if resp.status == 200:
                    return  # Pas d'ack — les tokens qui suivent confirment
                error_body = (await resp.text())[:200]
                await ws.send_json(Envelope(channel="chat", type="error", id=envelope.id, payload={
                    "reason": "clarify_expired",
                    "message": f"Clarification expirée (HTTP {resp.status}): {error_body}"
                }).to_dict())
    except (aiohttp.ClientError, asyncio.TimeoutError) as exc:
        await ws.send_json(Envelope(channel="chat", type="error", id=envelope.id, payload={
            "reason": "clarify_error",
            "message": f"Erreur lors de l'envoi de la clarification: {exc}"
        }).to_dict())
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
    pairing_manager: PairingManager,
    device_watch: DeviceChangeSignal,
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

    if envelope.channel == "system" and envelope.type == "capabilities":
        caps = envelope.payload.get("capabilities")
        if isinstance(caps, list):
            device_label = envelope.payload.get("device_label")
            if device_label is not None and not isinstance(device_label, str):
                device_label = None
            changed = pairing_manager.update_capabilities(device_hash, caps, device_label)
            if changed:
                device_watch.bump()
        else:
            log.warning("Enveloppe system/capabilities malformée (capabilities non-liste) id=%s", envelope.id)
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

    if envelope.channel == "chat" and envelope.type == "clarify_response":
        await _handle_chat_clarify_response(ws, envelope, hermes_api_base_url, hermes_api_token)
        return
    if envelope.channel == "chat" and envelope.type == "health":
        await _handle_chat_health(ws, envelope, hermes_api_base_url)
        return

    # Le canal proactive est acquitté ici (pas de routage métier entrant attendu).
    log.info("Reçu channel=%s type=%s id=%s", envelope.channel, envelope.type, envelope.id)


# ─────────────────────────── App factory ───────────────────────────


def _init_fcm_app(credentials_path: str) -> Any:
    """None si non configuré (déploiement sans FCM reste pleinement
    fonctionnel — comportement pré-FCM) ou si le fichier est absent/invalide.
    Ne fait jamais planter le démarrage du relay pour une mauvaise config FCM
    — un opérateur qui se trompe de chemin doit voir son relay démarrer quand
    même (juste sans réveils FCM), pas un crash au boot."""
    if not credentials_path:
        return None
    try:
        import firebase_admin
        from firebase_admin import credentials

        cred = credentials.Certificate(credentials_path)
        return firebase_admin.initialize_app(cred)
    except Exception as exc:
        log.error("Échec init firebase-admin (%s) — réveils FCM désactivés: %s", credentials_path, exc)
        return None


def create_app(
    *,
    admin_token: str = "",
    hermes_api_base_url: str = "http://127.0.0.1:8443",
    hermes_api_token: str = "",
    public_url: str = "",
    webui_url: str = "",
    webui_password: str = "",
    pairing_rate_limit_attempts: int = PAIRING_RATE_LIMIT_ATTEMPTS,
    pairing_rate_limit_window_seconds: float = PAIRING_RATE_LIMIT_WINDOW_SECONDS,
    sessions_path: Path | None = None,
    fcm_credentials_path: str = "",
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
    app[KEY_WEBUI_URL] = webui_url.rstrip("/")
    app[KEY_WEBUI_PASSWORD] = webui_password
    app[KEY_DEVICE_WATCH] = DeviceChangeSignal()
    app[KEY_FCM_APP] = _init_fcm_app(fcm_credentials_path)

    app.router.add_get("/health", handle_health)
    app.router.add_get("/version", handle_version)
    app.router.add_post("/pairing/create", handle_pairing_create)
    app.router.add_post("/pairing/register", handle_pairing_register)
    app.router.add_post("/pairing/refresh", handle_pairing_refresh)
    app.router.add_post("/phone/message", handle_phone_message)
    app.router.add_get("/phone/replies", handle_phone_replies)
    app.router.add_get("/phone/outbound", handle_phone_outbound)
    app.router.add_get("/phone/pending", handle_phone_pending)
    app.router.add_post("/fcm-token", handle_fcm_token)
    app.router.add_post("/bridge/command", handle_bridge_command)
    app.router.add_get("/capabilities", handle_capabilities)
    app.router.add_get("/devices", handle_devices_list)
    app.router.add_get("/devices/watch", handle_devices_watch)
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
        webui_url=os.environ.get("WEBUI_URL", ""),
        webui_password=os.environ.get("WEBUI_PASSWORD", ""),
        sessions_path=sessions_path,
        fcm_credentials_path=os.environ.get("RELAY_FCM_CREDENTIALS_PATH", ""),
    )


if __name__ == "__main__":
    log.info("Relay Server démarrage sur http://%s:%d", HOST, PORT)
    web.run_app(create_app_from_env(), host=HOST, port=PORT)
