#!/usr/bin/env bash
# Installateur unique du bridge Hasan, adaptatif selon l'infrastructure Hermès
# trouvée (natif ou conteneurisé). Voir issue #12.
#
# Une commande installe tout ce dont Hasan a besoin pour dialoguer avec
# Hermès : le plugin hasan_delivery (dans l'installation Hermès existante),
# hermes-webui (si absent), le relay (conteneur) et, si aucun reverse proxy
# ne tient déjà le port 443, un Caddy.
#
# Un nom de domaine est OBLIGATOIRE : l'app Android ne fait que de la
# vérification TLS standard et refuse une adresse IP ou un certificat
# auto-signé. Il faut deux noms (un pour le relay, un pour hermes-webui),
# couverts par un certificat reconnu.
#
# Ce qu'il NE fait jamais :
#   - installer ou modifier Hermès (son installateur s'en charge) ;
#   - s'exécuter depuis l'intérieur d'un conteneur (garde-fou) ;
#   - modifier le Caddyfile ou le docker-compose.yml de l'utilisateur (il
#     affiche les blocs à ajouter) ;
#   - écraser un Caddy ou un webui tiers déjà en place.
#
# Usage :
#   sudo ./server/install.sh
#   sudo ./server/install.sh --force   # remplace un Caddyfile non géré par nous
#
# Variables d'environnement optionnelles (évitent les questions) :
#   RELAY_HOST, WEBUI_HOST   les deux noms de domaine
#   CADDY_NETWORK            réseau Docker du Caddy existant, s'il en a plusieurs
#   WEBUI_REF                version de hermes-webui (étiquette ou commit)
#   HERMES_WEBUI_PASSWORD    mot de passe hermes-webui déjà choisi
#
# Idempotent : relançable pour mettre à jour un déploiement existant.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
COMPOSE_PROJECT="hasan-bridge"
CADDY_MARKER="# managed-by: hasan-bridge docker compose"

# shellcheck source=./lib/detect.sh
source "${SCRIPT_DIR}/lib/detect.sh"
# shellcheck source=./lib/actions.sh
source "${SCRIPT_DIR}/lib/actions.sh"

FORCE=0
while [[ $# -gt 0 ]]; do
    case "$1" in
        --force) FORCE=1; shift ;;
        *) echo "Argument inconnu : $1" >&2; exit 1 ;;
    esac
done

# ─────────────────────────── 0. Garde-fous ─────────────────────────────────

# Le tout premier test, avant même de vérifier root : refuser de tourner
# dans un conteneur (Docker-dans-Docker, écritures éphémères). Voir detect.sh.
abort_if_inside_container

# Deux façons valides de lancer ce script :
#   - en root (sudo) : nécessaire si Docker doit être installé ;
#   - en utilisateur normal membre du groupe docker : suffisant quand Docker
#     est déjà là, ce qui est le cas courant sur une machine homelab. Ça évite
#     d'exiger root pour un déploiement qui n'écrit que dans le home de
#     l'utilisateur et pilote un Docker déjà accessible.
IS_ROOT=0
[[ "$(id -u)" -eq 0 ]] && IS_ROOT=1

if [[ "${IS_ROOT}" -eq 0 ]] && ! docker ps >/dev/null 2>&1; then
    echo "À lancer soit en root (sudo), soit en utilisateur du groupe docker." >&2
    echo "Ici : ni root, ni accès Docker. Ajouter l'utilisateur au groupe" >&2
    echo "docker (newgrp docker), ou relancer avec sudo." >&2
    exit 1
fi

# Résout le home et l'utilisateur cible selon le mode de lancement.
if [[ "${IS_ROOT}" -eq 1 ]] && [[ -n "${SUDO_USER:-}" ]]; then
    # Root via sudo : Hermès/webui appartiennent à l'invocateur, pas à root.
    USER_HOME="$(getent passwd "${SUDO_USER}" | cut -d: -f6)"
    RUN_AS_USER="${SUDO_USER}"
