"""Streaming du chat texte vers Hermes, relayé sur le canal `chat` du WebSocket.

Contrairement au canal `bridge` (un command_id -> un résultat unique, voir
bridge_commands.py), un chat est un flux de N enveloppes (thinking/token/done)
pour un même tour de conversation. La clé de corrélation n'est donc pas un
identifiant par message mais le `session_id` de la conversation Hermes
elle-même : un seul tour peut être actif à la fois par session (déjà garanti
côté Android par l'annulation du job précédent avant tout nouvel envoi), ce
qui permet d'indexer simplement par session_id plutôt que de construire une
notion de request_id parallèle qui n'existe nulle part ailleurs dans le
protocole.

Erreurs : ce module ne classifie PAS les erreurs Hermes en catégories (pas de
mapping "400 -> contexte invalide", "401 -> auth" etc côté Python). Il
transporte l'info brute disponible (code HTTP, message, et un `reason` texte
uniquement pour les cas sans ambiguïté : cancelled/connection_refused/
timeout/ws_gone). Le classement fin vers l'enum ErrorType existe déjà côté
Android (ChatStreamHandler.kt, classifyChatError) pour ce même flux — le
dupliquer ici créerait deux classifications indépendantes qui pourraient
diverger.
"""

from __future__ import annotations

import asyncio
import json
import logging
from typing import Any, Awaitable, Callable

import aiohttp

log = logging.getLogger("relay.chat")

# Inactivité totale (aucun octet reçu de Hermes) avant abandon — volontairement
# généreux, pas un timeout sur la durée du tour entier : un tool call (recherche
# web, etc.) peut légitimement prendre plusieurs minutes sans qu'aucun octet
# n'arrive entre-temps si Hermes ne heartbeat pas pendant l'exécution de l'outil.
HERMES_WATCHDOG_TIMEOUT_SECONDS = 300.0

# Établissement de la connexion HTTP initiale vers Hermes (avant même le premier
# octet de réponse) — distinct du watchdog ci-dessus qui ne protège que la phase
# de lecture SSE une fois la connexion acceptée. Sans ce timeout, un Hermes qui
# redémarre ou ne répond pas au niveau TCP/HTTP pouvait laisser un tour bloqué
# silencieusement (côté relay) jusqu'à 300s — observé en pratique : un aller-retour
# a pris 111s suite à un redémarrage de l'API Hermes en amont pendant lequel ni
# chat/health (timeout 8s, CHAT_RPC_TIMEOUT_SECONDS côté server.py) ni chat/send
# n'échouaient proprement. 20s laisse une marge large pour un simple redémarrage
# tout en donnant une erreur explicite bien avant les 300s du watchdog SSE.
HERMES_CONNECT_TIMEOUT_SECONDS = 20.0

EnvelopeSender = Callable[[str, str, dict[str, Any]], Awaitable[None]]
"""(session_id, type, payload_extra) -> envoie une enveloppe chat/<type> au device."""


