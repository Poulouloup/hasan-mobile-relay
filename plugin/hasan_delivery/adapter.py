"""hasan_delivery platform adapter (Hermes plugin).

Relaie les messages entre l'agent Hermes et l'app Android Hasan, via le
relay server auto-hébergé (server/relay/server.py dans hasan-mobile-relay).
Contrairement à un adapter de chat classique (Telegram, Discord...), ce
canal ne parle pas directement au protocole d'une plateforme tierce : il
parle au relay server via HTTP, qui lui-même tient la connexion WebSocket
avec le téléphone.

Ce plugin ne gère qu'un seul device (un couple opérateur/téléphone), pas de
multi-tenant : HASAN_RELAY_SESSION_TOKEN identifie le device déjà appairé.
Le pairing initial (génération du code, scan QR, /pairing/register) se fait
hors de ce plugin, via l'app + le relay server directement (voir étape 5).

Ce plugin ships sous ``plugins/hasan_delivery/`` (ou ``~/.hermes/plugins/``
en tiers). Le loader de plugins Hermes scanne le dossier au démarrage,
appelle :func:`register`, et le canal devient disponible pour
``gateway/run.py`` et ``tools/send_message_tool`` via le registre — aucune
modification du core Hermes n'est nécessaire.

Configuration in config.yaml::

    platforms:
      hasan_delivery:
        enabled: true
        extra:
          relay_url: "https://relay.example.com"
          session_token: "..."

Variables d'environnement (lues à la construction de l'adapter, env prime
sur config.yaml ``extra``) :

    HASAN_RELAY_URL             Base URL du relay server (requis)
    HASAN_RELAY_ADMIN_TOKEN     Bearer token pour /pairing/create (admin)
    HASAN_RELAY_SESSION_TOKEN   Token de session du device déjà appairé
    HASAN_PHONE_ENABLED         "false" pour désactiver sans supprimer la config

Modèle d'identité : un seul device par instance de plugin — pas de
multi-utilisateur. ``chat_id``/``user_id`` sont fixés à une valeur
constante ("phone") puisqu'il n'y a qu'un seul canal possible.
"""

import asyncio
import logging
import os
import uuid
from datetime import datetime, timezone
from typing import Any, Dict, List, Optional

try:
    import httpx
    HTTPX_AVAILABLE = True
except ImportError:
    HTTPX_AVAILABLE = False
    httpx = None  # type: ignore[assignment]

from gateway.config import Platform, PlatformConfig
from gateway.platforms.base import (
    BasePlatformAdapter,
    MessageEvent,
    MessageType,
    SendResult,
)

from .hooks import check_relay_health

logger = logging.getLogger(__name__)


MAX_MESSAGE_LENGTH = 4096
RECONNECT_BACKOFF = [2, 5, 10, 30, 60]
LONG_POLL_TIMEOUT_SECONDS = 30.0
# Marge ajoutée au timeout HTTP client pour laisser le serveur répondre
# {"replies": []} juste avant sa propre échéance plutôt que de timeout
# côté client d'abord.
LONG_POLL_CLIENT_MARGIN_SECONDS = 5.0

# Un seul device par instance de plugin — pas de multi-tenant ici.
_FIXED_CHAT_ID = "phone"
_FIXED_USER_ID = "phone"


class _FatalStreamError(Exception):
    """Erreur de long-poll non récupérable (401, config manquante)."""


def check_requirements() -> bool:
    """Vérifie que httpx est dispo et que l'URL du relay est configurée."""
    if not HTTPX_AVAILABLE:
        return False
    return bool(os.getenv("HASAN_RELAY_URL", "").strip())


def validate_config(config) -> bool:
    extra = getattr(config, "extra", {}) or {}
    relay_url = extra.get("relay_url") or os.getenv("HASAN_RELAY_URL", "")
    session_token = extra.get("session_token") or os.getenv("HASAN_RELAY_SESSION_TOKEN", "")
    return bool(relay_url) and bool(session_token)


def is_connected(config) -> bool:
    extra = getattr(config, "extra", {}) or {}
    relay_url = os.getenv("HASAN_RELAY_URL") or extra.get("relay_url", "")
    return bool(relay_url)


