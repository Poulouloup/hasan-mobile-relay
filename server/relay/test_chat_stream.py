"""Tests du canal `chat` (streaming du chat texte via WS relay, voir chat_stream.py).

Hermes est simulé par un TestServer aiohttp local exposant /v1/responses en
SSE — pas de mock de bibliothèque tierce, cohérent avec le reste de la suite
qui teste au niveau HTTP/WS réel plutôt qu'en mockant aiohttp lui-même.

Lancer : pytest test_chat_stream.py -v
"""

from __future__ import annotations

import asyncio
import hashlib

import pytest
from aiohttp import web
from aiohttp.test_utils import TestServer

import server
from test_server import ADMIN_TOKEN, connect_and_auth, make_device_hash

pytestmark = pytest.mark.asyncio


def sse_lines(*lines: str) -> bytes:
    return ("\n".join(lines) + "\n\n").encode("utf-8")


class FakeHermes:
    """Sert /v1/responses (SSE) et, optionnellement, /health pour les tests
    chat/health."""

    def __init__(
        self,
        body: bytes | None = None,
        status: int = 200,
        delay_seconds: float = 0.0,
        health_status: int = 200,
    ):
        self.body = body or b""
        self.status = status
        self.delay_seconds = delay_seconds
        self.health_status = health_status
        self.received_requests: list[dict] = []
        self._server: TestServer | None = None

    async def _handle(self, request: web.Request) -> web.StreamResponse:
        self.received_requests.append(await request.json())
        if self.delay_seconds:
            await asyncio.sleep(self.delay_seconds)
        if self.status != 200:
            return web.Response(status=self.status, text="upstream error")
        response = web.StreamResponse(status=200, headers={"Content-Type": "text/event-stream"})
        await response.prepare(request)
        await response.write(self.body)
        await response.write_eof()
        return response

    async def _handle_health(self, request: web.Request) -> web.Response:
        return web.Response(status=self.health_status)

    async def __aenter__(self) -> "FakeHermes":
        app = web.Application()
        app.router.add_post("/v1/responses", self._handle)
        app.router.add_get("/health", self._handle_health)
        self._server = TestServer(app)
        await self._server.start_server()
        return self

    async def __aexit__(self, *exc):
        await self._server.close()

    @property
    def base_url(self) -> str:
        return str(self._server.make_url("")).rstrip("/")


async def _paired(aiohttp_client, app):
    client = await aiohttp_client(app)
    resp = await client.post("/pairing/create", headers={"Authorization": f"Bearer {ADMIN_TOKEN}"})
    code = (await resp.json())["code"]
    device_hash = make_device_hash(f"chat-{code}")
    resp = await client.post("/pairing/register", json={"code": code, "device_hash": device_hash})
    token = (await resp.json())["session_token"]
    return client, token, device_hash


async def test_chat_send_missing_fields_returns_error(aiohttp_client):
    app = server.create_app(admin_token=ADMIN_TOKEN)
    client, token, _ = await _paired(aiohttp_client, app)
    ws = await connect_and_auth(client, token)

    await ws.send_json({"version": 1, "channel": "chat", "type": "send", "payload": {"session_id": "s1"}})
    msg = await ws.receive_json()
    assert msg["channel"] == "chat"
    assert msg["type"] == "error"
    await ws.close()


async def test_chat_send_happy_path_streams_tokens(aiohttp_client):
    body = sse_lines(
        'event: response.output_text.delta',
        'data: {"delta": "Bon"}',
        '',
        'event: response.output_text.delta',
        'data: {"delta": "jour"}',
        '',
        'event: response.completed',
        'data: {"response": {"id": "resp_1", "usage": {"input_tokens": 5, "output_tokens": 2}}}',
    )
    async with FakeHermes(body=body) as hermes:
        app = server.create_app(admin_token=ADMIN_TOKEN, hermes_api_base_url=hermes.base_url)
        client, token, _ = await _paired(aiohttp_client, app)
        ws = await connect_and_auth(client, token)

        await ws.send_json(
            {"version": 1, "channel": "chat", "type": "send", "payload": {"session_id": "s1", "text": "salut"}}
        )

        connecting = await ws.receive_json()
        assert connecting["type"] == "connecting"
        assert connecting["payload"]["session_id"] == "s1"

        connected = await ws.receive_json()
        assert connected["type"] == "connected"

        tok1 = await ws.receive_json()
        assert tok1["type"] == "token"
        assert tok1["payload"]["text"] == "Bon"

        tok2 = await ws.receive_json()
        assert tok2["payload"]["text"] == "jour"

        done = await ws.receive_json()
        assert done["type"] == "done"
        assert done["payload"]["session_id"] == "s1"
        assert done["payload"]["response_id"] == "resp_1"
        assert done["payload"]["input_tokens"] == 5
        assert done["payload"]["output_tokens"] == 2

        assert hermes.received_requests[0] == {"input": "salut", "stream": True, "conversation": "s1"}
        await ws.close()


