"""Outils Hermes natifs pour les capabilities du téléphone Hasan — multi-device.

Remplace l'ancien serveur MCP externe (`~/.hermes/phone-relay-mcp/server.js`,
Node.js, hors de ce repo) — même contrat HTTP (`POST /bridge/command` sur le
relay, voir `server/relay/server.py::handle_bridge_command` et
`server/relay/bridge_commands.py`), mais enregistré directement dans le
registre d'outils natif de Hermes plutôt que via un serveur MCP séparé,
via `ctx.register_tool()` (voir register_tools() plus bas).

La confirmation utilisateur pour les capabilities sensibles (send_sms,
make_call, get_location, get_contacts, get_calendar_events, get_clipboard)
vit entièrement côté app Android (voir BridgeCommandHandler.kt côté app,
settings.isCapabilityAuthRequired) — ce module ne fait que relayer la
requête HTTP et attendre le résultat, exactement comme le faisait le MCP
qu'il remplace. Aucun changement de ce mécanisme de sécurité.

Schémas récupérés DYNAMIQUEMENT depuis `GET /devices` sur le relay, plutôt
que codés en dur ici — l'app annonce ses propres capabilities (activées +
permission accordée) et un libellé lisible à chaque connexion WS (voir
ConnectionManager.kt, CapabilitySchema.kt, SettingsManager.kt::relayDeviceLabel),
le relay les persiste par device (server/relay/pairing.py::Session). Ajouter/
modifier une capability dans app/src/main/java/com/hasan/v1/Capability.kt
suffit donc — aucune édition de ce fichier nécessaire pour une capability sur
un device déjà connu.

Plusieurs devices (téléphones, Hasan Desktop) peuvent être connectés au même
relay simultanément — UN SEUL tool par capability (ex: `get_battery`), pas un
tool par (device, capability). Chaque tool prend un paramètre `device_id`
optionnel : auto-résolu par le handler si un seul device connecté expose la
capability (comportement transparent pour un utilisateur mono-device),
sinon erreur explicite demandant de préciser (voir `_resolve_device_id`).

Rafraîchissement à chaud : `sync_tools(ctx)` (appelée par la veille
`_device_watch_loop`, un thread démarré par register_tools() sur notification
du relay, voir GET /devices/watch) N'AJOUTE que les tools pour des capacités nouvellement
apparues — elle ne désenregistre JAMAIS un tool existant. `PluginContext`
(runtime Hermes, hors de ce repo) n'expose pas de méthode de désenregistrement,
et en ajouter une reviendrait à modifier le code source de Hermes, ce qui est
exclu (seuls des ajouts purs dans ce plugin sont autorisés). Un tool devenu
obsolète (plus aucun device ne l'expose) reste donc visible mais échoue
proprement à l'appel via `_resolve_device_id` jusqu'au prochain restart
naturel du gateway.

Si le relay est injoignable ou aucun device jamais appairé au chargement,
aucun tool `hasan_phone` n'est enregistré à ce moment-là (pas d'erreur
bloquante pour le chargement du plugin) ; la veille les ajoute dès que le
relay les annonce.

**Plusieurs processus chargent ce plugin**, pas seulement le gateway :
hermes-webui fait tourner son propre agent, dans son propre processus, et y
déclenche aussi la découverte des plugins. Les outils ne comptent que dans le
registre du processus où ils sont enregistrés. Deux conséquences, constatées
sur le homelab (outils absents de toutes les conversations de l'app) :

- webui charge les plugins AVANT de lire `~/.hermes/.env` (via
  hermes_cli.tools_config, à l'import de son module de config) : les
  variables HASAN_* n'y sont pas encore dans l'environnement. D'où
  `_relay_config()`, qui relit `$HERMES_HOME/.env` lui-même si besoin.
- la veille des devices vivait dans adapter.py, qui n'existe que dans le
  gateway : webui ne rattrapait jamais une capability activée après son
  démarrage. Elle est maintenant ici, dans un thread, quel que soit le
  processus.
"""

from __future__ import annotations

import asyncio
import json
import logging
import os
import threading
from pathlib import Path
from typing import Any

import httpx

logger = logging.getLogger(__name__)

_TIMEOUT_SECONDS = 35.0  # Marge au-dessus du timeout serveur (30s, bridge_commands.py)
_FETCH_TIMEOUT_SECONDS = 10.0
_WATCH_TIMEOUT_SECONDS = 30
_WATCH_CLIENT_MARGIN_SECONDS = 5.0
_WATCH_BACKOFF_SECONDS = [2, 5, 10, 30, 60]
_CONFIG_KEYS = ("HASAN_RELAY_URL", "HASAN_RELAY_ADMIN_TOKEN")

