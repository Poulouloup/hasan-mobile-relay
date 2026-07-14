"""Tools function-calling exposant les capabilities du téléphone Android Hasan
à l'agent Hermes — pattern _SCHEMAS/registry.register() repris de
Codename-11/hermes-relay (plugin/tools/android_tool.py), adapté au relay
server de ce projet (server/relay/server.py, un seul endpoint générique
POST /bridge/command au lieu d'une route HTTP dédiée par action).

Contrairement à hermes-relay (qui pilote un bridge d'accessibilité générique —
tap, swipe, screenshot...), les capabilities ici sont un ensemble fixe et
typé, déclaré côté Android (voir CapabilitySchema.kt / McpFragment.kt
ALL_CAPABILITIES) : chaque tool correspond à une capability précise avec son
propre schéma de paramètres, pas une primitive d'automatisation générique.

Le registry réel (classe Registry, méthode register()) vit dans le runtime
Hermes privé — non disponible dans ce dépôt, comme dans hermes-relay. Le
bloc d'enregistrement en fin de fichier échoue silencieusement (ImportError)
hors de ce contexte, ce qui permet d'importer ce module pour ses tests sans
dépendre du runtime complet.

Variables d'environnement (mêmes noms que plugin/hasan_delivery/adapter.py,
le canal de messages existant pour ce même device) :
    HASAN_RELAY_URL             Base URL du relay server (requis)
    HASAN_RELAY_SESSION_TOKEN   Token de session du device déjà appairé (requis)
"""

from __future__ import annotations

import json
import os
from typing import Any, Callable, Dict, Optional

try:
    import requests
    REQUESTS_AVAILABLE = True
except ImportError:
    REQUESTS_AVAILABLE = False
    requests = None  # type: ignore[assignment]

DEFAULT_TIMEOUT_SECONDS = 30.0


def _relay_url() -> str:
    return os.getenv("HASAN_RELAY_URL", "").rstrip("/")


def _session_token() -> str:
    return os.getenv("HASAN_RELAY_SESSION_TOKEN", "")


def _auth_headers() -> Dict[str, str]:
    token = _session_token()
    return {"Authorization": f"Bearer {token}"} if token else {}


def _check_requirements() -> bool:
    return REQUESTS_AVAILABLE and bool(_relay_url()) and bool(_session_token())


def _call_capability(capability: str, params: Dict[str, Any]) -> str:
    """POST /bridge/command — un seul endpoint pour toutes les capabilities
    (voir server/relay/server.py handle_bridge_command), contrairement au
    pattern hermes-relay d'une route par action. Retourne toujours une string
    JSON, jamais d'exception qui remonte au LLM (même contrat que hermes-relay :
    {"error": "..."} en cas d'échec réseau/HTTP/timeout/device déconnecté).
    """
    if not REQUESTS_AVAILABLE:
        return json.dumps({"error": "python package 'requests' non installé"})
    if not _relay_url() or not _session_token():
        return json.dumps({"error": "HASAN_RELAY_URL/HASAN_RELAY_SESSION_TOKEN non configurés"})

    try:
        response = requests.post(
            f"{_relay_url()}/bridge/command",
            json={"capability": capability, "params": params},
            headers=_auth_headers(),
            timeout=DEFAULT_TIMEOUT_SECONDS,
        )
        if response.status_code == 503:
            return json.dumps({"error": "device_not_connected", "code": "device_not_connected"})
        if response.status_code == 504:
            return json.dumps({"error": "command_timeout", "code": "command_timeout"})
        response.raise_for_status()
        return json.dumps(response.json())
    except Exception as exc:  # noqa: BLE001 - contrat: jamais d'exception vers le LLM
        return json.dumps({"error": str(exc)})


# ─────────────────────────── Handlers ──────────────────────────────────────
# Un handler par capability déclarée côté Android (CapabilitySchema.kt).
# Les paramètres optionnels absents ne sont pas envoyés (le serveur/l'exécuteur
# Android applique déjà ses propres valeurs par défaut, voir CapabilityExecutor.kt).


def android_get_battery() -> str:
    return _call_capability("get_battery", {})


def android_send_sms(to: str, message: str) -> str:
    return _call_capability("send_sms", {"to": to, "message": message})


def android_get_location() -> str:
    return _call_capability("get_location", {})


def android_send_notification(body: str, title: Optional[str] = None) -> str:
    params: Dict[str, Any] = {"body": body}
    if title is not None:
        params["title"] = title
    return _call_capability("send_notification", params)


def android_set_volume(level: int) -> str:
    return _call_capability("set_volume", {"level": level})


def android_launch_app(package_name: str) -> str:
    return _call_capability("launch_app", {"package_name": package_name})


def android_discover_apps() -> str:
    return _call_capability("discover_apps", {})


def android_get_contacts(query: Optional[str] = None, limit: Optional[int] = None) -> str:
    params: Dict[str, Any] = {}
    if query is not None:
        params["query"] = query
    if limit is not None:
        params["limit"] = limit
    return _call_capability("get_contacts", params)


def android_set_alarm(hour: int, minute: int, label: Optional[str] = None) -> str:
    params: Dict[str, Any] = {"hour": hour, "minute": minute}
    if label is not None:
        params["label"] = label
    return _call_capability("set_alarm", params)


def android_get_network_info() -> str:
    return _call_capability("get_network_info", {})