async def test_chat_send_propagates_thinking(aiohttp_client):
    body = sse_lines(
        'event: response.output_item.added',
        'data: {"item": {"type": "function_call", "name": "web_search"}}',
    )
    async with FakeHermes(body=body) as hermes:
        app = server.create_app(admin_token=ADMIN_TOKEN, hermes_api_base_url=hermes.base_url)
        client, token, _ = await _paired(aiohttp_client, app)
        ws = await connect_and_auth(client, token)

        await ws.send_json(
            {"version": 1, "channel": "chat", "type": "send", "payload": {"session_id": "s1", "text": "cherche"}}
        )
        await ws.receive_json()  # connecting
        await ws.receive_json()  # connected

        thinking = await ws.receive_json()
        assert thinking["type"] == "thinking"
        assert thinking["payload"]["message"] == "web_search"
        await ws.close()


async def test_chat_cancel_stops_active_stream(aiohttp_client):
    async with FakeHermes(body=sse_lines('data: [DONE]'), delay_seconds=2.0) as hermes:
        app = server.create_app(admin_token=ADMIN_TOKEN, hermes_api_base_url=hermes.base_url)
        client, token, _ = await _paired(aiohttp_client, app)
        ws = await connect_and_auth(client, token)

        await ws.send_json(
            {"version": 1, "channel": "chat", "type": "send", "payload": {"session_id": "s1", "text": "long"}}
        )
        connecting = await ws.receive_json()
        assert connecting["type"] == "connecting"

        await ws.send_json({"version": 1, "channel": "chat", "type": "cancel", "payload": {"session_id": "s1"}})

        msg = await ws.receive_json()
        assert msg["type"] == "error"
        assert msg["payload"]["reason"] == "cancelled"
        assert msg["payload"]["session_id"] == "s1"
        await ws.close()


async def test_chat_cancel_unknown_session_is_noop(aiohttp_client):
    app = server.create_app(admin_token=ADMIN_TOKEN)
    client, token, _ = await _paired(aiohttp_client, app)
    ws = await connect_and_auth(client, token)

    await ws.send_json({"version": 1, "channel": "chat", "type": "cancel", "payload": {"session_id": "ghost"}})
    # Aucune réponse attendue, aucun crash — on vérifie que le WS reste utilisable.
    await ws.send_json({"version": 1, "channel": "system", "type": "ping", "payload": {}})
    msg = await ws.receive_json()
    assert msg["type"] == "pong"
    await ws.close()


async def test_chat_send_while_session_already_active_cancels_previous(aiohttp_client):
    async with FakeHermes(body=sse_lines('data: [DONE]'), delay_seconds=1.0) as hermes:
        app = server.create_app(admin_token=ADMIN_TOKEN, hermes_api_base_url=hermes.base_url)
        client, token, _ = await _paired(aiohttp_client, app)
        ws = await connect_and_auth(client, token)

        await ws.send_json(
            {"version": 1, "channel": "chat", "type": "send", "payload": {"session_id": "s1", "text": "premier"}}
        )
        await ws.receive_json()  # connecting du premier tour

        await ws.send_json(
            {"version": 1, "channel": "chat", "type": "send", "payload": {"session_id": "s1", "text": "second"}}
        )

        # Le premier tour doit être annulé (erreur cancelled) avant que le
        # second démarre son propre "connecting".
        first_cancel = await ws.receive_json()
        assert first_cancel["type"] == "error"
        assert first_cancel["payload"]["reason"] == "cancelled"

        second_connecting = await ws.receive_json()
        assert second_connecting["type"] == "connecting"
        await ws.close()


async def test_chat_error_transports_raw_connection_refused(aiohttp_client):
    # Aucun serveur Hermes réel démarré sur ce port -> connexion refusée.
    app = server.create_app(admin_token=ADMIN_TOKEN, hermes_api_base_url="http://127.0.0.1:1")
    client, token, _ = await _paired(aiohttp_client, app)
    ws = await connect_and_auth(client, token)

    await ws.send_json(
        {"version": 1, "channel": "chat", "type": "send", "payload": {"session_id": "s1", "text": "salut"}}
    )
    await ws.receive_json()  # connecting

    msg = await ws.receive_json()
    assert msg["type"] == "error"
    assert msg["payload"]["reason"] == "connection_refused"
    assert "http_status" not in msg["payload"] or msg["payload"].get("http_status") is None
    await ws.close()