else
    # Root direct, ou utilisateur normal : on écrit dans son propre home.
    USER_HOME="${HOME}"
    RUN_AS_USER=""
fi

# Exécute une commande en tant qu'utilisateur invocateur (pas root) — pour
# tout ce qui écrit dans son home ou pilote Docker. No-op quand on n'est pas
# root (on est déjà le bon utilisateur).
as_user() {
    if [[ -n "${RUN_AS_USER}" ]]; then
        sudo -u "${RUN_AS_USER}" "$@"
    else
        "$@"
    fi
}

# systemctl --user pour l'utilisateur invocateur. Sous sudo, la session
# systemd de l'utilisateur n'est pas dans l'environnement : sans
# XDG_RUNTIME_DIR, systemctl --user échoue (« Failed to connect to bus »).
user_systemctl() {
    if [[ -n "${RUN_AS_USER}" ]]; then
        sudo -u "${RUN_AS_USER}" XDG_RUNTIME_DIR="/run/user/$(id -u "${RUN_AS_USER}")" \
            systemctl --user "$@"
    else
        systemctl --user "$@"
    fi
}

# ─────────────────────────── 1. Docker ─────────────────────────────────────

echo "==> Docker"
if ! command -v docker >/dev/null 2>&1; then
    if [[ "${IS_ROOT}" -eq 1 ]]; then
        echo "Docker absent — installation via le script officiel get.docker.com."
        curl -fsSL https://get.docker.com | sh
        systemctl enable --now docker >/dev/null 2>&1 || true
    else
        echo "Docker absent et script lancé sans root : impossible de l'installer." >&2
        echo "Relancer avec sudo pour l'installation de Docker." >&2
        exit 1
    fi
fi
if ! docker compose version >/dev/null 2>&1; then
    echo "Le plugin 'docker compose' (v2) est requis mais introuvable." >&2
    echo "Voir https://docs.docker.com/compose/install/" >&2
    exit 1
fi

# ─────────────────────────── 2. Détection Hermès ───────────────────────────

echo "==> Détection de Hermès"
read -rp "HERMES_HOME [${USER_HOME}/.hermes] : " HERMES_HOME_INPUT
HERMES_HOME="${HERMES_HOME_INPUT:-${USER_HOME}/.hermes}"

HERMES_CONTAINER=""
HERMES_MODE="$(detect_hermes_mode "${HERMES_HOME}")"
case "${HERMES_MODE}" in
    native)
        HERMES_PYTHON="$(hermes_native_python "${HERMES_HOME}")"
        if [[ -z "${HERMES_PYTHON}" ]]; then
            echo "Hermès natif trouvé dans ${HERMES_HOME}/hermes-agent, mais pas son" >&2
            echo "environnement Python (ni ${HERMES_HOME}/installs/*/facts.json, ni" >&2
            echo "hermes-agent/venv). Installation incomplète — relancer l'installateur" >&2
            echo "de Hermès, puis ce script." >&2
            exit 1
        fi
        echo "  Hermès natif détecté (Python : ${HERMES_PYTHON})."
        PLUGIN_DEST_DIR="${HERMES_HOME}"
        ;;
    container)
        HERMES_CONTAINER="$(detect_hermes_container)"
        echo "  Hermès conteneurisé détecté (conteneur « ${HERMES_CONTAINER} »)."
        PLUGIN_DEST_DIR="$(detect_hermes_volume_host_path "${HERMES_CONTAINER}")"
        if [[ -z "${PLUGIN_DEST_DIR}" ]]; then
            echo "Le conteneur Hermès n'a aucun volume monté sur /opt/data." >&2
            echo "Sans volume, le plugin serait perdu au redémarrage. Ajoutez" >&2
            echo "au compose de Hermès :  - ${HERMES_HOME}:/opt/data" >&2
            exit 1
        fi
        echo "  Volume /opt/data monté depuis : ${PLUGIN_DEST_DIR}"
        ;;
    none)
        echo "Aucune installation Hermès trouvée (ni conteneur, ni" >&2
        echo "${HERMES_HOME}/hermes-agent). Ce script n'installe PAS Hermès lui-même." >&2
        echo "Installer Hermès d'abord — natif ou conteneurisé — puis relancer." >&2
        echo "Pour le mode conteneurisé, voir server/hermes-image/ (image" >&2
        echo "dérivée qui embarque aussi hermes-webui)." >&2
        exit 1
        ;;
