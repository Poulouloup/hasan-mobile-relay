#!/usr/bin/env bash
# Détection de l'infrastructure existante, sourcé par install.sh.
#
# Isolé dans son propre fichier pour être testable seul (voir
# server/lib/test-detect.sh et server/lib/selftest.sh) : ces fonctions
# n'écrivent rien, ne demandent rien à l'utilisateur et n'ont aucun effet de
# bord — elles répondent à des questions sur la machine.
#
# Le mode conteneurisé a été vérifié sur nousresearch/hermes-agent:latest
# (issue #12). Le mode natif a été réaligné sur une installation réelle de
# Hermès v0.21.6 faite par l'installateur officiel (homelab, 2026-10-12).

# ─────────────────────── Suis-je dans un conteneur ? ────────────────────────

# /.dockerenv est le seul test fiable : présent dans un conteneur Docker,
# absent sur l'hôte (vérifié dans les deux sens). La méthode alternative
# souvent citée — chercher "docker" dans /proc/1/cgroup — a été testée et
# NE FONCTIONNE PAS sur Docker moderne (cgroup v2) : elle renvoie "absent"
# depuis l'intérieur d'un conteneur, donc un faux négatif silencieux.
running_inside_container() {
    [[ -f /.dockerenv ]]
}

# Garde-fou : ce script installe Docker et lance des conteneurs. Exécuté
# DEPUIS un conteneur (typiquement si on demande à Hermes conteneurisé
# d'installer Hasan lui-même), il tenterait un Docker-dans-Docker et
# n'écrirait que dans des couches éphémères, perdues au prochain
# `docker compose up`.
abort_if_inside_container() {
    if running_inside_container; then
        cat >&2 <<'EOF'
Ce script s'exécute à l'intérieur d'un conteneur.

Il doit tourner sur la MACHINE HÔTE : il installe Docker au besoin et lance
des conteneurs. Depuis l'intérieur, il installerait Docker dans Docker, et
tout ce qu'il écrirait hors des volumes disparaîtrait au prochain
redémarrage du conteneur.

À faire : ouvrir un shell sur l'hôte et relancer ce script là-bas.
EOF
        exit 1
    fi
}

# ─────────────────────── Nom de domaine ─────────────────────────────────────