class HasanPhoneAdapter(BasePlatformAdapter):
    """Adapter reliant Hermes au relay server Hasan (un seul device)."""

    MAX_MESSAGE_LENGTH = MAX_MESSAGE_LENGTH
    supports_code_blocks = False

    def __init__(self, config: PlatformConfig):
        platform = Platform("hasan_delivery")
        super().__init__(config=config, platform=platform)

        extra = config.extra or {}
        self._relay_url: str = (
            extra.get("relay_url") or os.getenv("HASAN_RELAY_URL", "")
        ).rstrip("/")
        self._session_token: str = (
            extra.get("session_token") or os.getenv("HASAN_RELAY_SESSION_TOKEN", "")
        )

        self._poll_task: Optional[asyncio.Task] = None
        self._http_client: Optional["httpx.AsyncClient"] = None

    # ── Connexion ────────────────────────────────────────────────────────

    async def connect(self, *, is_reconnect: bool = False) -> bool:
        if not HTTPX_AVAILABLE:
            logger.warning("[%s] httpx non installé. pip install httpx", self.name)
            return False
        if not self._relay_url:
            logger.warning("[%s] HASAN_RELAY_URL non configuré", self.name)
            return False
        if not self._session_token:
            logger.warning(
                "[%s] HASAN_RELAY_SESSION_TOKEN non configuré — le device doit "
                "d'abord être appairé via /pairing/register",
                self.name,
            )
            return False

        if not await check_relay_health(self._relay_url):
            logger.warning(
                "[%s] Relay server injoignable sur %s — abandon de la connexion",
                self.name, self._relay_url,
            )
            return False

        self._http_client = httpx.AsyncClient(timeout=None)
        self._poll_task = asyncio.create_task(self._run_long_poll_loop())
        self._mark_connected()
        logger.info("[%s] Connecté — long-poll sur %s/phone/replies", self.name, self._relay_url)
        return True

    async def disconnect(self) -> None:
        self._running = False
        self._mark_disconnected()

        if self._poll_task:
            self._poll_task.cancel()
            try:
                await self._poll_task
            except asyncio.CancelledError:
                pass
            self._poll_task = None

        if self._http_client:
            await self._http_client.aclose()
            self._http_client = None

        logger.info("[%s] Déconnecté", self.name)

    def _auth_headers(self) -> Dict[str, str]:
        return {"Authorization": f"Bearer {self._session_token}"}

    # ── Long-poll entrant ────────────────────────────────────────────────

    async def _run_long_poll_loop(self) -> None:
        backoff_idx = 0
        url = f"{self._relay_url}/phone/replies"

        while self._running:
            try:
                got_reply = await self._poll_once(url)
                if got_reply:
                    backoff_idx = 0
            except asyncio.CancelledError:
                return
            except _FatalStreamError:
                self._running = False
                return
            except Exception as exc:
                if not self._running:
                    return
                logger.warning("[%s] Erreur long-poll: %s", self.name, exc)
                delay = RECONNECT_BACKOFF[min(backoff_idx, len(RECONNECT_BACKOFF) - 1)]
                await asyncio.sleep(delay)
                backoff_idx += 1

    async def _poll_once(self, url: str) -> bool:
        """Un aller-retour de long-poll. Retourne True si au moins une réponse a été traitée."""
        response = await self._http_client.get(
            url,
            headers=self._auth_headers(),
            params={"timeout": int(LONG_POLL_TIMEOUT_SECONDS)},
            timeout=httpx.Timeout(
                connect=15.0,
                read=LONG_POLL_TIMEOUT_SECONDS + LONG_POLL_CLIENT_MARGIN_SECONDS,
                write=15.0,
                pool=15.0,
            ),
        )

        if response.status_code == 401:
            logger.error(
                "[%s] Authentification refusée (401) — arrêt de la boucle. "
                "Vérifier HASAN_RELAY_SESSION_TOKEN.",
                self.name,
            )
            self._set_fatal_error(
                "hasan_relay_unauthorized",
                "Le relay server a rejeté le session_token (401).",
                retryable=False,
            )
            raise _FatalStreamError("401 Unauthorized")

        response.raise_for_status()
        data = response.json()
        replies = data.get("replies") or []

        for reply in replies:
            await self._on_reply(reply)

        return bool(replies)

    async def _on_reply(self, reply: Dict[str, Any]) -> None:
        text = (reply.get("text") or "").strip()
        if not text:
            return

        message_id = reply.get("id") or uuid.uuid4().hex

        raw_ts = reply.get("timestamp")
        try:
            timestamp = (
                datetime.fromtimestamp(float(raw_ts), tz=timezone.utc)
                if raw_ts is not None else datetime.now(tz=timezone.utc)
            )
        except (ValueError, OSError, TypeError):
            timestamp = datetime.now(tz=timezone.utc)

        source = self.build_source(
            chat_id=_FIXED_CHAT_ID,
            chat_name="Hasan (téléphone)",
            chat_type="dm",
            user_id=_FIXED_USER_ID,
            user_name="Hasan",
        )

        message_event = MessageEvent(
            text=text,
            message_type=MessageType.TEXT,
            source=source,
            message_id=message_id,
            raw_message=reply,
            timestamp=timestamp,
        )

        logger.debug("[%s] Message reçu: %s", self.name, text[:80])
        await self.handle_message(message_event)

    # ── Envoi sortant ────────────────────────────────────────────────────

    async def send(
        self,
        chat_id: str,
        content: str,
        reply_to: Optional[str] = None,
        metadata: Optional[Dict[str, Any]] = None,
    ) -> SendResult:
        if not self._http_client:
            return SendResult(success=False, error="HTTP client non initialisé")

        if len(content) > self.MAX_MESSAGE_LENGTH:
            logger.warning(
                "[%s] Message tronqué de %d à %d caractères",
                self.name, len(content), self.MAX_MESSAGE_LENGTH,
            )
        body_text = content[: self.MAX_MESSAGE_LENGTH]

        url = f"{self._relay_url}/phone/message"
        try:
            response = await self._http_client.post(
                url,
                json={"text": body_text},
                headers=self._auth_headers(),
                timeout=15.0,
            )
            if response.status_code >= 300:
                error_body = response.text[:200]
                logger.warning("[%s] Envoi échoué HTTP %d: %s", self.name, response.status_code, error_body)
                return SendResult(success=False, error=f"HTTP {response.status_code}: {error_body}")

            data = response.json()
            return SendResult(success=True, message_id=uuid.uuid4().hex[:12], raw_response=data)
        except httpx.TimeoutException:
            return SendResult(success=False, error="Timeout en envoyant au relay server")
        except Exception as exc:
            logger.error("[%s] Erreur d'envoi: %s", self.name, exc)
            return SendResult(success=False, error=str(exc))

    async def send_typing(self, chat_id: str, metadata=None) -> None:
        """Pas d'indicateur de frappe côté relay pour l'instant."""
        pass

    async def get_chat_info(self, chat_id: str) -> Dict[str, Any]:
        return {"name": "Hasan (téléphone)", "type": "dm", "chat_id": _FIXED_CHAT_ID}