esac

# ─────────────────────────── 3. Noms de domaine ────────────────────────────

# Deux noms, pas un : le relay et hermes-webui servent chacun leurs chemins
# depuis la racine (/health existe des deux côtés), ils ne peuvent pas
# partager un même nom derrière le reverse proxy.
ask_domain_name() {
    local var_name="$1" prompt="$2" value="${!1:-}"
    while true; do
        if [[ -z "${value}" ]]; then
            read -rp "${prompt} : " value
        fi
        if is_valid_domain_name "${value}"; then
            printf -v "${var_name}" '%s' "${value}"
            return 0
        fi
        echo "  « ${value} » n'est pas un nom de domaine utilisable." >&2
        echo "  Attendu : un nom comme hasan.exemple.net — sans https://, sans port," >&2
        echo "  et pas une adresse IP (l'app refuse tout certificat non reconnu)." >&2
        value=""
    done
}

echo
echo "=== Noms de domaine ==="
echo "L'app exige https sous un nom de domaine avec un certificat reconnu."
ask_domain_name RELAY_HOST "Nom de domaine du relay (ex. hasan.exemple.net)"
ask_domain_name WEBUI_HOST "Nom de domaine de hermes-webui (ex. hermes.exemple.net)"
if [[ "${RELAY_HOST}" == "${WEBUI_HOST}" ]]; then
    echo "Le relay et hermes-webui doivent avoir deux noms différents." >&2
    exit 1
fi
WEBUI_URL="https://${WEBUI_HOST}"

# ─────────────────────────── 4. Reverse proxy ──────────────────────────────

# Trois topologies, qui décident de qui joint qui et par quelle adresse :
#
#   own          443 libre : on lance notre Caddy (network_mode: host), qui
#                joint le relay et webui sur 127.0.0.1.
#   reuse-host   443 tenu par un process natif ou un conteneur en réseau
#                hôte : on le réutilise, mêmes adresses 127.0.0.1.
#   reuse-bridge 443 tenu par un conteneur en réseau Docker classique : il ne
#                voit PAS 127.0.0.1 de l'hôte. Le relay rejoint son réseau
#                (joint par son nom), et webui écoute sur la passerelle de ce
#                réseau.
echo "==> Reverse proxy"
CADDYFILE="${SCRIPT_DIR}/Caddyfile"
CADDY_MODE="own"
CADDY_NETWORK="${CADDY_NETWORK:-}"
CADDY_SUBNET=""
RELAY_UPSTREAM="127.0.0.1:8767"
WEBUI_BIND_HOST="127.0.0.1"
WEBUI_UPSTREAM="127.0.0.1:8787"

