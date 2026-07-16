"""Suite pytest pour le relay server — couvre tout ce qui est automatisable
sans device Android réel (HTTP, WebSocket, pairing, buffer, rate limit).

Lancer : pytest -v
"""

from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path

import pytest
from aiohttp import WSMsgType
from aiohttp.test_utils import TestClient, TestServer

import server
from envelope import Envelope
from pairing import PairingManager

ADMIN_TOKEN = "test-admin-secret"


def make_device_hash(seed: str) -> str:
    return hashlib.sha256(seed.encode("utf-8")).hexdigest()


@pytest.fixture
async def client(aiohttp_client):
    app = server.create_app(admin_token=ADMIN_TOKEN)
    return await aiohttp_client(app)


@pytest.fixture
async def paired_client(client):
    """Un client avec un pairing déjà effectué. Retourne (client, session_token, device_hash)."""
    resp = await client.post("/pairing/create", headers={"Authorization": f"Bearer {ADMIN_TOKEN}"})
    code = (await resp.json())["code"]

    device_hash = make_device_hash("device-fixture")
    resp = await client.post("/pairing/register", json={"code": code, "device_hash": device_hash})
    token = (await resp.json())["session_token"]

    return client, token, device_hash


@pytest.fixture
async def paired_client_with_refresh(client):
    """Comme paired_client, mais retourne aussi le refresh_token."""
    resp = await client.post("/pairing/create", headers={"Authorization": f"Bearer {ADMIN_TOKEN}"})
    code = (await resp.json())["code"]

    device_hash = make_device_hash("device-fixture-refresh")
    resp = await client.post("/pairing/register", json={"code": code, "device_hash": device_hash})
    body = await resp.json()

    return client, body["session_token"], body["refresh_token"], device_hash


# ─────────────────────────── Health ───────────────────────────


async def test_health(client):
    resp = await client.get("/health")
    assert resp.status == 200
    body = await resp.json()
    assert body["status"] == "ok"
    assert "timestamp" in body


# ─────────────────────────── Pairing ───────────────────────────


async def test_pairing_create_requires_admin_token(client):
    resp = await client.post("/pairing/create")
    assert resp.status == 401


async def test_pairing_create_disabled_without_admin_token(aiohttp_client):
    app = server.create_app(admin_token="")  # pas d'admin token configuré
    c = await aiohttp_client(app)
    resp = await c.post("/pairing/create", headers={"Authorization": "Bearer whatever"})
    assert resp.status == 503


async def test_pairing_create_with_wrong_token(client):
    resp = await client.post("/pairing/create", headers={"Authorization": "Bearer wrong-token"})
    assert resp.status == 401


async def test_pairing_create_ok(client):
    resp = await client.post("/pairing/create", headers={"Authorization": f"Bearer {ADMIN_TOKEN}"})
    assert resp.status == 200
    body = await resp.json()
    assert len(body["code"]) == 6
    assert body["code"].isalnum()
    assert body["ttl_seconds"] == 600


async def test_pairing_create_omits_relay_url_when_not_configured(client):
    """Sans public_url configuré côté serveur, le QR n'a pas de quoi construire relay_url."""
    resp = await client.post("/pairing/create", headers={"Authorization": f"Bearer {ADMIN_TOKEN}"})
    body = await resp.json()
    assert "relay_url" not in body


async def test_pairing_create_includes_relay_url_when_configured(aiohttp_client):
    app = server.create_app(admin_token=ADMIN_TOKEN, public_url="https://relay.example.com:8767")
    c = await aiohttp_client(app)
    resp = await c.post("/pairing/create", headers={"Authorization": f"Bearer {ADMIN_TOKEN}"})
    body = await resp.json()
    assert body["relay_url"] == "https://relay.example.com:8767"


async def test_pairing_create_omits_webui_fields_when_not_configured(client):
    """Sans WEBUI_URL/WEBUI_PASSWORD, le QR reste un QR bridge valide sans configurer le chat."""
    resp = await client.post("/pairing/create", headers={"Authorization": f"Bearer {ADMIN_TOKEN}"})
    body = await resp.json()
    assert "webui_url" not in body
    assert "webui_password" not in body