# ---------------------------------------------------------------------------
# Enregistrement du plugin
# ---------------------------------------------------------------------------


def _env_enablement() -> Optional[dict]:
    """Seed ``PlatformConfig.extra`` depuis les env vars, avant construction
    de l'adapter — permet à un setup env-only d'apparaître dans
    ``hermes gateway status`` sans instancier le client HTTP.
    """
    relay_url = os.getenv("HASAN_RELAY_URL", "").strip()
    if not relay_url:
        return None

    seed: dict = {"relay_url": relay_url.rstrip("/")}

    session_token = os.getenv("HASAN_RELAY_SESSION_TOKEN", "").strip()
    if session_token:
        seed["session_token"] = session_token

    admin_token = os.getenv("HASAN_RELAY_ADMIN_TOKEN", "").strip()
    if admin_token:
        seed["admin_token"] = admin_token

    return seed


async def _standalone_send(
    pconfig,
    chat_id: str,
    message: str,
    *,
    thread_id: Optional[str] = None,
    media_files: Optional[List[str]] = None,
    force_document: bool = False,
) -> Dict[str, Any]:
    """Envoi hors-process pour cron / send_message_tool.

    ``thread_id`` et ``media_files`` acceptés pour la parité de signature
    uniquement — ce canal n'a ni thread ni pièce jointe pour l'instant.
    """
    if not HTTPX_AVAILABLE:
        return {"error": "hasan_delivery standalone send: httpx non installé"}

    extra = getattr(pconfig, "extra", {}) or {}
    relay_url = (extra.get("relay_url") or os.getenv("HASAN_RELAY_URL", "")).rstrip("/")
    session_token = extra.get("session_token") or os.getenv("HASAN_RELAY_SESSION_TOKEN", "")

    if not relay_url or not session_token:
        return {"error": "hasan_delivery standalone send: HASAN_RELAY_URL/HASAN_RELAY_SESSION_TOKEN manquants"}

    body_text = message[:MAX_MESSAGE_LENGTH]
    url = f"{relay_url}/phone/message"
    headers = {"Authorization": f"Bearer {session_token}"}

    try:
        async with httpx.AsyncClient(timeout=15.0) as client:
            response = await client.post(url, json={"text": body_text}, headers=headers)
        if response.status_code >= 300:
            return {"error": f"hasan_delivery HTTP {response.status_code}: {response.text[:200]}"}
        return {"success": True, "platform": "hasan_delivery", "chat_id": _FIXED_CHAT_ID}
    except Exception as exc:
        return {"error": f"hasan_delivery standalone send failed: {exc}"}


def register(ctx) -> None:
    """Point d'entrée du plugin — appelé par le système de plugins Hermes au démarrage."""
    ctx.register_platform(
        name="hasan_delivery",
        label="Hasan Phone",
        adapter_factory=lambda cfg: HasanPhoneAdapter(cfg),
        check_fn=check_requirements,
        validate_config=validate_config,
        is_connected=is_connected,
        required_env=["HASAN_RELAY_URL", "HASAN_RELAY_SESSION_TOKEN"],
        install_hint="pip install httpx   # déjà une dépendance Hermes",
        env_enablement_fn=_env_enablement,
        standalone_sender_fn=_standalone_send,
        max_message_length=MAX_MESSAGE_LENGTH,
        emoji="📱",
        # Un seul device fixe par instance — pas d'identifiant utilisateur
        # variable à rédiger dans les logs.
        pii_safe=True,
        platform_hint=(
            "Tu communiques avec l'utilisateur via son téléphone Android "
            "(app compagnon Hasan). Utilise du texte simple, pas de markdown "
            "riche — le message est lu ou affiché tel quel sur le téléphone."
        ),
    )
