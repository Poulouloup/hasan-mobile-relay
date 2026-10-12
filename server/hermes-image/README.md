# Image Hermès dérivée (Hermès + hermes-webui)

Cette image ajoute **hermes-webui** — l'interface de chat qui alimente
l'écran principal de l'app — à l'image officielle `nousresearch/hermes-agent`,
et la fait tourner comme un service supervisé aux côtés de Hermès.

Elle n'est nécessaire **que si Hermès tourne en conteneur**. En Hermès natif,
webui s'installe à côté, avec le Python de Hermès (`server/install.sh` s'en charge).

## Pourquoi une image dérivée plutôt qu'un conteneur webui séparé

hermes-webui **n'est pas un service autonome** : il importe massivement le
code interne de Hermès (`from agent.*`, `from hermes_cli.*` — plus de 100
imports). Il lui faut donc le venv **et** le code source de Hermès sur son
chemin d'import, tous deux enfermés dans l'image du conteneur Hermès.

Trois conséquences :

- On **ne peut pas** le faire tourner « à côté » dans son propre conteneur
  sans dupliquer/monter le code de Hermès — un couplage fragile qui diverge à
  chaque mise à jour amont.
- On **ne peut pas** le lancer via `docker exec` de façon durable : ce
  process ne serait pas supervisé et disparaîtrait au premier redémarrage du
  conteneur.
- La solution propre est de le **graver dans l'image** comme service
  [s6-overlay](https://github.com/just-containers/s6-overlay), exactement au
  même titre que les services `main-hermes` et `dashboard` déjà présents dans
  l'image officielle.

**Ce n'est pas un fork.** webui est cloné depuis l'amont
(`nesquena/hermes-webui`, à une version épinglée) et seulement *placé* dans l'image.
Aucun code tiers n'est modifié ni maintenu. À chaque mise à jour, il suffit de
reconstruire — l'image de base fait tout le gros œuvre.

## Construire l'image

```bash
docker build -t hasan-hermes server/hermes-image
```

Puis, dans votre `docker-compose.yml` Hermès, pointez le service sur cette
image au lieu de l'officielle :

```yaml
services:
  hermes:
    image: hasan-hermes          # au lieu de nousresearch/hermes-agent
    command: gateway run          # requis : la commande par défaut lance le
                                   # TUI interactif, qui sort sans terminal et
                                   # fait tomber tout le conteneur
    volumes:
      - ~/.hermes:/opt/data        # obligatoire — persiste config, sessions,
                                   # plugins ; sans lui, tout est perdu au
                                   # redémarrage
    restart: unless-stopped
```

Puis `docker compose up -d`, et enfin `sudo ./server/install.sh` pour le reste
du bridge (relay, Caddy, plugin).

### Version de webui

L'image fige webui sur une étiquette amont (`WEBUI_REF`, voir le
`Dockerfile`), pas sur la tête de `master` : reconstruire redonne la même
image. webui se met à jour **en même temps que Hermès** (son README le
demande) ; l'étiquette par défaut date du même jour que Hermès v0.21.6. Avec
une autre image de base, choisir l'étiquette `exp-v0.52.NNN` de la même date :

```bash
docker build --build-arg WEBUI_REF=<étiquette> -t hasan-hermes server/hermes-image
```

## Ce que l'image contient

| Ajout | Où | Rebuild nécessaire pour changer ? |
|---|---|---|
| hermes-webui (version épinglée) | `/opt/hermes/hermes-webui` | oui — c'est du **programme** |
| ses dépendances (`pyyaml`, `cryptography`) | venv `/opt/hermes/.venv` (via `uv pip`) | oui |
| service s6 `hasan-webui` | `/etc/s6-overlay/s6-rc.d/` | oui |

Le **plugin** `hasan_delivery`, lui, n'est **pas** dans l'image : il vit dans
le volume `/opt/data/plugins/` (déposé par `install.sh`), donc modifiable sans
rebuild — comme les données de Hermès.

## Le service webui, en pratique

- Écoute sur `:8787` (`HERMES_WEBUI_PORT`), bind `0.0.0.0` par défaut
  (`HASAN_WEBUI_HOST`) — dans un conteneur, `127.0.0.1` ne serait pas
  joignable depuis l'extérieur.
- Tourne en tant qu'utilisateur `hermes` (uid 10000), comme les autres
  services — pas root.
- Supervisé : s'il crashe, s6 le relance ; il redémarre avec le conteneur.
- Désactivable sans rebuild via `HASAN_WEBUI=false` dans l'environnement du
  conteneur (le service sort proprement, s6 laisse le slot « down »).

Vérifié de bout en bout sur machine de test : build, réponse HTTP 200,
relance après crash (< 8 s), et survie à un redémarrage complet du conteneur.
