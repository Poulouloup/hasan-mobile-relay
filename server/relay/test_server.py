"""Suite pytest pour le relay server — couvre tout ce qui est automatisable
sans device Android réel (HTTP, WebSocket, pairing, buffer, rate limit).

Lancer : pytest -v
"""

from __future__ import annotations

import hashlib

import pytest
from aiohttp import WSMsgType
from aiohttp.test_utils import TestClient, TestServer

import server
from envelope import Envelope

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


async def test_ws_rejects_invalid_token(client):
    ws = await client.ws_connect("/ws?token=invalid-token")
    msg = await ws.receive()
    assert msg.type == WSMsgType.CLOSE
    assert msg.data == 4401


async def test_ws_rejects_missing_token(client):
    ws = await client.ws_connect("/ws")
    msg = await ws.receive()
    assert msg.type == WSMsgType.CLOSE
    assert msg.data == 4401


async def test_ws_ping_pong(paired_client):
    client, token, _ = paired_client
    ws = await client.ws_connect(f"/ws?token={token}")

    await ws.send_json({"version": 1, "channel": "system", "type": "ping", "id": "t1", "payload": {}})
    msg = await ws.receive_json()
    assert msg["channel"] == "system"
    assert msg["type"] == "pong"
    await ws.close()


async def test_ws_rejects_unsupported_version(paired_client):
    client, token, _ = paired_client
    ws = await client.ws_connect(f"/ws?token={token}")

    await ws.send_json({"version": 99, "channel": "system", "type": "ping", "payload": {}})
    msg = await ws.receive_json()
    assert msg["type"] == "error"
    assert "version" in msg["payload"]["error"]
    await ws.close()


async def test_ws_rejects_missing_version(paired_client):
    client, token, _ = paired_client
    ws = await client.ws_connect(f"/ws?token={token}")

    await ws.send_json({"channel": "system", "type": "ping", "payload": {}})
    msg = await ws.receive_json()
    assert msg["type"] == "error"
    await ws.close()


async def test_ws_rejects_invalid_channel(paired_client):
    client, token, _ = paired_client
    ws = await client.ws_connect(f"/ws?token={token}")

    await ws.send_json({"version": 1, "channel": "not_a_real_channel", "type": "ping", "payload": {}})
    msg = await ws.receive_json()
    assert msg["type"] == "error"
    await ws.close()


async def test_ws_drains_buffered_messages_on_connect(paired_client):
    client, token, _ = paired_client

    await client.post(
        "/phone/message", json={"text": "message en attente"}, headers={"Authorization": f"Bearer {token}"}
    )

    ws = await client.ws_connect(f"/ws?token={token}")
    msg = await ws.receive_json()
    assert msg["channel"] == "proactive"
    assert msg["type"] == "message"
    assert msg["payload"]["text"] == "message en attente"
    await ws.close()


async def test_phone_message_delivers_live_when_ws_connected(paired_client):
    client, token, _ = paired_client
    ws = await client.ws_connect(f"/ws?token={token}")

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
    ws1 = await client.ws_connect(f"/ws?token={token}")
    ws2 = await client.ws_connect(f"/ws?token={token}")

    msg = await ws1.receive()
    assert msg.type == WSMsgType.CLOSE
    assert msg.data == 4000

    assert not ws2.closed
    await ws2.close()


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