@pytest.mark.parametrize("status", [401, 500])
async def test_chat_error_transports_raw_http_status(aiohttp_client, status):
    async with FakeHermes(status=status) as hermes:
        app = server.create_app(admin_token=ADMIN_TOKEN, hermes_api_base_url=hermes.base_url)
        client, token, _ = await _paired(aiohttp_client, app)
        ws = await connect_and_auth(client, token)

        await ws.send_json(
            {"version": 1, "channel": "chat", "type": "send", "payload": {"session_id": "s1", "text": "salut"}}
        )
        await ws.receive_json()  # connecting

        msg = await ws.receive_json()
        assert msg["type"] == "error"
        assert msg["payload"]["http_status"] == status
        # Pas de classification côté serveur : ni error_type ni reason déduits du code HTTP.
        assert "reason" not in msg["payload"]
        await ws.close()


async def test_chat_stream_survives_ws_reconnect_resolves_fresh_socket(aiohttp_client):
    async with FakeHermes(body=sse_lines('data: [DONE]'), delay_seconds=0.3) as hermes:
        app = server.create_app(admin_token=ADMIN_TOKEN, hermes_api_base_url=hermes.base_url)
        client, token, device_hash = await _paired(aiohttp_client, app)
        ws1 = await connect_and_auth(client, token)

        await ws1.send_json(
            {"version": 1, "channel": "chat", "type": "send", "payload": {"session_id": "s1", "text": "salut"}}
        )
        connecting = await ws1.receive_json()
        assert connecting["type"] == "connecting"

        # ws1 est supersedé par une nouvelle connexion du même device pendant
        # que la task Hermes tourne encore (délai artificiel de FakeHermes) —
        # le serveur ne doit pas planter et doit re-résoudre le socket actif
        # à chaque émission (corrige le piège identifié dans l'ancien
        # handle_chat_stream, qui capturait `ws` une seule fois avant la
        # boucle de forward).
        ws2 = await connect_and_auth(client, token)
        closed = await ws1.receive()
        assert closed.data == 4000

        # Les événements suivants du même tour (connected puis done) doivent
        # tous arriver sur ws2 — plus aucun n'est écrit sur ws1, désormais fermé.
        connected = await ws2.receive_json()
        assert connected["type"] == "connected"

        done = await ws2.receive_json()
        assert done["type"] == "done"
        assert done["payload"]["session_id"] == "s1"
        await ws2.close()


async def test_chat_streams_cancelled_on_ws_disconnect(aiohttp_client):
    hermes_call_seen = asyncio.Event()

    async def slow_handler(request: web.Request) -> web.StreamResponse:
        await request.json()
        hermes_call_seen.set()
        response = web.StreamResponse(status=200, headers={"Content-Type": "text/event-stream"})
        await response.prepare(request)
        try:
            await asyncio.sleep(5.0)
        except asyncio.CancelledError:
            raise
        await response.write_eof()
        return response

    app_hermes = web.Application()
    app_hermes.router.add_post("/v1/responses", slow_handler)
    hermes_server = TestServer(app_hermes)
    await hermes_server.start_server()
    try:
        app = server.create_app(admin_token=ADMIN_TOKEN, hermes_api_base_url=str(hermes_server.make_url("")).rstrip("/"))
        client, token, device_hash = await _paired(aiohttp_client, app)
        ws = await connect_and_auth(client, token)

        await ws.send_json(
            {"version": 1, "channel": "chat", "type": "send", "payload": {"session_id": "s1", "text": "salut"}}
        )
        await ws.receive_json()  # connecting
        await asyncio.wait_for(hermes_call_seen.wait(), timeout=2.0)

        await ws.close()
        await asyncio.sleep(0.1)  # laisse le serveur traiter la déconnexion (bloc finally)

        chat_sessions = app[server.KEY_CHAT_SESSIONS]
        assert "s1" not in chat_sessions._active
    finally:
        await hermes_server.close()