# Noms de tools déjà enregistrés — alimenté par register_tools() (démarrage)
# et sync_tools() (ajouts à chaud). Ne contient jamais un retrait : voir
# docstring du module pour le pourquoi (pas de deregister disponible).
_known_tool_names: set[str] = set()
# Sérialise les enregistrements : la veille tourne dans son propre thread.
_registration_lock = threading.Lock()
_watch_thread: threading.Thread | None = None


def _hermes_dotenv_values(keys: tuple[str, ...]) -> dict[str, str]:
    """Valeurs de [keys] lues dans `$HERMES_HOME/.env` (défaut ~/.hermes/.env),
    sans rien modifier ni exporter — seulement les clés demandées, le reste
    du fichier (clés d'API de l'utilisateur) n'est jamais conservé."""
    home = os.getenv("HERMES_HOME", "").strip() or str(Path.home() / ".hermes")
    values: dict[str, str] = {}
    try:
        lines = (Path(home) / ".env").read_text(encoding="utf-8").splitlines()
    except OSError:
        return values
    for line in lines:
        line = line.strip()
        if line.startswith("export "):
            line = line[len("export "):].lstrip()
        key, sep, value = line.partition("=")
        key = key.strip()
        if not sep or key not in keys:
            continue
        value = value.strip()
        if len(value) >= 2 and value[0] == value[-1] and value[0] in "'\"":
            value = value[1:-1]
        values[key] = value
    return values


def _relay_config() -> tuple[str, str]:
    """Lit l'URL du relay et le token ADMIN — pas le session_token d'un device
    particulier. GET /devices et POST /bridge/command sont cross-device
    (voir server.py handle_devices_list/handle_bridge_command), le plugin agit
    comme un opérateur de confiance, pas comme un device spécifique.

    L'environnement du processus d'abord ; à défaut `$HERMES_HOME/.env`, que
    hermes-webui n'a pas encore chargé quand il charge les plugins (voir la
    docstring du module)."""
    relay_url = os.getenv("HASAN_RELAY_URL", "").strip()
    admin_token = os.getenv("HASAN_RELAY_ADMIN_TOKEN", "").strip()
    if not relay_url or not admin_token:
        from_file = _hermes_dotenv_values(_CONFIG_KEYS)
        relay_url = relay_url or from_file.get("HASAN_RELAY_URL", "").strip()
        admin_token = admin_token or from_file.get("HASAN_RELAY_ADMIN_TOKEN", "").strip()
    return relay_url.rstrip("/"), admin_token


def _check_requirements() -> bool:
    relay_url, admin_token = _relay_config()
    return bool(relay_url and admin_token)


async def _fetch_devices() -> list[dict[str, Any]]:
    """GET /devices — tous les devices connus (connectés ou non), avec leurs
    capabilities et leur état de connexion live.

    Retourne [] (silencieusement) si le relay n'est pas configuré/joignable,
    ou si la réponse est malformée : ce module ne doit jamais faire échouer
    le chargement du plugin hasan_delivery.
    """
    relay_url, admin_token = _relay_config()
    if not relay_url or not admin_token:
        return []

    try:
        async with httpx.AsyncClient(timeout=_FETCH_TIMEOUT_SECONDS) as client:
            response = await client.get(
                f"{relay_url}/devices",
                headers={"Authorization": f"Bearer {admin_token}"},
            )
        response.raise_for_status()
        data = response.json()
    except (httpx.RequestError, httpx.HTTPStatusError, ValueError) as exc:
        logger.warning("hasan_delivery: échec récupération /devices: %s", exc)
        return []

    devices = data.get("devices")
    return devices if isinstance(devices, list) else []


def _build_schema(base_parameters: dict[str, Any] | None) -> dict[str, Any]:
    """Ajoute `device_id` au schéma de paramètres d'une capability — toujours
    présent, jamais dans `required` (voir docstring du module : Hermes
    n'applique aucune validation jsonschema sur les tool calls du LLM, la
    contrainte réelle est appliquée par le handler via _resolve_device_id)."""
    base = base_parameters or {"type": "object", "properties": {}, "required": []}
    properties = dict(base.get("properties") or {})
    properties["device_id"] = {
        "type": "string",
        "description": (
            "Identifiant du device ciblé (voir la liste des devices connectés). "
            "Optionnel si un seul device connecté expose cette capability — "
            "dans ce cas elle est résolue automatiquement. Devient nécessaire "
            "dès que plusieurs devices connectés l'exposent."
        ),
    }
    return {
        "type": "object",
        "properties": properties,
        "required": list(base.get("required") or []),
    }