# L'app ne fait plus que de la vérification TLS standard : il lui faut un nom
# de domaine couvert par un certificat reconnu. Une adresse IP est refusée
# (aucune autorité publique n'en délivre d'utilisable ici), de même qu'un nom
# sans point ("localhost", "serveur") qui ne peut pas porter de certificat
# public. On refuse aussi schéma, port et chemin : on attend le nom seul.
is_valid_domain_name() {
    local name="$1"
    [[ -n "${name}" ]] || return 1
    [[ "${name}" =~ ^[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?$ ]] || return 1
    [[ "${name}" == *.* ]] || return 1
    [[ "${name}" != *..* ]] || return 1
    # Tout en chiffres et points = adresse IPv4.
    [[ "${name}" =~ ^[0-9.]+$ ]] && return 1
    return 0
}

# ─────────────────────── Hermes : natif ou conteneurisé ? ───────────────────

# Motifs d'image reconnus comme "un Hermès" :
#   - nousresearch/hermes-agent : l'image officielle (Docker Hub + GHCR) ;
#   - hasan-hermes : notre image dérivée (server/hermes-image/), qui embarque
#     hermes-webui. Sans ce second motif, un déploiement homelab utilisant
#     l'image dérivée serait vu comme "pas de Hermès" (bug rencontré au test).
HERMES_IMAGE_PATTERNS=("nousresearch/hermes-agent" "hasan-hermes")

# Renvoie le nom du conteneur Hermes en cours d'exécution, ou une chaîne vide.
# Cherche par image plutôt que par nom de conteneur : le nom est libre
# (chacun nomme son service comme il veut dans son compose), l'image ne l'est
# pas. --filter ancestor exige le nom exact du tag, donc on liste les
# conteneurs et on filtre l'image nous-mêmes pour matcher un préfixe
# (l'utilisateur peut tagger hasan-hermes:latest, :local, :v2…).
detect_hermes_container() {
    command -v docker >/dev/null 2>&1 || return 0
    local name image
    while IFS='|' read -r name image; do
        [[ -z "${name}" ]] && continue
        for pat in "${HERMES_IMAGE_PATTERNS[@]}"; do
            if [[ "${image}" == "${pat}"* || "${image}" == *"/${pat}"* ]]; then
                echo "${name}"
                return 0
            fi
        done
    done < <(docker ps --format '{{.Names}}|{{.Image}}' 2>/dev/null)
}

# Un Hermès natif est-il installé dans ce HERMES_HOME ?
#
# On reconnaît le CODE (hermes-agent/run_agent.py), pas un venv : l'ancien
# test — hermes-agent/venv/bin/python — ne trouvait plus rien, l'installateur
# officiel ayant déplacé l'environnement Python hors de hermes-agent (voir
# hermes_native_python). L'ancien chemin reste accepté pour les installations
# antérieures.
hermes_native_installed() {
    local hermes_home="$1"
    [[ -f "${hermes_home}/hermes-agent/run_agent.py" ]] \
        || [[ -x "${hermes_home}/hermes-agent/venv/bin/python" ]]
}

# Mode d'installation de Hermes : "container", "native" ou "none".
#
# L'ordre compte : on teste le conteneur AVANT le natif, car une machine peut
# porter les deux (une ancienne installation native laissée en place à côté
# d'un conteneur devenu la vraie instance). Le conteneur en cours d'exécution
# est la source de vérité — c'est lui qui tourne.
detect_hermes_mode() {
    local hermes_home="$1"

    if [[ -n "$(detect_hermes_container)" ]]; then
        echo "container"
        return 0
    fi
    if hermes_native_installed "${hermes_home}"; then
        echo "native"
        return 0
    fi
    echo "none"
}

# Chemin de l'environnement Python lu dans un facts.json de l'installateur
# Hermès (clé packages.venv.environment), ou vide. python3 si disponible,
# sinon une extraction textuelle suffisante pour ce fichier généré.
_facts_venv_dir() {
    local facts="$1"
    if command -v python3 >/dev/null 2>&1; then
        python3 - "${facts}" 2>/dev/null <<'EOF'
import json, sys
try:
    print(json.load(open(sys.argv[1]))["packages"]["venv"]["environment"])
except Exception:
    pass
EOF
    else
        grep -o '"environment"[[:space:]]*:[[:space:]]*"[^"]*"' "${facts}" 2>/dev/null \
            | head -n1 | sed 's/.*:[[:space:]]*"\(.*\)"$/\1/'
    fi
}

# Python de l'environnement d'un Hermès natif, ou vide s'il est introuvable.
#
# L'installateur officiel actuel crée un environnement géré dans
# ~/.hermes/installs/<id>/environments/<empreinte>/venv/, dont le chemin
# CHANGE à chaque `hermes update` : il ne faut donc jamais le figer dans une
# config, mais le relire dans installs/<id>/facts.json (le plus récent
# d'abord). Les installations plus anciennes ont un venv classique dans
# hermes-agent/venv.
hermes_native_python() {
    local hermes_home="$1" facts dir
    while IFS= read -r facts; do
        [[ -n "${facts}" ]] || continue
        dir="$(_facts_venv_dir "${facts}")"
        if [[ -n "${dir}" && -x "${dir}/bin/python" ]]; then
            echo "${dir}/bin/python"
            return 0
        fi
    done < <(ls -t "${hermes_home}"/installs/*/facts.json 2>/dev/null)

    if [[ -x "${hermes_home}/hermes-agent/venv/bin/python" ]]; then
        echo "${hermes_home}/hermes-agent/venv/bin/python"
    fi
    return 0
}

# ─────────────────────── Modules Python disponibles ─────────────────────────

# Vérifié sur l'image officielle : httpx est déjà présent dans le venv
# /opt/hermes/.venv. On vérifie quand même plutôt que de le supposer : une
# future image amont pourrait cesser de l'embarquer, et il vaut mieux un
# message clair qu'un plugin qui refuse de charger sans explication.
hermes_container_has_httpx() {
    local container="$1"
    docker exec "${container}" python -c 'import httpx' >/dev/null 2>&1
}

python_has_module() {
    local python="$1" module="$2"
    [[ -n "${python}" ]] && "${python}" -c "import ${module}" >/dev/null 2>&1
}

# L'environnement géré de Hermès (uv, verrouillé) n'a PAS pip : tout
# `python -m pip install` y échoue. On teste avant d'essayer.
python_has_pip() {
    local python="$1"
    [[ -n "${python}" ]] && "${python}" -m pip --version >/dev/null 2>&1
}

# ─────────────────────── hermes-webui ───────────────────────────────────────

# hermes-webui répond-il sur cette adresse d'écoute ? C'est lui qui alimente
# l'écran Chat de l'app. Projet tiers (github.com/nesquena/hermes-webui),
# installé séparément de Hermes. L'adresse n'est pas toujours 127.0.0.1 :
# derrière un Caddy en réseau Docker, webui écoute sur la passerelle de ce
# réseau (voir docker_network_gateway).
webui_is_running() {
    local host="${1:-127.0.0.1}"
    curl -sf -o /dev/null --max-time 3 "http://${host}:8787/health" 2>/dev/null
}

# webui est-il EMBARQUÉ dans l'image du conteneur Hermes ?
#
# Distinct de "répond-il ?" : au démarrage, webui met ~15-20 s à répondre
# (s6 le lance après main-hermes). Sans cette distinction, un webui simplement
# lent serait pris pour une image nue et l'installation refusée à tort. On
# regarde donc si le CODE est présent (/opt/hermes/hermes-webui), ce qui
# tranche entre l'image dérivée (présent) et l'image officielle nue (absent),
# indépendamment de l'état de démarrage.
webui_embedded_in_container() {
    local container="$1"
    docker exec "${container}" sh -c 'test -d /opt/hermes/hermes-webui' >/dev/null 2>&1
}

# Un dépôt hermes-webui est-il déjà cloné, même s'il ne tourne pas ?
webui_is_installed() {
    local user_home="$1"
    [[ -d "${user_home}/hermes-webui/.git" ]]
}

# ─────────────────────── Port 443 : qui le tient, et comment ? ──────────────

# Conteneur tiers publiant le port 443, ou vide. "Tiers" = hors du projet
# compose hasan-bridge.
port_443_foreign_container() {
    command -v docker >/dev/null 2>&1 || return 0
    docker ps --format '{{.Names}}\t{{.Ports}}' 2>/dev/null \
        | awk -F'\t' '$2 ~ /:443->/ {print $1}' \
        | grep -v '^hasan-bridge-' | head -n1
    return 0
}

# Le port 443 est-il tenu par quelque chose QUI N'EST PAS À NOUS ?
#
# C'est la vraie question, plus large que "un Caddy tourne-t-il ?" : n'importe
# quel service tiers (un autre reverse-proxy, un conteneur du homelab) peut
# déjà occuper 443. S'il est occupé par un tiers, on ne doit PAS lancer notre
# Caddy — il entrerait en conflit et crash-looperait (bug rencontré au test :
# hasan-bridge-caddy en Restarting derrière un caddy-perso). On réutilise
# l'existant à la place.
#
# Renvoie "conteneur <nom>" ou "process <nom>", ou vide si 443 est libre ou
# tenu par nous.
port_443_foreign_holder() {
    local holder
    holder="$(port_443_foreign_container)"
    if [[ -n "${holder}" ]]; then
        echo "conteneur ${holder}"
        return 0
    fi
    # Un conteneur en network_mode: host ne « publie » rien : il apparaît
    # comme un process natif, tout comme un Caddy systemd. Le nôtre aussi —
    # on l'écarte pour qu'une relance de l'installateur ne le prenne pas pour
    # un tiers.
    if own_caddy_is_running; then
        return 0
    fi
    _port_holder_native 443
}

# Notre propre Caddy (service compose hasan-bridge) tourne-t-il ?
own_caddy_is_running() {
    command -v docker >/dev/null 2>&1 || return 1
    docker ps --format '{{.Names}}' 2>/dev/null | grep -q '^hasan-bridge-caddy'
}

# Détenteur natif d'un port (hors Docker), ou vide.
_port_holder_native() {
    local port="$1" h
    command -v ss >/dev/null 2>&1 || return 0
    h="$(ss -ltnp "sport = :${port}" 2>/dev/null | awk 'NR==2 {print $NF}')"
    # `[[ -n ... ]] && echo` en fin de fonction renverrait le code du test (1
    # si vide), fatal sous `set -e` chez l'appelant. On sépare pour toujours
    # sortir 0.
    if [[ -n "${h}" ]]; then
        echo "process ${h}"
    fi
    return 0
}

# Description de ce qui occupe un port, ou chaîne vide s'il est libre.
# Sert à nommer le coupable dans les messages plutôt que de dire
# "port occupé" sans dire par qui.
port_holder() {
    local port="$1"
    command -v ss >/dev/null 2>&1 || return 0
    ss -ltnp "sport = :${port}" 2>/dev/null | awk 'NR==2 {print $NF}'
}

# Réseaux Docker auxquels un conteneur est attaché, un par ligne. Renvoie
# "host" seul pour un conteneur en network_mode: host.
#
# La distinction décide de tout le routage : un Caddy en réseau Docker
# classique NE PEUT PAS joindre 127.0.0.1 de l'hôte (c'est le sien). Il faut
# alors que le relay soit sur son réseau, et que webui écoute sur la
# passerelle de ce réseau.
container_networks() {
    local container="$1"
    docker inspect "${container}" \
        --format '{{range $name, $_ := .NetworkSettings.Networks}}{{$name}}{{"\n"}}{{end}}' \
        2>/dev/null | sed '/^$/d'
}

# Adresse IPv4 de la passerelle d'un réseau Docker = l'adresse de l'hôte vue
# depuis les conteneurs de ce réseau. `host.docker.internal:host-gateway` ne
# convient pas : il désigne la passerelle du réseau Docker PAR DÉFAUT, pas
# celle du réseau du Caddy (constaté sur le homelab).
docker_network_gateway() {
    local network="$1"
    docker network inspect "${network}" \
        --format '{{range .IPAM.Config}}{{.Gateway}}{{"\n"}}{{end}}' 2>/dev/null \
        | grep -E '^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$' | head -n1
}

# Sous-réseau IPv4 d'un réseau Docker (pour la règle de pare-feu à afficher).
docker_network_subnet() {
    local network="$1"
    docker network inspect "${network}" \
        --format '{{range .IPAM.Config}}{{.Subnet}}{{"\n"}}{{end}}' 2>/dev/null \
        | grep -E '^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+/[0-9]+$' | head -n1
}

caddyfile_is_ours() {
    local caddyfile="$1" marker="$2"
    [[ -f "${caddyfile}" ]] && head -n1 "${caddyfile}" | grep -qF "${marker}"
}

# ─────────────────────── Chemin du volume Hermes ────────────────────────────

# Où déposer le plugin, selon le mode.
#
# Cas conteneurisé : l'image officielle définit HERMES_HOME=/opt/data, monté
# depuis l'hôte (vérifié). Le dossier plugins/ que Hermes scanne au démarrage
# vit donc dans le volume — y écrire depuis l'hôte revient EXACTEMENT au cas
# natif. C'est ce qui permet un seul chemin de code pour les deux modes.
#
# On résout le point de montage réel plutôt que de supposer ~/.hermes :
# l'utilisateur est libre de monter n'importe quel dossier hôte sur
# /opt/data.
detect_hermes_volume_host_path() {
    local container="$1"
    docker inspect "${container}" \
        --format '{{range .Mounts}}{{if eq .Destination "/opt/data"}}{{.Source}}{{end}}{{end}}' \
        2>/dev/null
}
