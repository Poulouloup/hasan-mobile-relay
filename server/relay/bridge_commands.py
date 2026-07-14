"""Corrélation requête/réponse pour les commandes "bridge" (capabilities Android).

Le canal `bridge` du WebSocket transporte des appels de tool function-calling
vers le téléphone (voir CapabilitySchema.kt / CapabilityExecutor.kt côté
Android) : le plugin Hermes envoie une commande, attend un résultat borné dans
le temps. Le WS étant asynchrone et multiplexé, on ne peut pas simplement
"attendre la prochaine réponse" — un `command_id` correlate chaque envoi à sa
réponse, via un `asyncio.Future` par commande en vol.

Bornes : une commande en attente au-delà de COMMAND_TIMEOUT_SECONDS échoue
(timeout), le Future est retiré du registre dans tous les cas (succès, erreur,
timeout) pour ne jamais accumuler de futures orphelins.
"""

from __future__ import annotations

import asyncio
import uuid
from typing import Any

COMMAND_TIMEOUT_SECONDS = 30.0


class CommandTimeoutError(Exception):
    pass


class BridgeCommandRegistry:
    def __init__(self, timeout_seconds: float = COMMAND_TIMEOUT_SECONDS):
        self._timeout_seconds = timeout_seconds
        self._pending: dict[str, asyncio.Future] = {}

    async def send_and_wait(
        self,
        ws,
        *,
        command_id: str | None,
        capability: str,
        params: dict[str, Any],
        envelope_factory,
    ) -> dict[str, Any]:
        """Envoie l'enveloppe de commande sur `ws`, attend le résultat corrélé.

        `envelope_factory(command_id, capability, params) -> dict` construit
        l'enveloppe à envoyer — laissé à l'appelant pour ne pas coupler ce
        module au format exact d'Envelope (import circulaire évité, et permet
        de tester ce module indépendamment du format d'enveloppe).
        """
        cmd_id = command_id or str(uuid.uuid4())
        loop = asyncio.get_event_loop()
        future: asyncio.Future = loop.create_future()
        self._pending[cmd_id] = future

        try:
            await ws.send_json(envelope_factory(cmd_id, capability, params))
            try:
                result = await asyncio.wait_for(future, timeout=self._timeout_seconds)
            except asyncio.TimeoutError as exc:
                raise CommandTimeoutError(
                    f"Timeout en attendant le résultat de la commande {cmd_id}"
                ) from exc
            return result
        finally:
            self._pending.pop(cmd_id, None)

    def resolve(self, command_id: str, result: dict[str, Any]) -> bool:
        """Appelé quand une réponse `bridge`/tool_result arrive du téléphone.

        Retourne False si aucune commande de cet id n'est en attente (déjà
        résolue, timeout déjà écoulé, ou id inconnu) — l'appelant journalise
        ce cas plutôt que d'échouer bruyamment, un résultat tardif après
        timeout n'est pas une erreur serveur.
        """
        future = self._pending.get(command_id)
        if future is None or future.done():
            return False
        future.set_result(result)
        return True