async def test_chat_watchdog_timeout_on_hermes_silence(aiohttp_client):
    import chat_stream

    async def silent_handler(request: web.Request) -> web.StreamResponse:
        await request.json()
        response = web.StreamResponse(status=200, headers={"Content-Type": "text/event-stream"})
        await response.prepare(request)
        await asyncio.sleep(1.0)  # ne renvoie jamais rien avant le watchdog réduit
        await response.write_eof()
        return response

    app_hermes = web.Application()
    app_hermes.router.add_post("/v1/responses", silent_handler)
    hermes_server = TestServer(app_hermes)
    await hermes_server.start_server()
    try:
        app = server.create_app(admin_token=ADMIN_TOKEN, hermes_api_base_url=str(hermes_server.make_url("")).rstrip("/"))
        app[server.KEY_CHAT_SESSIONS] = chat_stream.ChatSessionRegistry(watchdog_timeout_seconds=0.2)
        client, token, _ = await _paired(aiohttp_client, app)
        ws = await connect_and_auth(client, token)

        await ws.send_json(
            {"version": 1, "channel": "chat", "type": "send", "payload": {"session_id": "s1", "text": "salut"}}
        )
        await ws.receive_json()  # connecting
        await ws.receive_json()  # connected (le handler démarre le StreamResponse avant de dormir)

        msg = await ws.receive_json()
        assert msg["type"] == "error"
        assert msg["payload"]["reason"] == "timeout"
        await ws.close()
    finally:
        await hermes_server.close()


# ─────────────────────────── chat/health ───────────────────────────


async def test_chat_health_ok_when_hermes_healthy(aiohttp_client):
    async with FakeHermes(health_status=200) as hermes:
        app = server.create_app(admin_token=ADMIN_TOKEN, hermes_api_base_url=hermes.base_url)
        client, token, _ = await _paired(aiohttp_client, app)
        ws = await connect_and_auth(client, token)

        req_id = "health-req-1"
        await ws.send_json({"version": 1, "channel": "chat", "type": "health", "id": req_id, "payload": {}})

        msg = await ws.receive_json()
        assert msg["type"] == "health_result"
        assert msg["id"] == req_id
        assert msg["payload"]["ok"] is True
        await ws.close()


async def test_chat_health_reports_http_status_on_hermes_error(aiohttp_client):
    async with FakeHermes(health_status=503) as hermes:
        app = server.create_app(admin_token=ADMIN_TOKEN, hermes_api_base_url=hermes.base_url)
        client, token, _ = await _paired(aiohttp_client, app)
        ws = await connect_and_auth(client, token)

        await ws.send_json({"version": 1, "channel": "chat", "type": "health", "payload": {}})

        msg = await ws.receive_json()
        assert msg["type"] == "health_result"
        assert msg["payload"]["ok"] is False
        assert msg["payload"]["http_status"] == 503
        await ws.close()


async def test_chat_health_reports_network_error_when_hermes_unreachable(aiohttp_client):
    app = server.create_app(admin_token=ADMIN_TOKEN, hermes_api_base_url="http://127.0.0.1:1")
    client, token, _ = await _paired(aiohttp_client, app)
    ws = await connect_and_auth(client, token)

    await ws.send_json({"version": 1, "channel": "chat", "type": "health", "payload": {}})

    msg = await ws.receive_json()
    assert msg["type"] == "health_result"
    assert msg["payload"]["ok"] is False
    assert "http_status" not in msg["payload"]
    assert "message" in msg["payload"]
    await ws.close()


async def test_chat_health_does_not_interfere_with_active_chat_send(aiohttp_client):
    body = sse_lines('data: [DONE]')
    async with FakeHermes(body=body, health_status=200) as hermes:
        app = server.create_app(admin_token=ADMIN_TOKEN, hermes_api_base_url=hermes.base_url)
        client, token, _ = await _paired(aiohttp_client, app)
        ws = await connect_and_auth(client, token)

        await ws.send_json(
            {"version": 1, "channel": "chat", "type": "send", "payload": {"session_id": "s1", "text": "salut"}}
        )
        connecting = await ws.receive_json()
        assert connecting["type"] == "connecting"

        health_req_id = "health-parallel-1"
        await ws.send_json({"version": 1, "channel": "chat", "type": "health", "id": health_req_id, "payload": {}})

        # Les deux réponses doivent arriver, chacune identifiable sans ambiguïté :
        # health par son id, le tour de chat par sa progression normale jusqu'à
        # done — l'ordre d'arrivée entre les deux tâches concurrentes n'est pas
        # garanti (ni à garantir), seule l'absence de cross-talk compte.
        seen_types = set()
        health_result = None
        done = None
        for _ in range(3):
            msg = await ws.receive_json()
            seen_types.add(msg["type"])
            if msg["type"] == "health_result":
                health_result = msg
            elif msg["type"] == "done":
                done = msg

        assert seen_types == {"connected", "health_result", "done"}
        assert health_result["id"] == health_req_id
        assert done["payload"]["session_id"] == "s1"
        await ws.close()
