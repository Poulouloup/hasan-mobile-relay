"""Buffer de push borné : conserve les messages destinés à l'app pendant
qu'elle est déconnectée, pour les rejouer à la reconnexion.

Bornes : 50 messages max par device, TTL 24h. Purge paresseuse (à la lecture
et à l'écriture) plutôt qu'un thread de purge dédié — le volume est trop
faible pour justifier davantage.
"""

from __future__ import annotations

import time
from collections import defaultdict, deque
from typing import Any

MAX_MESSAGES_PER_DEVICE = 50
TTL_SECONDS = 24 * 60 * 60


class PushBuffer:
    def __init__(self, max_messages: int = MAX_MESSAGES_PER_DEVICE, ttl_seconds: float = TTL_SECONDS):
        self._max_messages = max_messages
        self._ttl_seconds = ttl_seconds
        self._buffers: dict[str, deque[tuple[float, dict[str, Any]]]] = defaultdict(
            lambda: deque(maxlen=self._max_messages)
        )

    def push(self, device_id: str, envelope: dict[str, Any]) -> None:
        self._buffers[device_id].append((time.monotonic(), envelope))

    def drain(self, device_id: str) -> list[dict[str, Any]]:
        """Retourne les messages non expirés pour ce device et vide son buffer."""
        buf = self._buffers.get(device_id)
        if not buf:
            return []
        now = time.monotonic()
        fresh = [envelope for ts, envelope in buf if now - ts <= self._ttl_seconds]
        buf.clear()
        return fresh

    def pending_count(self, device_id: str) -> int:
        buf = self._buffers.get(device_id)
        return len(buf) if buf else 0