async def test_pairing_create_includes_webui_fields_when_both_configured(aiohttp_client):
    app = server.create_app(
        admin_token=ADMIN_TOKEN,
        webui_url="https://34.155.193.170",
        webui_password="test-webui-secret",
    )
    c = await aiohttp_client(app)
    resp = await c.post("/pairing/create", headers={"Authorization": f"Bearer {ADMIN_TOKEN}"})
    body = await resp.json()
    assert body["webui_url"] == "https://34.155.193.170"
    assert body["webui_password"] == "test-webui-secret"


async def test_pairing_create_omits_webui_fields_when_only_one_configured(aiohttp_client):
    """webui_url et webui_password sont les deux ou aucun — jamais un QR à moitié configuré."""
    app = server.create_app(admin_token=ADMIN_TOKEN, webui_url="https://34.155.193.170")
    c = await aiohttp_client(app)
    resp = await c.post("/pairing/create", headers={"Authorization": f"Bearer {ADMIN_TOKEN}"})
    body = await resp.json()
    assert "webui_url" not in body
    assert "webui_password" not in body


async def test_pairing_register_with_bad_code(client):
    resp = await client.post(
        "/pairing/register", json={"code": "NOPE00", "device_hash": make_device_hash("x")}
    )
    assert resp.status == 400
    body = await resp.json()
    assert body["error"] == "invalid_or_expired_code"


async def test_pairing_register_ok(client):
    resp = await client.post("/pairing/create", headers={"Authorization": f"Bearer {ADMIN_TOKEN}"})
    code = (await resp.json())["code"]

    resp = await client.post(
        "/pairing/register", json={"code": code, "device_hash": make_device_hash("d1")}
    )
    assert resp.status == 200
    body = await resp.json()
    assert isinstance(body["session_token"], str)
    assert len(body["session_token"]) > 20


async def test_pairing_code_is_single_use(client):
    resp = await client.post("/pairing/create", headers={"Authorization": f"Bearer {ADMIN_TOKEN}"})
    code = (await resp.json())["code"]
    device_hash = make_device_hash("d2")

    resp1 = await client.post("/pairing/register", json={"code": code, "device_hash": device_hash})
    assert resp1.status == 200

    resp2 = await client.post("/pairing/register", json={"code": code, "device_hash": device_hash})
    assert resp2.status == 400


async def test_pairing_register_rejects_non_hex_device_hash(client):
    resp = await client.post("/pairing/create", headers={"Authorization": f"Bearer {ADMIN_TOKEN}"})
    code = (await resp.json())["code"]

    resp = await client.post("/pairing/register", json={"code": code, "device_hash": "not-a-sha256"})
    assert resp.status == 400


async def test_pairing_register_missing_fields(client):
    resp = await client.post("/pairing/register", json={"code": "ABC123"})
    assert resp.status == 400
    body = await resp.json()
    assert body["error"] == "missing_fields"


async def test_pairing_rate_limit(aiohttp_client):
    app = server.create_app(admin_token=ADMIN_TOKEN, pairing_rate_limit_attempts=3, pairing_rate_limit_window_seconds=60.0)
    c = await aiohttp_client(app)

    for _ in range(3):
        resp = await c.post("/pairing/register", json={"code": "BADCOD", "device_hash": make_device_hash("r")})
        assert resp.status == 400  # code invalide, mais pas encore rate-limited

    resp = await c.post("/pairing/register", json={"code": "BADCOD", "device_hash": make_device_hash("r")})
    assert resp.status == 429
    body = await resp.json()
    assert body["error"] == "rate_limited"


# ─────────────────────────── Auth sur routes protégées ───────────────────────────


async def test_phone_message_requires_auth(client):
    resp = await client.post("/phone/message", json={"text": "hi"})
    assert resp.status == 401


async def test_phone_message_rejects_bad_token(client):
    resp = await client.post(
        "/phone/message", json={"text": "hi"}, headers={"Authorization": "Bearer garbage"}
    )
    assert resp.status == 401


