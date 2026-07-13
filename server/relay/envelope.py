"""Enveloppe JSON multiplexée entre le relay server et l'app Android.

Format : {version, channel, type, id, payload}. `version` est obligatoire dès
le départ pour permettre de faire évoluer le protocole plus tard sans casser
silencieusement la compatibilité entre app et serveur.
"""

from __future__ import annotations

import uuid
from dataclasses import dataclass, field
from typing import Any

PROTOCOL_VERSION = 1

CHANNELS = ("system", "chat", "proactive", "bridge")


class EnvelopeError(ValueError):
    pass


@dataclass
class Envelope:
    channel: str
    type: str
    payload: dict[str, Any]
    version: int = PROTOCOL_VERSION
    id: str = field(default_factory=lambda: str(uuid.uuid4()))

    def to_dict(self) -> dict[str, Any]:
        return {
            "version": self.version,
            "channel": self.channel,
            "type": self.type,
            "id": self.id,
            "payload": self.payload,
        }

    @staticmethod
    def from_dict(data: dict[str, Any]) -> "Envelope":
        if "version" not in data:
            raise EnvelopeError("missing required field 'version'")
        if not isinstance(data["version"], int):
            raise EnvelopeError("'version' must be an int")
        if data["version"] != PROTOCOL_VERSION:
            raise EnvelopeError(
                f"unsupported protocol version {data['version']!r} "
                f"(expected {PROTOCOL_VERSION})"
            )

        channel = data.get("channel")
        if channel not in CHANNELS:
            raise EnvelopeError(f"invalid or missing channel {channel!r}")

        msg_type = data.get("type")
        if not isinstance(msg_type, str) or not msg_type:
            raise EnvelopeError("missing required field 'type'")

        payload = data.get("payload")
        if not isinstance(payload, dict):
            raise EnvelopeError("missing or invalid 'payload' (must be an object)")

        envelope_id = data.get("id") or str(uuid.uuid4())

        return Envelope(
            channel=channel,
            type=msg_type,
            payload=payload,
            version=data["version"],
            id=envelope_id,
        )
