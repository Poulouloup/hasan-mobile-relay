"""Pairing QR + session tokens.

Flux : le serveur génère un code de pairing éphémère (affiché en QR côté
serveur ou via un canal admin), l'app le scanne et l'envoie à /pairing/register
avec le SHA-256 de son device_hash. Le serveur répond avec un session_token
opaque que l'app doit ensuite présenter (Bearer) sur toutes les requêtes
authentifiées et à l'upgrade WebSocket.

Le TOFU (Trust On First Use) du certificat serveur est vérifié côté client
(app Android, CertPinStore) — ce module ne gère que l'auth applicative.
"""

from __future__ import annotations

import hashlib
import secrets
import string
import time
from dataclasses import dataclass

PAIRING_CODE_ALPHABET = string.ascii_uppercase + string.digits
PAIRING_CODE_LENGTH = 6
PAIRING_CODE_TTL_SECONDS = 10 * 60  # 10 minutes pour scanner le QR
SESSION_TOKEN_TTL_SECONDS = 30 * 24 * 60 * 60  # 30 jours, renouvelé à l'usage


@dataclass
class PendingPairing:
    code: str
    created_at: float

    def expired(self) -> bool:
        return time.monotonic() - self.created_at > PAIRING_CODE_TTL_SECONDS


@dataclass
class Session:
    token: str
    device_hash: str
    created_at: float
    last_seen_at: float

    def expired(self) -> bool:
        return time.monotonic() - self.last_seen_at > SESSION_TOKEN_TTL_SECONDS


class PairingManager:
    def __init__(self) -> None:
        self._pending: dict[str, PendingPairing] = {}
        self._sessions: dict[str, Session] = {}

    def create_pairing_code(self) -> str:
        code = "".join(secrets.choice(PAIRING_CODE_ALPHABET) for _ in range(PAIRING_CODE_LENGTH))
        self._pending[code] = PendingPairing(code=code, created_at=time.monotonic())
        return code

    def redeem(self, code: str, device_hash: str) -> Session | None:
        """Consomme un code de pairing valide et émet une session. None si code invalide/expiré."""
        pending = self._pending.get(code)
        if pending is None or pending.expired():
            self._pending.pop(code, None)
            return None

        del self._pending[code]

        if not _looks_like_sha256(device_hash):
            return None

        token = secrets.token_urlsafe(32)
        now = time.monotonic()
        session = Session(token=token, device_hash=device_hash, created_at=now, last_seen_at=now)
        self._sessions[token] = session
        return session

    def touch(self, token: str) -> Session | None:
        """Valide un session_token et rafraîchit son horodatage. None si invalide/expiré."""
        session = self._sessions.get(token)
        if session is None or session.expired():
            self._sessions.pop(token, None)
            return None
        session.last_seen_at = time.monotonic()
        return session

    def revoke(self, token: str) -> None:
        self._sessions.pop(token, None)


def _looks_like_sha256(value: str) -> bool:
    return len(value) == 64 and all(c in string.hexdigits for c in value)


def hash_device_id(raw_device_id: str) -> str:
    return hashlib.sha256(raw_device_id.encode("utf-8")).hexdigest()