async def test_phone_outbound_requires_auth(client):
    resp = await client.get("/phone/outbound")
    assert resp.status == 401


async def test_phone_replies_requires_auth(client):
    resp = await client.get("/phone/replies")
    assert resp.status == 401


async def test_phone_replies_long_poll_returns_empty_list(paired_client):
    """timeout=0 pour ne pas ralentir la suite — vérifie juste le format de contrat."""
    client, token, _ = paired_client
    resp = await client.get(
        "/phone/replies", params={"timeout": "0"}, headers={"Authorization": f"Bearer {token}"}
    )
    assert resp.status == 200
    body = await resp.json()
    assert body["replies"] == []


async def test_phone_replies_clamps_negative_timeout(paired_client):
    """Une valeur négative ne doit ni crasher ni bloquer la suite (clampée à 0)."""
    client, token, _ = paired_client
    resp = await client.get(
        "/phone/replies", params={"timeout": "-5"}, headers={"Authorization": f"Bearer {token}"}
    )
    assert resp.status == 200


# ─────────────────────────── Push buffer ───────────────────────────


async def test_phone_message_buffers_when_no_ws_connected(paired_client):
    client, token, _ = paired_client

    resp = await client.post(
        "/phone/message", json={"text": "bonjour"}, headers={"Authorization": f"Bearer {token}"}
    )
    assert resp.status == 200
    body = await resp.json()
    assert body["delivered"] is False

    resp = await client.get("/phone/outbound", headers={"Authorization": f"Bearer {token}"})
    body = await resp.json()
    assert body["pending"] == 1


async def test_phone_message_missing_text(paired_client):
    client, token, _ = paired_client
    resp = await client.post("/phone/message", json={}, headers={"Authorization": f"Bearer {token}"})
    assert resp.status == 400


# ─────────────────────────── WebSocket ───────────────────────────
#
# Contrat d'auth : le session_token ne transite jamais dans l'URL (query
# param) — un token en query string finit dans les logs d'accès du reverse
# proxy, l'historique navigateur, etc. Le client se connecte SANS token dans
# l'URL, puis envoie comme premier message une enveloppe
# {channel: "system", type: "auth", payload: {session_token: "..."}}.


async def send_auth(ws, token: str) -> None:
    await ws.send_json({
        "version": 1, "channel": "system", "type": "auth", "id": "auth1",
        "payload": {"session_token": token},
    })


async def connect_and_auth(client, token: str):
    ws = await client.ws_connect("/ws")
    await send_auth(ws, token)
    return ws


async def test_ws_rejects_invalid_token(client):
    ws = await client.ws_connect("/ws")
    await send_auth(ws, "invalid-token")
    msg = await ws.receive()
    assert msg.type == WSMsgType.CLOSE
    assert msg.data == 4401


async def test_ws_rejects_missing_auth_message(client):
    """Un premier message qui n'est pas une enveloppe auth valide -> rejet."""
    ws = await client.ws_connect("/ws")
    await ws.send_json({"version": 1, "channel": "system", "type": "ping", "payload": {}})
    msg = await ws.receive()
    assert msg.type == WSMsgType.CLOSE
    assert msg.data == 4401


async def test_ws_rejects_auth_with_missing_token_field(client):
    ws = await client.ws_connect("/ws")
    await ws.send_json({"version": 1, "channel": "system", "type": "auth", "payload": {}})
    msg = await ws.receive()
    assert msg.type == WSMsgType.CLOSE
    assert msg.data == 4401


async def test_ws_auth_success_no_token_in_url(paired_client):
    """Le contrat central de cette suite : aucune query string, tout passe par le message."""
    client, token, _ = paired_client
    ws = await client.ws_connect("/ws")
    await send_auth(ws, token)
    # Une session valide ne ferme pas immédiatement — on peut échanger derrière.
    await ws.send_json({"version": 1, "channel": "system", "type": "ping", "id": "t1", "payload": {}})
    msg = await ws.receive_json()
    assert msg["type"] == "pong"
    await ws.close()


