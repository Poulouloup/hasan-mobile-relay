"""Rate limiter anti-bruteforce par IP, fenêtre glissante en mémoire.

Volontairement simple (pas de Redis) : un seul process relay, un seul VPS.
"""

from __future__ import annotations

import time
from collections import defaultdict, deque


class RateLimiter:
    def __init__(self, max_attempts: int, window_seconds: float):
        self._max_attempts = max_attempts
        self._window_seconds = window_seconds
        self._attempts: dict[str, deque[float]] = defaultdict(deque)

    def allow(self, key: str) -> bool:
        """Enregistre une tentative pour `key` et dit si elle est autorisée."""
        now = time.monotonic()
        attempts = self._attempts[key]

        while attempts and now - attempts[0] > self._window_seconds:
            attempts.popleft()

        if len(attempts) >= self._max_attempts:
            return False

        attempts.append(now)
        return True