def _merge_capabilities(devices: list[dict[str, Any]]) -> dict[str, dict[str, Any]]:
    """Union des capabilities PAR NOM à travers tous les devices — un seul
    schéma par nom (premier device rencontré qui l'expose), c'est cet
    ensemble de noms qui détermine les tools enregistrés (un seul
    `get_battery` même si N devices l'exposent)."""
    merged: dict[str, dict[str, Any]] = {}
    for device in devices:
        for entry in device.get("capabilities") or []:
            if not isinstance(entry, dict):
                continue
            name = entry.get("name")
            if not isinstance(name, str) or not name or name in merged:
                continue
            merged[name] = {
                "name": name,
                "description": entry.get("description") or f"Capability téléphone Hasan: {name}",
                "parameters": _build_schema(entry.get("parameters")),
            }
    return merged


async def _resolve_device_id(
    capability: str, requested_device_id: str | None
) -> tuple[str | None, str | None]:
    """Résout le device_id réel à cibler pour `capability`, au moment de
    l'APPEL (pas de l'enregistrement) — relit GET /devices pour refléter
    l'état live. Retourne (device_id, error_message) ; error_message non-None
    signifie qu'aucun appel ne doit être tenté.
    """
    devices = await _fetch_devices()
    connected_with_cap = [
        d
        for d in devices
        if d.get("connected")
        and any(
            isinstance(c, dict) and c.get("name") == capability
            for c in (d.get("capabilities") or [])
        )
    ]

    if requested_device_id:
        match = next(
            (d for d in connected_with_cap if d.get("device_id") == requested_device_id),
            None,
        )
        if match is None:
            return None, f"device '{requested_device_id}' non connecté ou n'expose pas '{capability}'"
        return requested_device_id, None

    if len(connected_with_cap) == 1:
        return connected_with_cap[0]["device_id"], None
    if len(connected_with_cap) == 0:
        return None, f"aucun device connecté n'expose la capability '{capability}'"

    labels = [d.get("label") or (d.get("device_id") or "?")[:8] for d in connected_with_cap]
    return None, (
        f"plusieurs devices connectés exposent '{capability}' ({', '.join(labels)}) "
        "— précise device_id"
    )


async def _call_capability(capability: str, device_id: str, params: dict[str, Any]) -> str:
    """POST /bridge/command sur le relay, attend le résultat, retourne du JSON.

    Contrat identique à celui utilisé côté MCP historique — mêmes codes
    d'erreur (401/400/503 device_not_connected/504 command_timeout, voir
    server/relay/server.py::handle_bridge_command), avec device_id explicite
    depuis le passage au multi-device.
    """
    relay_url, admin_token = _relay_config()
    if not relay_url or not admin_token:
        return json.dumps({"error": "HASAN_RELAY_URL/HASAN_RELAY_ADMIN_TOKEN non configurés"})

    try:
        async with httpx.AsyncClient(timeout=_TIMEOUT_SECONDS) as client:
            response = await client.post(
                f"{relay_url}/bridge/command",
                json={"device_id": device_id, "capability": capability, "params": params},
                headers={"Authorization": f"Bearer {admin_token}"},
            )
    except httpx.RequestError as exc:
        return json.dumps({"error": f"relay injoignable: {exc}"})

    try:
        data = response.json()
    except ValueError:
        return json.dumps({"error": f"réponse relay invalide (HTTP {response.status_code})"})
    return json.dumps(data)


def _tool(capability: str):
    """Fabrique un handler async qui résout le device cible puis relaie
    `capability` avec les args reçus du LLM (device_id extrait avant l'appel,
    jamais transmis tel quel comme paramètre métier)."""

    async def handler(args: dict[str, Any], **_kw: Any) -> str:
        call_args = dict(args or {})
        requested_device_id = call_args.pop("device_id", None)
        device_id, error = await _resolve_device_id(capability, requested_device_id)
        if error:
            return json.dumps({"error": error})
        return await _call_capability(capability, device_id, call_args)

    return handler


# ─────────────────────────── Enregistrement ─────────────────────────────────
# Appelé explicitement depuis __init__.py::register(ctx) — jamais à l'import
# de ce module. Hermes n'importe que ce que `plugins/hasan_delivery/__init__.py`
# référence ; un simple `import tools.py` à côté d'`adapter.py` ne suffit pas
# à le faire charger. Appeler ceci depuis register(ctx) garantit aussi que les
# requires_env (HASAN_RELAY_URL, HASAN_RELAY_ADMIN_TOKEN) sont déjà dans
# os.environ à ce stade, comme pour l'enregistrement de la plateforme adapter.py.
#
# IMPORTANT : passe par ctx.register_tool(), pas tools.registry.registry.register()
# directement. Les deux appellent le même registre sous le capot, mais seul
# ctx.register_tool() fait aussi self._manager._plugin_tool_names.add(name)
# côté PluginContext (hermes_cli/plugins.py) — c'est cette liste que
# get_plugin_toolsets()/`hermes tools enable` utilisent pour reconnaître un
# toolset comme valide. Un appel direct au registre enregistre bien le tool
# (invocable), mais le toolset reste invisible/« Unknown toolset » pour tout
# ce qui passe par cette liste d'attribution.
def _register(ctx, schemas: dict[str, dict[str, Any]]) -> None:
    for name, schema in schemas.items():
        ctx.register_tool(
            name=name,
            toolset="hasan_phone",
            schema=schema,
            handler=_tool(name),
            check_fn=_check_requirements,
            requires_env=["HASAN_RELAY_URL", "HASAN_RELAY_ADMIN_TOKEN"],
            is_async=True,
        )