async def test_ws_ping_pong(paired_client):
    client, token, _ = paired_client
    ws = await connect_and_auth(client, token)

    await ws.send_json({"version": 1, "channel": "system", "type": "ping", "id": "t1", "payload": {}})
    msg = await ws.receive_json()
    assert msg["channel"] == "system"
    assert msg["type"] == "pong"
    await ws.close()


async def test_ws_rejects_unsupported_version(paired_client):
    client, token, _ = paired_client
    ws = await connect_and_auth(client, token)

    await ws.send_json({"version": 99, "channel": "system", "type": "ping", "payload": {}})
    msg = await ws.receive_json()
    assert msg["type"] == "error"
    assert "version" in msg["payload"]["error"]
    await ws.close()


async def test_ws_rejects_missing_version(paired_client):
    client, token, _ = paired_client
    ws = await connect_and_auth(client, token)

    await ws.send_json({"channel": "system", "type": "ping", "payload": {}})
    msg = await ws.receive_json()
    assert msg["type"] == "error"
    await ws.close()


async def test_ws_rejects_invalid_channel(paired_client):
    client, token, _ = paired_client
    ws = await connect_and_auth(client, token)

    await ws.send_json({"version": 1, "channel": "not_a_real_channel", "type": "ping", "payload": {}})
    msg = await ws.receive_json()
    assert msg["type"] == "error"
    await ws.close()


async def test_ws_drains_buffered_messages_on_connect(paired_client):
    client, token, _ = paired_client

    await client.post(
        "/phone/message", json={"text": "message en attente"}, headers={"Authorization": f"Bearer {token}"}
    )

    ws = await connect_and_auth(client, token)
    msg = await ws.receive_json()
    assert msg["channel"] == "proactive"
    assert msg["type"] == "message"
    assert msg["payload"]["text"] == "message en attente"
    await ws.close()


async def test_phone_message_delivers_live_when_ws_connected(paired_client):
    client, token, _ = paired_client
    ws = await connect_and_auth(client, token)

    resp = await client.post(
        "/phone/message", json={"text": "live"}, headers={"Authorization": f"Bearer {token}"}
    )
    body = await resp.json()
    assert body["delivered"] is True

    msg = await ws.receive_json()
    assert msg["payload"]["text"] == "live"
    await ws.close()


async def test_second_ws_connection_supersedes_first(paired_client):
    client, token, _ = paired_client
    ws1 = await connect_and_auth(client, token)
    ws2 = await connect_and_auth(client, token)

    msg = await ws1.receive()
    assert msg.type == WSMsgType.CLOSE
    assert msg.data == 4000

    assert not ws2.closed
    await ws2.close()


# ─────────────────────────── Refresh token ───────────────────────────


async def test_pairing_register_returns_refresh_token(client):
    resp = await client.post("/pairing/create", headers={"Authorization": f"Bearer {ADMIN_TOKEN}"})
    code = (await resp.json())["code"]

    resp = await client.post(
        "/pairing/register", json={"code": code, "device_hash": make_device_hash("rt1")}
    )
    body = await resp.json()
    assert isinstance(body["refresh_token"], str)
    assert len(body["refresh_token"]) > 20
    assert body["refresh_token"] != body["session_token"]


async def test_refresh_issues_new_session_and_refresh_token(paired_client_with_refresh):
    client, old_session_token, old_refresh_token, device_hash = paired_client_with_refresh

    resp = await client.post("/pairing/refresh", json={"refresh_token": old_refresh_token})
    assert resp.status == 200
    body = await resp.json()

    assert body["session_token"] != old_session_token
    assert body["refresh_token"] != old_refresh_token

    # Le nouveau session_token doit être utilisable immédiatement.
    resp = await client.get("/phone/outbound", headers={"Authorization": f"Bearer {body['session_token']}"})
    assert resp.status == 200


