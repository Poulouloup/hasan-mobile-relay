# hasan_delivery — plugin Hermes

Canal de messagerie qui relie l'agent Hermes à l'app compagnon Android
[Hasan](../../README.md), via le [relay server](../../server/relay/)
auto-hébergé. Ne parle à aucune plateforme tierce directement : il envoie/reçoit
via HTTP long-poll contre le relay, qui lui-même tient la connexion WebSocket
avec le téléphone.

Un seul device par installation (pas de multi-tenant) — voir
[`adapter.py`](adapter.py) pour le détail du protocole.

## Prérequis

- Une instance Hermes déjà installée et fonctionnelle sur le serveur.
- Le [relay server](../../server/relay/) déployé et joignable depuis ce
  serveur (voir [`install-relay.sh`](../../server/relay/install-relay.sh) ou
  le [guide de déploiement complet](../../DEPLOYMENT.md)).

## Installation

Manuelle :

```bash
# 1. Copier ce dossier dans les plugins Hermes
cp -r plugin/hasan_delivery "${HERMES_HOME:-$HOME/.hermes}/plugins/hasan_delivery"

# 2. Installer sa dépendance (httpx) dans le venv de l'agent Hermes
"${HERMES_HOME:-$HOME/.hermes}/hermes-agent/venv/bin/pip" install -r \
    "${HERMES_HOME:-$HOME/.hermes}/plugins/hasan_delivery/requirements.txt"
```

Ou via le script fourni, qui fait exactement ça :

```bash
./plugin/hasan_delivery/install-plugin.sh
```

## Configuration

Le plugin lit ces variables d'environnement (voir aussi
[`plugin.yaml`](plugin.yaml)) :

| Variable | Requis | Description |
|---|---|---|
| `HASAN_RELAY_URL` | oui | URL de base du relay server, ex. `https://relay.example.com` |
| `HASAN_RELAY_SESSION_TOKEN` | oui | Token de session d'un device déjà appairé (obtenu via `/pairing/register` — voir le flux de pairing QR dans l'app) |
| `HASAN_RELAY_ADMIN_TOKEN` | non | Token admin pour générer des codes de pairing (`/pairing/create`) |
| `HASAN_PHONE_ENABLED` | non | `false` pour désactiver le canal sans supprimer la config |

À définir dans l'environnement du service Hermes (ex. `~/.hermes/hermes-agent/.env`
ou l'unit systemd du gateway), ou dans `config.yaml` sous `platforms.hasan_delivery.extra`
(voir le docstring en tête d'[`adapter.py`](adapter.py) pour l'exemple complet).

Redémarrer le gateway après configuration :

```bash
hermes gateway restart
```

## Vérifier que ça marche

`hermes gateway status`/`hermes gateway list` ne détaillent pas les canaux
individuels — le signal fiable est dans les logs du service, préfixés
`hermes_plugins.hasan_delivery.adapter` :

```bash
journalctl --user -u hermes-gateway.service -f | grep -i hasan_delivery
```

- `[Hasan_Delivery] Connecté — long-poll sur <url>/phone/replies` → OK.
- `Relay server injoignable sur <url>` → `HASAN_RELAY_URL` est mal configuré ou
  le relay ne tourne pas/n'est pas joignable depuis ce serveur.
- `HASAN_RELAY_SESSION_TOKEN non configuré` → le device n'est pas encore
  appairé (scanner le QR depuis l'app).
- `Authentification refusée (401)` → le token de session a été révoqué côté
  relay ; ré-appairer le device.

## Portée

Ce plugin ne couvre que le canal de messagerie texte (`send`/`receive`). Le
contrôle d'écran du téléphone (`plugin/tools/android_tool.py`) est un sujet
distinct, volontairement non intégré — voir la note en tête de ce fichier
dans le repo.