def register_tools(ctx) -> None:
    """Enregistrement initial, appelé une fois au chargement du plugin — dans
    chaque processus qui le charge (gateway, hermes-webui…) — puis démarrage
    de la veille qui tient la liste à jour."""
    global _known_tool_names
    schemas = asyncio.run(_fetch_devices())
    merged = _merge_capabilities(schemas)
    with _registration_lock:
        _register(ctx, merged)
        _known_tool_names = set(merged.keys())
    _start_device_watch(ctx)


async def sync_tools(ctx) -> None:
    """Rafraîchissement à chaud — appelée depuis _device_watch_loop
    (plus bas) sur notification du relay
    (GET /devices/watch). N'ENREGISTRE QUE les capabilities nouvellement
    apparues (nouveau device connecté, ou nouvelle capability activée sur un
    device déjà connu) ; ne désenregistre jamais un tool existant — voir la
    docstring du module pour le pourquoi (pas de deregister disponible côté
    PluginContext, et en ajouter un reviendrait à modifier le code source de
    Hermes). Un tool devenu obsolète reste visible mais échoue proprement à
    l'appel via _resolve_device_id jusqu'au prochain restart naturel du
    gateway.
    """
    global _known_tool_names
    devices = await _fetch_devices()
    merged = _merge_capabilities(devices)
    with _registration_lock:
        new_names = set(merged.keys()) - _known_tool_names
        if not new_names:
            return
        _register(ctx, {name: merged[name] for name in new_names})
        _known_tool_names |= new_names
    logger.info("hasan_delivery: nouveaux tools enregistrés: %s", sorted(new_names))


# ─────────────────────────── Veille des devices ─────────────────────────────


def _watch_once(client: httpx.Client, ctx) -> bool:
    """Un aller-retour de GET /devices/watch (long-poll). Appelle sync_tools
    si le relay signale un changement. Retourne False quand la veille doit
    s'arrêter définitivement (configuration absente, jeton refusé) ; lève
    une exception sur une erreur passagère (la boucle réessaie)."""
    relay_url, admin_token = _relay_config()
    if not relay_url or not admin_token:
        logger.warning("hasan_delivery: HASAN_RELAY_URL/HASAN_RELAY_ADMIN_TOKEN absents — pas de veille des devices")
        return False
    response = client.get(
        f"{relay_url}/devices/watch",
        params={"timeout": _WATCH_TIMEOUT_SECONDS},
        headers={"Authorization": f"Bearer {admin_token}"},
        timeout=_WATCH_TIMEOUT_SECONDS + _WATCH_CLIENT_MARGIN_SECONDS,
    )
    if response.status_code == 401:
        logger.error("hasan_delivery: /devices/watch a refusé HASAN_RELAY_ADMIN_TOKEN (401) — arrêt de la veille")
        return False
    response.raise_for_status()
    if response.json().get("changed"):
        asyncio.run(sync_tools(ctx))
    return True


def _device_watch_loop(ctx) -> None:
    # Rattrapage immédiat : un device peut avoir annoncé ses capabilities
    # entre l'enregistrement initial et le démarrage de cette boucle.
    try:
        asyncio.run(sync_tools(ctx))
    except Exception as exc:  # jamais fatal : la boucle réessaiera
        logger.warning("hasan_delivery: synchronisation initiale des tools échouée: %s", exc)

    backoff_idx = 0
    with httpx.Client() as client:
        while True:
            try:
                if not _watch_once(client, ctx):
                    return
                backoff_idx = 0
            except Exception as exc:
                logger.warning("hasan_delivery: erreur veille des devices: %s", exc)
                threading.Event().wait(_WATCH_BACKOFF_SECONDS[min(backoff_idx, len(_WATCH_BACKOFF_SECONDS) - 1)])
                backoff_idx += 1


def _start_device_watch(ctx) -> None:
    """Démarre la veille une seule fois par processus. Thread démon : il ne
    retient jamais l'arrêt du processus, et n'a besoin d'aucune boucle asyncio
    existante — webui n'en fournit pas au chargement des plugins."""
    global _watch_thread
    if _watch_thread is not None and _watch_thread.is_alive():
        return
    _watch_thread = threading.Thread(
        target=_device_watch_loop, args=(ctx,), name="hasan-device-watch", daemon=True
    )
    _watch_thread.start()