class ChatSessionRegistry:
    """Une task Hermes active au plus par session_id, indexée aussi par device
    pour permettre l'annulation groupée à la déconnexion WS."""

    def __init__(self, watchdog_timeout_seconds: float = HERMES_WATCHDOG_TIMEOUT_SECONDS):
        self._watchdog_timeout_seconds = watchdog_timeout_seconds
        self._active: dict[str, asyncio.Task] = {}
        self._by_device: dict[str, set[str]] = {}

    async def start(
        self,
        *,
        device_hash: str,
        session_id: str,
        text: str,
        hermes_base_url: str,
        hermes_token: str,
        send_envelope: EnvelopeSender,
    ) -> None:
        """Démarre un nouveau tour pour `session_id`.

        Si une task est déjà active pour cette session (ne devrait pas arriver
        avec un client bien formé — un seul stream actif à la fois côté
        MainViewModel), l'annule d'abord plutôt que d'empiler.
        """
        existing = self._active.get(session_id)
        if existing is not None and not existing.done():
            log.warning("chat/send reçu pour session_id=%s déjà active — annulation de l'ancien tour", session_id)
            existing.cancel()
            try:
                await existing
            except asyncio.CancelledError:
                pass

        task = asyncio.ensure_future(
            self._run_stream(
                device_hash=device_hash,
                session_id=session_id,
                text=text,
                hermes_base_url=hermes_base_url,
                hermes_token=hermes_token,
                send_envelope=send_envelope,
            )
        )
        self._active[session_id] = task
        self._by_device.setdefault(device_hash, set()).add(session_id)

    def cancel(self, session_id: str) -> bool:
        """Best-effort : annule la task si présente. Un cancel sur une session
        inconnue ou déjà terminée n'est pas une erreur (le socket "peut être
        parti", comme côté hermes-relay — juste un log)."""
        task = self._active.get(session_id)
        if task is None or task.done():
            log.info("chat/cancel reçu pour session_id=%s introuvable/déjà terminée — ignoré", session_id)
            return False
        task.cancel()
        return True

    def cancel_all_for_device(self, device_hash: str) -> None:
        """Appelé à la déconnexion WS — évite qu'une task continue de consommer
        une connexion HTTP amont vers Hermes pour un client qui ne l'écoute plus."""
        session_ids = self._by_device.pop(device_hash, set())
        for session_id in session_ids:
            task = self._active.get(session_id)
            if task is not None and not task.done():
                task.cancel()

    async def _run_stream(
        self,
        *,
        device_hash: str,
        session_id: str,
        text: str,
        hermes_base_url: str,
        hermes_token: str,
        send_envelope: EnvelopeSender,
    ) -> None:
        try:
            await send_envelope(session_id, "connecting", {})

            body = {"input": text, "stream": True, "conversation": session_id}
            headers = {"Content-Type": "application/json"}
            if hermes_token:
                headers["Authorization"] = f"Bearer {hermes_token}"

            # sock_connect + le total par défaut (None = illimité) laisserait la phase
            # d'établissement de connexion/attente des headers non bornée ; ici on ne
            # borne QUE cette phase initiale (connect), pas la lecture SSE ensuite
            # (gérée séparément par _pump_sse via HERMES_WATCHDOG_TIMEOUT_SECONDS).
            connect_timeout = aiohttp.ClientTimeout(sock_connect=HERMES_CONNECT_TIMEOUT_SECONDS)
            try:
                async with aiohttp.ClientSession(timeout=connect_timeout) as client:
                    async with client.post(
                        f"{hermes_base_url}/v1/responses", json=body, headers=headers
                    ) as response:
                        if response.status != 200:
                            error_body = await response.text()
                            await send_envelope(
                                session_id,
                                "error",
                                {"http_status": response.status, "message": error_body[:500]},
                            )
                            return

                        await send_envelope(session_id, "connected", {})
                        await self._pump_sse(session_id, response, send_envelope)
            except asyncio.CancelledError:
                await send_envelope(session_id, "error", {"reason": "cancelled", "message": "Tour annulé"})
                raise
            except (aiohttp.ClientConnectorError, ConnectionRefusedError) as exc:
                await send_envelope(
                    session_id, "error", {"reason": "connection_refused", "message": str(exc)}
                )
            except asyncio.TimeoutError:
                await send_envelope(
                    session_id,
                    "error",
                    {"reason": "timeout", "message": f"Hermes injoignable après {HERMES_CONNECT_TIMEOUT_SECONDS:.0f}s"},
                )
            except aiohttp.ClientError as exc:
                await send_envelope(session_id, "error", {"message": str(exc)})
        finally:
            self._active.pop(session_id, None)
            for sessions in self._by_device.values():
                sessions.discard(session_id)

    async def _pump_sse(
        self,
        session_id: str,
        response: aiohttp.ClientResponse,
        send_envelope: EnvelopeSender,
    ) -> None:
        """Parse le flux SSE de Hermes et réémet en enveloppes chat/* structurées.

        Interprétation de flux (pas de la classification d'erreur) — dupliquée
        côté Python en miroir de HermesApiClient.kt (lignes ~141-244), qui est
        la seule autre implémentation de ce parsing dans le projet.
        """
        pending_event: str | None = None
        buffer = ""

        while True:
            try:
                chunk = await asyncio.wait_for(
                    response.content.readline(), timeout=self._watchdog_timeout_seconds
                )
            except asyncio.TimeoutError:
                await send_envelope(session_id, "error", {"reason": "timeout", "message": "Hermes silencieux"})
                return

            if not chunk:
                break

            line = chunk.decode("utf-8", errors="replace").rstrip("\r\n")

            if line.startswith("event: "):
                pending_event = line.removeprefix("event: ").strip()
                continue

            if not line.startswith("data: "):
                if line == "":
                    pending_event = None
                continue

            data = line.removeprefix("data: ").strip()

            if data == "[DONE]":
                await send_envelope(session_id, "done", {"response_id": None, "input_tokens": 0, "output_tokens": 0})
                return

            if pending_event == "response.completed":
                try:
                    obj = json.loads(data)
                    resp_obj = obj.get("response", obj)
                    response_id = resp_obj.get("id")
                    usage = resp_obj.get("usage") or {}
                    await send_envelope(
                        session_id,
                        "done",
                        {
                            "response_id": response_id,
                            "input_tokens": usage.get("input_tokens", 0),
                            "output_tokens": usage.get("output_tokens", 0),
                        },
                    )
                except (json.JSONDecodeError, AttributeError):
                    pass
                return

            if pending_event == "response.output_item.added":
                try:
                    obj = json.loads(data)
                    item = obj.get("item") or {}
                    if item.get("type") == "function_call":
                        name = item.get("name")
                        if name:
                            await send_envelope(session_id, "thinking", {"message": name})
                except json.JSONDecodeError:
                    pass
                pending_event = None
                continue

            if pending_event == "ping":
                pending_event = None
                continue

            if pending_event == "response.output_text.delta":
                try:
                    delta = json.loads(data).get("delta")
                    if delta:
                        await send_envelope(session_id, "token", {"text": delta})
                except json.JSONDecodeError:
                    pass
                pending_event = None
                continue

            if pending_event is None:
                try:
                    obj = json.loads(data)
                    delta = obj.get("delta")
                    if not delta:
                        choices = obj.get("choices")
                        if choices:
                            delta = choices[0].get("delta", {}).get("content")
                    if delta:
                        await send_envelope(session_id, "token", {"text": delta})
                except json.JSONDecodeError:
                    pass
                continue

            # Event SSE non géré — ignoré silencieusement (diagnostic via logs si besoin).
            pending_event = None