CADDY_443_HOLDER="$(port_443_foreign_holder)"
CADDY_443_CONTAINER="$(port_443_foreign_container)"
if [[ -n "${CADDY_443_HOLDER}" ]]; then
    echo "  Le port 443 est déjà tenu par un tiers : ${CADDY_443_HOLDER}."
    echo "  On ne lance PAS un second Caddy (il crash-looperait sur le port"
    echo "  occupé — piège du 2026-07-28). L'existant sera réutilisé ; les"
    echo "  blocs à lui ajouter seront affichés à la fin."
    CADDY_MODE="reuse-host"

    if [[ -n "${CADDY_443_CONTAINER}" ]]; then
        mapfile -t HOLDER_NETWORKS < <(container_networks "${CADDY_443_CONTAINER}")
        if [[ "${#HOLDER_NETWORKS[@]}" -gt 0 && "${HOLDER_NETWORKS[0]}" != "host" ]]; then
            CADDY_MODE="reuse-bridge"
            if [[ -z "${CADDY_NETWORK}" ]]; then
                if [[ "${#HOLDER_NETWORKS[@]}" -eq 1 ]]; then
                    CADDY_NETWORK="${HOLDER_NETWORKS[0]}"
                else
                    echo "  Ce conteneur est sur plusieurs réseaux Docker : ${HOLDER_NETWORKS[*]}"
                    read -rp "  Lequel utiliser pour joindre le relay et hermes-webui ? [${HOLDER_NETWORKS[0]}] : " net_answer
                    CADDY_NETWORK="${net_answer:-${HOLDER_NETWORKS[0]}}"
                fi
            fi
            WEBUI_BIND_HOST="$(docker_network_gateway "${CADDY_NETWORK}")"
            if [[ -z "${WEBUI_BIND_HOST}" ]]; then
                echo "Réseau Docker « ${CADDY_NETWORK} » introuvable ou sans passerelle IPv4." >&2
                exit 1
            fi
            CADDY_SUBNET="$(docker_network_subnet "${CADDY_NETWORK}")"
            RELAY_UPSTREAM="hasan-relay:8767"
            WEBUI_UPSTREAM="${WEBUI_BIND_HOST}:8787"
            echo "  Il est en réseau Docker classique (« ${CADDY_NETWORK} ») : il ne joint pas"
            echo "  127.0.0.1 de l'hôte. Le relay rejoindra ce réseau, et hermes-webui"
            echo "  écoutera sur sa passerelle (${WEBUI_BIND_HOST})."
        fi
    fi
else
    echo "  Port 443 libre — un Caddy sera lancé par cet installateur."
fi
# Hors reuse-bridge, un CADDY_NETWORK fourni par l'environnement n'a pas de sens.
[[ "${CADDY_MODE}" == "reuse-bridge" ]] || CADDY_NETWORK=""

# ─────────────────────────── 5. hermes-webui ───────────────────────────────

echo "==> hermes-webui (écran Chat de l'app)"
if [[ "${HERMES_MODE}" == "container" ]]; then
    # En conteneurisé, la question n'est pas "webui répond-il ?" (il peut être
    # lent à démarrer) mais "est-il dans l'image ?". S'il n'y est pas, l'image
    # nue a été utilisée et webui ne peut pas être ajouté après coup.
    if webui_embedded_in_container "${HERMES_CONTAINER}"; then
        echo "  hermes-webui embarqué dans l'image — rien à installer."
        # Vu d'un Caddy en réseau Docker, webui se joint par le nom du
        # conteneur Hermès, pas par l'hôte.
        [[ "${CADDY_MODE}" == "reuse-bridge" ]] && WEBUI_UPSTREAM="${HERMES_CONTAINER}:8787"
    else
        webui_container_missing_abort
    fi
elif webui_is_running "${WEBUI_BIND_HOST}"; then
    echo "  hermes-webui répond déjà sur ${WEBUI_BIND_HOST}:8787 — rien à installer."
else
    # Hermès natif : webui tourne à côté, avec le Python de Hermès.
    echo "  hermes-webui ne répond pas sur ${WEBUI_BIND_HOST}:8787 — installation."
    install_webui_native "${HERMES_HOME}" "${USER_HOME}" "${WEBUI_BIND_HOST}" "${SCRIPT_DIR}/webui"
fi

# Mot de passe hermes-webui — porté dans le QR de pairing pour que l'app se
# connecte au Chat automatiquement. Résolu sans prompt quand c'est possible
# (voir resolve_webui_password). Vide = pas de login auto (l'utilisateur le
# saisira dans l'app).
WEBUI_PASSWORD="$(resolve_webui_password "${USER_HOME}" "${HERMES_MODE}" "${HERMES_CONTAINER}")"

