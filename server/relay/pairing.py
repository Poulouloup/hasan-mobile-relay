"""Pairing QR + session tokens, avec persistance disque et refresh token.

Flux : le serveur génère un code de pairing éphémère (affiché en QR côté
serveur ou via un canal admin), l'app le scanne et l'envoie à /pairing/register
avec le SHA-256 de son device_hash. Le serveur répond avec un session_token
opaque (courte durée de vie, renouvelée à l'usage) et un refresh_token
(longue durée de vie, usage unique — rotation à chaque utilisation) que
l'app doit ensuite présenter (Bearer) sur toutes les requêtes authentifiées
et à l'authentification WebSocket.

Le TOFU (Trust On First Use) du certificat serveur est vérifié côté client
(app Android, CertPinStore) — ce module ne gère que l'auth applicative.

Persistance : les sessions survivent à un redémarrage du process via un
fichier JSON (écriture atomique, permissions 0o600). Sans ça, un simple
redémarrage du relay server (déploiement, crash, reboot du VPS) forçait un
re-pairing complet — inspiré de Codename-11/hermes-relay (session_store.py),
mais sans la dépendance à ce projet : implémentation propre ici.

Toutes les horloges utilisent time.time() (epoch Unix), PAS time.monotonic()
— monotonic() repart de zéro à chaque process et n'a aucun sens une fois
persisté sur disque et relu après un redémarrage.
"""

from __future__ import annotations

import hashlib
import json
import logging
import os
import secrets
import string
import tempfile
import time
from dataclasses import dataclass, field
from pathlib import Path

log = logging.getLogger("relay.pairing")

PAIRING_CODE_ALPHABET = string.ascii_uppercase + string.digits
PAIRING_CODE_LENGTH = 6
PAIRING_CODE_TTL_SECONDS = 10 * 60  # 10 minutes pour scanner le QR

SESSION_TOKEN_TTL_SECONDS = 30 * 24 * 60 * 60  # 30 jours, renouvelé à l'usage (touch())
REFRESH_TOKEN_TTL_SECONDS = 180 * 24 * 60 * 60  # 180 jours, usage unique (rotation à chaque refresh)

DEFAULT_SESSIONS_PATH = Path.home() / ".hermes" / "hasan-relay-sessions.json"


@dataclass
class PendingPairing:
    code: str
    created_at: float  # time.time()

    def expired(self) -> bool:
        return time.time() - self.created_at > PAIRING_CODE_TTL_SECONDS


@dataclass
class Session:
    token: str
    device_hash: str
    created_at: float  # time.time()
    last_seen_at: float  # time.time()
    # Hash SHA-256 du refresh_token courant — jamais le refresh_token en clair
    # n'est conservé côté serveur (même logique que le session_token n'étant
    # jamais loggé). None si ce device n'a pas encore de refresh_token actif
    # (ex: session migrée depuis un ancien format sans refresh).
    refresh_token_hash: str | None = None
    refresh_expires_at: float | None = None  # time.time()

    def expired(self) -> bool:
        return time.time() - self.last_seen_at > SESSION_TOKEN_TTL_SECONDS

    def refresh_token_expired(self) -> bool:
        if self.refresh_token_hash is None or self.refresh_expires_at is None:
            return True
        return time.time() > self.refresh_expires_at

    def to_dict(self) -> dict:
        return {
            "token": self.token,
            "device_hash": self.device_hash,
            "created_at": self.created_at,
            "last_seen_at": self.last_seen_at,
            "refresh_token_hash": self.refresh_token_hash,
            "refresh_expires_at": self.refresh_expires_at,
        }

    @staticmethod
    def from_dict(data: dict) -> "Session | None":
        try:
            return Session(
                token=data["token"],
                device_hash=data["device_hash"],
                created_at=float(data["created_at"]),
                last_seen_at=float(data["last_seen_at"]),
                refresh_token_hash=data.get("refresh_token_hash"),
                refresh_expires_at=data.get("refresh_expires_at"),
            )
        except (KeyError, TypeError, ValueError):
            return None


@dataclass
class RefreshResult:
    session: Session
    refresh_token: str  # en clair, une seule fois — jamais restitué ensuite