async def test_refresh_invalidates_old_session_token(paired_client_with_refresh):
    client, old_session_token, old_refresh_token, _ = paired_client_with_refresh

    await client.post("/pairing/refresh", json={"refresh_token": old_refresh_token})

    # L'ancien session_token ne doit plus fonctionner après rotation.
    resp = await client.get("/phone/outbound", headers={"Authorization": f"Bearer {old_session_token}"})
    assert resp.status == 401


async def test_refresh_token_is_single_use(paired_client_with_refresh):
    client, _, old_refresh_token, _ = paired_client_with_refresh

    resp1 = await client.post("/pairing/refresh", json={"refresh_token": old_refresh_token})
    assert resp1.status == 200

    # Rejeu du même refresh_token — doit être rejeté (rotation stricte).
    resp2 = await client.post("/pairing/refresh", json={"refresh_token": old_refresh_token})
    assert resp2.status == 401


async def test_refresh_rejects_invalid_token(client):
    resp = await client.post("/pairing/refresh", json={"refresh_token": "not-a-real-token"})
    assert resp.status == 401
    body = await resp.json()
    assert body["error"] == "invalid_or_expired_refresh_token"


async def test_refresh_rejects_missing_field(client):
    resp = await client.post("/pairing/refresh", json={})
    assert resp.status == 400
    body = await resp.json()
    assert body["error"] == "missing_refresh_token"


# ─────────────────────────── Persistance disque ───────────────────────────


async def test_sessions_survive_process_restart(aiohttp_client, tmp_path):
    """Simule un redémarrage : un second PairingManager pointant sur le même
    fichier doit retrouver la session créée par le premier."""
    sessions_file = tmp_path / "sessions.json"

    app1 = server.create_app(admin_token=ADMIN_TOKEN, sessions_path=sessions_file)
    client1 = await aiohttp_client(app1)

    resp = await client1.post("/pairing/create", headers={"Authorization": f"Bearer {ADMIN_TOKEN}"})
    code = (await resp.json())["code"]
    resp = await client1.post(
        "/pairing/register", json={"code": code, "device_hash": make_device_hash("persist1")}
    )
    token = (await resp.json())["session_token"]

    assert sessions_file.exists()

    # "Redémarrage" : nouvelle app, nouveau PairingManager, même fichier.
    app2 = server.create_app(admin_token=ADMIN_TOKEN, sessions_path=sessions_file)
    client2 = await aiohttp_client(app2)

    resp = await client2.get("/phone/outbound", headers={"Authorization": f"Bearer {token}"})
    assert resp.status == 200


async def test_sessions_file_has_restricted_permissions(aiohttp_client, tmp_path):
    sessions_file = tmp_path / "sessions.json"
    app = server.create_app(admin_token=ADMIN_TOKEN, sessions_path=sessions_file)
    client = await aiohttp_client(app)

    resp = await client.post("/pairing/create", headers={"Authorization": f"Bearer {ADMIN_TOKEN}"})
    code = (await resp.json())["code"]
    await client.post("/pairing/register", json={"code": code, "device_hash": make_device_hash("perm1")})

    assert sessions_file.exists()
    if os.name != "nt":  # os.chmod sur les fichiers est un no-op partiel sous Windows
        mode = sessions_file.stat().st_mode & 0o777
        assert mode == 0o600


def test_pairing_manager_with_none_path_never_touches_disk(tmp_path, monkeypatch):
    """sessions_path=None (défaut de create_app()) : aucune écriture disque,
    même si le CWD est redirigé vers un dossier vide surveillé."""
    monkeypatch.chdir(tmp_path)
    manager = PairingManager(sessions_path=None)

    code = manager.create_pairing_code()
    result = manager.redeem(code, make_device_hash("nopersist"))
    assert result is not None
    manager.refresh(result.refresh_token)

    assert list(tmp_path.iterdir()) == []


