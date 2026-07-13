"""Hooks du plugin hasan_delivery.

Vérifie au démarrage que le relay server (server/relay/server.py) est
joignable, pour surfacer une erreur de config claire plutôt qu'un échec
silencieux au premier envoi de message.
"""

from __future__ import annotations

import logging

import httpx

log = logging.getLogger(__name__)

HEALTH_CHECK_TIMEOUT_SECONDS = 5.0


async def check_relay_health(relay_url: str) -> bool:
    """Sonde GET {relay_url}/health. Retourne False sur toute erreur réseau/HTTP."""
    url = relay_url.rstrip("/") + "/health"
    try:
        async with httpx.AsyncClient(timeout=HEALTH_CHECK_TIMEOUT_SECONDS) as client:
            response = await client.get(url)
            response.raise_for_status()
            return True
    except (httpx.HTTPError, httpx.TimeoutException) as exc:
        log.warning("hasan_delivery: relay server injoignable sur %s (%s)", url, exc)
        return False