class PairingManager:
    """Gère pairing codes et sessions, avec persistance JSON optionnelle.

    Si [sessions_path] est None, fonctionne entièrement en mémoire (pas de
    persistance) — pratique pour les tests, qui ne doivent jamais toucher le
    disque ni interférer les uns avec les autres.
    """

    def __init__(self, sessions_path: Path | None = DEFAULT_SESSIONS_PATH) -> None:
        self._pending: dict[str, PendingPairing] = {}
        self._sessions: dict[str, Session] = {}
        self._sessions_path = sessions_path
        if self._sessions_path is not None:
            self._load_from_disk()

    def create_pairing_code(self) -> str:
        code = "".join(secrets.choice(PAIRING_CODE_ALPHABET) for _ in range(PAIRING_CODE_LENGTH))
        self._pending[code] = PendingPairing(code=code, created_at=time.time())
        return code

    def redeem(self, code: str, device_hash: str) -> RefreshResult | None:
        """Consomme un code de pairing valide et émet une session + refresh_token. None si code invalide/expiré."""
        pending = self._pending.get(code)
        if pending is None or pending.expired():
            self._pending.pop(code, None)
            return None

        del self._pending[code]

        if not _looks_like_sha256(device_hash):
            return None

        token = secrets.token_urlsafe(32)
        refresh_token = secrets.token_urlsafe(32)
        now = time.time()
        session = Session(
            token=token,
            device_hash=device_hash,
            created_at=now,
            last_seen_at=now,
            refresh_token_hash=_hash_token(refresh_token),
            refresh_expires_at=now + REFRESH_TOKEN_TTL_SECONDS,
        )
        self._sessions[token] = session
        self._save_to_disk()
        return RefreshResult(session=session, refresh_token=refresh_token)

    def touch(self, token: str) -> Session | None:
        """Valide un session_token et rafraîchit son horodatage. None si invalide/expiré."""
        session = self._sessions.get(token)
        if session is None or session.expired():
            if session is not None:
                self._sessions.pop(token, None)
                self._save_to_disk()
            return None
        session.last_seen_at = time.time()
        # Pas de _save_to_disk() ici : touch() est appelé sur QUASI CHAQUE
        # requête HTTP/WS — écrire le fichier à chaque fois serait une
        # amplification I/O disproportionnée pour un TTL glissant de 30
        # jours. Le pire cas en cas de crash entre deux vraies mutations
        # (redeem/refresh/revoke) est un TTL légèrement sous-estimé au
        # rechargement, jamais une perte de session.
        return session

    def refresh(self, refresh_token: str) -> RefreshResult | None:
        """Échange un refresh_token contre un nouveau (session_token, refresh_token).

        Rotation stricte : l'ancien refresh_token est immédiatement invalidé,
        qu'il soit réutilisé ou non — une seule tentative de refresh possible
        par token émis. Retourne None si le token est invalide, expiré, ou ne
        correspond à aucune session connue.
        """
        token_hash = _hash_token(refresh_token)
        session = next(
            (s for s in self._sessions.values() if s.refresh_token_hash == token_hash),
            None,
        )
        if session is None or session.refresh_token_expired():
            return None

        new_session_token = secrets.token_urlsafe(32)
        new_refresh_token = secrets.token_urlsafe(32)
        now = time.time()

        del self._sessions[session.token]
        new_session = Session(
            token=new_session_token,
            device_hash=session.device_hash,
            created_at=session.created_at,
            last_seen_at=now,
            refresh_token_hash=_hash_token(new_refresh_token),
            refresh_expires_at=now + REFRESH_TOKEN_TTL_SECONDS,
        )
        self._sessions[new_session_token] = new_session
        self._save_to_disk()
        return RefreshResult(session=new_session, refresh_token=new_refresh_token)

    def revoke(self, token: str) -> None:
        if self._sessions.pop(token, None) is not None:
            self._save_to_disk()

    # ─────────────────────────── Persistance ───────────────────────────

    def _load_from_disk(self) -> None:
        path = self._sessions_path
        if path is None or not path.exists():
            return
        try:
            raw = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as exc:
            log.warning("Impossible de lire %s (%s) — démarrage avec 0 session persistée", path, exc)
            return

        loaded = 0
        expired_skipped = 0
        for entry in raw.get("sessions", []):
            session = Session.from_dict(entry)
            if session is None:
                continue
            if session.expired():
                expired_skipped += 1
                continue
            self._sessions[session.token] = session
            loaded += 1

        log.info(
            "Sessions rechargées depuis %s : %d valides, %d expirées écartées",
            path, loaded, expired_skipped,
        )

    def _save_to_disk(self) -> None:
        path = self._sessions_path
        if path is None:
            return
        try:
            path.parent.mkdir(parents=True, exist_ok=True)
            payload = json.dumps(
                {"sessions": [s.to_dict() for s in self._sessions.values()]},
                indent=2,
            )
            # Écriture atomique : tempfile dans le même dossier (garantit que
            # os.replace reste sur le même filesystem) puis remplacement —
            # un crash pendant l'écriture ne corrompt jamais le fichier
            # existant, au pire on perd la toute dernière mutation.
            fd, tmp_path = tempfile.mkstemp(dir=str(path.parent), prefix=".sessions-", suffix=".tmp")
            try:
                with os.fdopen(fd, "w", encoding="utf-8") as f:
                    f.write(payload)
                os.chmod(tmp_path, 0o600)
                os.replace(tmp_path, path)
            except BaseException:
                try:
                    os.unlink(tmp_path)
                except OSError:
                    pass
                raise
        except OSError as exc:
            log.error("Échec d'écriture de %s : %s — session non persistée pour ce cycle", path, exc)


def _hash_token(token: str) -> str:
    return hashlib.sha256(token.encode("utf-8")).hexdigest()


def _looks_like_sha256(value: str) -> bool:
    return len(value) == 64 and all(c in string.hexdigits for c in value)


def hash_device_id(raw_device_id: str) -> str:
    return hashlib.sha256(raw_device_id.encode("utf-8")).hexdigest()