def test_expired_sessions_are_skipped_on_load(tmp_path):
    """Une session expirée présente dans le fichier ne doit pas être rechargée."""
    sessions_file = tmp_path / "sessions.json"
    stale_session = {
        "token": "stale-token",
        "device_hash": make_device_hash("stale"),
        "created_at": 0.0,
        "last_seen_at": 0.0,  # epoch 1970 — largement expiré
        "refresh_token_hash": None,
        "refresh_expires_at": None,
    }
    sessions_file.write_text(json.dumps({"sessions": [stale_session]}), encoding="utf-8")

    manager = PairingManager(sessions_path=sessions_file)
    assert manager.touch("stale-token") is None


# ─────────────────────────── Envelope (unitaire, pas de réseau) ───────────────────────────


def test_envelope_round_trip():
    env = Envelope(channel="chat", type="message", payload={"text": "hi"})
    data = env.to_dict()
    parsed = Envelope.from_dict(data)
    assert parsed.channel == "chat"
    assert parsed.type == "message"
    assert parsed.payload == {"text": "hi"}
    assert parsed.version == 1


def test_envelope_missing_version_raises():
    with pytest.raises(Exception):
        Envelope.from_dict({"channel": "chat", "type": "message", "payload": {}})


def test_envelope_invalid_channel_raises():
    with pytest.raises(Exception):
        Envelope.from_dict({"version": 1, "channel": "bogus", "type": "message", "payload": {}})


# ─────────────────────────── Bridge commands (capabilities) ───────────────────────────


async def test_bridge_command_requires_auth(client):
    resp = await client.post("/bridge/command", json={"capability": "get_battery"})
    assert resp.status == 401


async def test_bridge_command_missing_capability(paired_client):
    client, token, _ = paired_client
    resp = await client.post(
        "/bridge/command",
        json={"params": {}},
        headers={"Authorization": f"Bearer {token}"},
    )
    assert resp.status == 400


async def test_bridge_command_device_not_connected(paired_client):
    """Session valide mais aucun WS actif pour ce device — pas de tunnel vers le téléphone."""
    client, token, _ = paired_client
    resp = await client.post(
        "/bridge/command",
        json={"capability": "get_battery", "params": {}},
        headers={"Authorization": f"Bearer {token}"},
    )
    assert resp.status == 503


async def test_bridge_command_round_trip(paired_client):
    """Le device (WS) reçoit la commande, répond via bridge/command_result — /bridge/command la retourne."""
    client, token, _ = paired_client
    ws = await connect_and_auth(client, token)

    import asyncio

    async def respond_once():
        msg = await ws.receive_json()
        assert msg["channel"] == "bridge"
        assert msg["type"] == "command"
        assert msg["payload"]["capability"] == "get_battery"
        command_id = msg["payload"]["command_id"]
        await ws.send_json({
            "version": 1, "channel": "bridge", "type": "command_result", "id": "r1",
            "payload": {"command_id": command_id, "result": {"level": 87, "charging": False}},
        })

    responder = asyncio.create_task(respond_once())
    resp = await client.post(
        "/bridge/command",
        json={"capability": "get_battery", "params": {}},
        headers={"Authorization": f"Bearer {token}"},
    )
    await responder

    assert resp.status == 200
    body = await resp.json()
    assert body == {"level": 87, "charging": False}
    await ws.close()


async def test_bridge_command_timeout_when_device_silent(aiohttp_client):
    """Le device est connecté mais ne répond jamais — timeout plutôt qu'un blocage indéfini."""
    import bridge_commands

    app = server.create_app(admin_token=ADMIN_TOKEN)
    app[server.KEY_BRIDGE_COMMANDS] = bridge_commands.BridgeCommandRegistry(timeout_seconds=0.2)
    client = await aiohttp_client(app)

    resp = await client.post("/pairing/create", headers={"Authorization": f"Bearer {ADMIN_TOKEN}"})
    code = (await resp.json())["code"]
    device_hash = make_device_hash("device-timeout")
    resp = await client.post("/pairing/register", json={"code": code, "device_hash": device_hash})
    token = (await resp.json())["session_token"]

    ws = await connect_and_auth(client, token)

    resp = await client.post(
        "/bridge/command",
        json={"capability": "get_battery", "params": {}},
        headers={"Authorization": f"Bearer {token}"},
    )
    assert resp.status == 504
    await ws.close()