# ─────────────────────────── 6. Secrets + .env ─────────────────────────────

echo "==> Secrets"
ENV_FILE="${SCRIPT_DIR}/.env"
RELAY_ADMIN_TOKEN="$(read_env_var "${ENV_FILE}" RELAY_ADMIN_TOKEN)"
if [[ -z "${RELAY_ADMIN_TOKEN}" ]]; then
    RELAY_ADMIN_TOKEN="$(openssl rand -hex 32)"
    echo "  Nouveau RELAY_ADMIN_TOKEN généré."
else
    echo "  RELAY_ADMIN_TOKEN existant conservé."
fi

write_env_file "${ENV_FILE}" "${RELAY_ADMIN_TOKEN}" "${RELAY_HOST}" "${WEBUI_URL}" "${WEBUI_PASSWORD}"

# ─────────────────────────── 7. Fichiers compose/Caddy ─────────────────────

NETWORK_OVERRIDE="${SCRIPT_DIR}/docker-compose.network.yml"
write_network_override "${NETWORK_OVERRIDE}" "${CADDY_NETWORK}"

if [[ "${CADDY_MODE}" == "own" ]]; then
    render_caddyfile "${SCRIPT_DIR}/Caddyfile.template" "${CADDYFILE}" "${FORCE}" "${CADDY_MARKER}" \
        "${RELAY_HOST}" "${RELAY_UPSTREAM}" "${WEBUI_HOST}" "${WEBUI_UPSTREAM}"
fi

# ─────────────────────────── 8. Conteneurs ─────────────────────────────────

COMPOSE_ARGS=(-p "${COMPOSE_PROJECT}" -f "${SCRIPT_DIR}/docker-compose.yml")
[[ -f "${NETWORK_OVERRIDE}" ]] && COMPOSE_ARGS+=(-f "${NETWORK_OVERRIDE}")
COMPOSE_SERVICES=(relay)
if [[ "${CADDY_MODE}" == "own" ]]; then
    COMPOSE_ARGS+=(--profile caddy)
    COMPOSE_SERVICES+=(caddy)
fi

echo "==> Lancement des conteneurs (${COMPOSE_SERVICES[*]})"
( cd "${SCRIPT_DIR}" && docker compose "${COMPOSE_ARGS[@]}" up -d --build "${COMPOSE_SERVICES[@]}" )

echo "==> Vérification"
sleep 2
if ! curl -sf --max-time 5 http://127.0.0.1:8767/health >/dev/null; then
    echo "  AVERTISSEMENT : le relay ne répond pas encore sur 127.0.0.1:8767." >&2
    echo "  Voir : docker compose -p ${COMPOSE_PROJECT} logs relay" >&2
fi

# ─────────────────────────── 9. Plugin ─────────────────────────────────────

echo "==> Dépôt du plugin hasan_delivery dans ${PLUGIN_DEST_DIR}"
install_plugin "${REPO_ROOT}/plugin/hasan_delivery" "${PLUGIN_DEST_DIR}" "${HERMES_MODE}" "${HERMES_HOME}"
if [[ "${HERMES_MODE}" == "native" ]]; then
    configure_plugin_native "${HERMES_HOME}" "${RELAY_ADMIN_TOKEN}"
    enable_plugin_native "${USER_HOME}"
fi

# ─────────────────────────── 10. Redémarrage Hermès ────────────────────────

echo "==> Redémarrage de Hermès pour charger le plugin"
restart_hermes "${HERMES_MODE}" "${HERMES_CONTAINER}"

# ─────────────────────────── 11. Pairing + résumé ──────────────────────────

echo "==> Code de pairing"
sleep 1
PAIRING_JSON="$(curl -sf --max-time 5 -X POST "http://127.0.0.1:8767/pairing/create" \
    -H "Authorization: Bearer ${RELAY_ADMIN_TOKEN}" || true)"

print_summary
