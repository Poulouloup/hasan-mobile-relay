# hasan_delivery — plugin Hermes

Relie l'agent Hermes à l'app compagnon Android [Hasan](../../README.md), via
le [relay server](../../server/relay/) auto-hébergé. Ne parle à aucune
plateforme tierce directement : il passe par HTTP contre le relay, qui
lui-même tient la connexion WebSocket avec le téléphone.

Deux choses distinctes dans le même plugin (voir [Portée](#portée)) : un canal
de messagerie vers un téléphone, et les actions sur le téléphone exposées à
Hermes comme des outils.

## Prérequis

- Une instance Hermes déjà installée et fonctionnelle sur le serveur.
- Le [relay server](../../server/relay/) déployé et joignable depuis ce
  serveur (voir [`install-relay.sh`](../../server/relay/install-relay.sh) ou
  le [guide de déploiement complet](../../DEPLOYMENT.md)).

## Installation

`server/install.sh` (voir [`DEPLOYMENT.md`](../../DEPLOYMENT.md)) installe,
configure et active le plugin avec le reste du bridge. Pour le plugin seul,
sur un Hermes natif :

```bash
./plugin/hasan_delivery/install-plugin.sh
```

Le script copie le plugin dans `${HERMES_HOME:-~/.hermes}/plugins/hasan_delivery`,
vérifie sa seule dépendance (`httpx`, déjà fournie par Hermes) et l'active
(`hermes plugins enable hasan_delivery` — les plugins tiers sont désactivés
par défaut dans les Hermes récents). Il ne lance `pip` que si `httpx` manque
et que l'environnement de Hermes a `pip` : l'environnement géré des
installations actuelles n'en a pas.

## Configuration

Le plugin lit ces variables d'environnement (voir aussi
[`plugin.yaml`](plugin.yaml)) :

| Variable | Requis | Description |
|---|---|---|
| `HASAN_RELAY_URL` | oui | Adresse du relay vue depuis Hermes — en général l'adresse locale `http://127.0.0.1:8767`, pas l'adresse publique `https://` qu'utilise le téléphone |
| `HASAN_RELAY_ADMIN_TOKEN` | oui | Jeton d'administration du relay (`RELAY_ADMIN_TOKEN`). Sert à tout ce que fait le plugin : messagerie, outils du téléphone, codes de pairing |
| `HASAN_RELAY_DEVICE_ID` | non | `device_hash` du téléphone qui reçoit les messages (voir `GET /devices`). Utile seulement si plusieurs appareils sont appairés ; par défaut, c'est l'appareil appairé vu le plus récemment |
| `HASAN_PHONE_ENABLED` | non | `false` pour désactiver le canal sans supprimer la config |
| `HASAN_RELAY_SESSION_TOKEN` | non | **Obsolète.** N'est lu que si `HASAN_RELAY_ADMIN_TOKEN` est absent (relay antérieur à 0.2.0) |

**Pourquoi le jeton d'administration et plus un jeton de session.** Le jeton
de session d'un téléphone est remplacé à chaque renouvellement de session
(`POST /pairing/refresh`, que l'app déclenche toute seule). Une copie gardée
dans la configuration du plugin cessait donc de fonctionner sans prévenir, et
la messagerie tombait en 401 jusqu'à ce qu'on recopie le nouveau jeton à la
main. Le jeton d'administration ne change pas ; le plugin n'a plus rien à
récupérer après un appairage, et il se connecte même avant le premier. Il faut
pour cela un relay en version 0.2.0 ou plus (`GET /version`).

Si le relay est configuré avec `RELAY_FCM_CREDENTIALS_PATH` (voir
[`DEPLOYMENT.md`](../../DEPLOYMENT.md)), `POST /phone/message` déclenche
aussi un réveil FCM data-only quand le device n'a pas de WebSocket actif —
comportement transparent côté plugin, aucune variable/config supplémentaire
ici (voir `docs/ARCHITECTURE.md` pour le flux complet).

À définir dans l'environnement du service Hermes (`~/.hermes/.env` — c'est là
que `server/install.sh` les écrit — ou l'unit systemd du gateway), ou dans `config.yaml` sous `platforms.hasan_delivery.extra`
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
- `HASAN_RELAY_ADMIN_TOKEN non configuré` → la variable manque dans
  l'environnement du gateway.
- `Authentification refusée (401)` → `HASAN_RELAY_ADMIN_TOKEN` ne correspond
  pas au `RELAY_ADMIN_TOKEN` du relay, ou le relay est antérieur à 0.2.0 et
  n'accepte pas ce jeton pour la messagerie (le mettre à jour).
- Un envoi qui échoue en `HTTP 409: no_paired_device` → aucun téléphone
  n'est encore appairé (scanner le QR depuis l'app).

Si rien n'apparaît du tout dans les logs : vérifier que le plugin est activé
(`hermes plugins list`).

## Portée

Le plugin enregistre deux choses auprès de Hermes :

- **Un canal de messagerie texte** ([`adapter.py`](adapter.py)) : Hermes
  envoie un message au téléphone (notification), vers un seul appareil.
- **Les actions sur le téléphone** ([`tools.py`](tools.py)) : SMS, batterie,
  localisation, etc., exposées comme des outils natifs de Hermes (toolset
  `hasan_phone`). La liste n'est pas codée ici : elle vient de ce que chaque
  app annonce au relay, et couvre tous les appareils connectés. Ces outils
  appellent `POST /bridge/command` sur le relay ; la confirmation des actions
  sensibles se fait dans l'app.

Voir `docs/ARCHITECTURE.md` (section « Serveur relay + plugin
hasan_delivery ») et le skill `hasan-plugin-architecture`.