def android_get_device_info() -> str:
    return _call_capability("get_device_info", {})


_HANDLERS: Dict[str, Callable[..., str]] = {
    "android_get_battery": android_get_battery,
    "android_send_sms": android_send_sms,
    "android_get_location": android_get_location,
    "android_send_notification": android_send_notification,
    "android_set_volume": android_set_volume,
    "android_launch_app": android_launch_app,
    "android_discover_apps": android_discover_apps,
    "android_get_contacts": android_get_contacts,
    "android_set_alarm": android_set_alarm,
    "android_get_network_info": android_get_network_info,
    "android_get_device_info": android_get_device_info,
}


# ─────────────────────────── Schémas JSON function-calling ─────────────────
# Reflète exactement les ParamSpec déclarés côté Android (McpFragment.kt
# ALL_CAPABILITIES / CapabilitySchema.kt validateParams) — toute évolution du
# schéma doit être faite des deux côtés pour rester synchronisée.

_SCHEMAS: Dict[str, Dict[str, Any]] = {
    "android_get_battery": {
        "name": "android_get_battery",
        "description": "Lit le niveau de batterie et l'état de charge du téléphone Android de l'utilisateur.",
        "parameters": {"type": "object", "properties": {}, "required": []},
    },
    "android_send_sms": {
        "name": "android_send_sms",
        "description": "Envoie un SMS depuis le téléphone de l'utilisateur. Nécessite que la capability soit activée et la permission SEND_SMS accordée côté téléphone.",
        "parameters": {
            "type": "object",
            "properties": {
                "to": {"type": "string", "description": "Numéro de téléphone du destinataire"},
                "message": {"type": "string", "description": "Contenu du SMS"},
            },
            "required": ["to", "message"],
        },
    },
    "android_get_location": {
        "name": "android_get_location",
        "description": "Lit la dernière position GPS/réseau connue du téléphone. Nécessite la permission de localisation accordée côté téléphone.",
        "parameters": {"type": "object", "properties": {}, "required": []},
    },
    "android_send_notification": {
        "name": "android_send_notification",
        "description": "Affiche une notification sur le téléphone de l'utilisateur.",
        "parameters": {
            "type": "object",
            "properties": {
                "title": {"type": "string", "description": "Titre de la notification (défaut: Hasan)"},
                "body": {"type": "string", "description": "Texte de la notification"},
            },
            "required": ["body"],
        },
    },
    "android_set_volume": {
        "name": "android_set_volume",
        "description": "Règle le volume média du téléphone.",
        "parameters": {
            "type": "object",
            "properties": {
                "level": {"type": "integer", "description": "Volume cible, de 0 à 100"},
            },
            "required": ["level"],
        },
    },
    "android_launch_app": {
        "name": "android_launch_app",
        "description": "Lance une application installée sur le téléphone par son nom de package.",
        "parameters": {
            "type": "object",
            "properties": {
                "package_name": {"type": "string", "description": "Nom de package Android à lancer (ex: com.whatsapp)"},
            },
            "required": ["package_name"],
        },
    },
    "android_discover_apps": {
        "name": "android_discover_apps",
        "description": "Liste les applications installées sur le téléphone (nom de package + label affiché).",
        "parameters": {"type": "object", "properties": {}, "required": []},
    },
    "android_get_contacts": {
        "name": "android_get_contacts",
        "description": "Recherche des contacts dans le répertoire du téléphone. Nécessite la permission READ_CONTACTS accordée côté téléphone.",
        "parameters": {
            "type": "object",
            "properties": {
                "query": {"type": "string", "description": "Filtre sur le nom du contact (recherche partielle)"},
                "limit": {"type": "integer", "description": "Nombre maximum de résultats (défaut 20, max 100)"},
            },
            "required": [],
        },
    },
    "android_set_alarm": {
        "name": "android_set_alarm",
        "description": "Programme une alarme sur le téléphone via l'application horloge.",
        "parameters": {
            "type": "object",
            "properties": {
                "hour": {"type": "integer", "description": "Heure de l'alarme, 0-23"},
                "minute": {"type": "integer", "description": "Minute de l'alarme, 0-59"},
                "label": {"type": "string", "description": "Libellé de l'alarme (défaut: Hasan)"},
            },
            "required": ["hour", "minute"],
        },
    },
    "android_get_network_info": {
        "name": "android_get_network_info",
        "description": "Lit l'état de connexion réseau du téléphone (WiFi/cellulaire, SSID, force du signal...).",
        "parameters": {"type": "object", "properties": {}, "required": []},
    },
    "android_get_device_info": {
        "name": "android_get_device_info",
        "description": "Lit les informations générales du téléphone (modèle, version Android, RAM, stockage...).",
        "parameters": {"type": "object", "properties": {}, "required": []},
    },
}


# ─────────────────────────── Enregistrement du plugin ──────────────────────

try:
    from tools.registry import registry  # module privé runtime Hermes, absent hors de ce contexte

    for _tool_name, _schema in _SCHEMAS.items():
        registry.register(
            name=_tool_name,
            toolset="android",
            schema=_schema,
            handler=_HANDLERS[_tool_name],
            check_fn=_check_requirements,
            requires_env=["HASAN_RELAY_URL", "HASAN_RELAY_SESSION_TOKEN"],
        )
except ImportError:
    pass
