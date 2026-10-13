#!/usr/bin/env bash
# Tests des fonctions de server/lib/ qui ne dépendent pas d'une vraie machine
# cible : validation des noms de domaine, détection d'un Hermès natif dans une
# arborescence factice, fichiers générés. Docker, ss et systemd sont remplacés
# par des fonctions de substitution — rien n'est installé ni lancé.
#
# Usage :
#   bash server/lib/selftest.sh
#
# Ne remplace pas test-detect.sh (diagnostic sur la machine cible) ni une
# installation réelle : il attrape les régressions de logique, pas les écarts
# avec une infrastructure qu'il ne voit pas.

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=./detect.sh
source "${SCRIPT_DIR}/detect.sh"
# shellcheck source=./actions.sh
source "${SCRIPT_DIR}/actions.sh"

# install.sh les définit ; ici on est déjà le bon utilisateur.
as_user() { "$@"; }

TMP="$(mktemp -d)"
trap 'rm -rf "${TMP}"' EXIT

PASS=0
FAIL=0
check() {
    local label="$1"; shift
    if "$@"; then
        PASS=$((PASS + 1))
    else
        FAIL=$((FAIL + 1))
        printf '  \033[31m✗\033[0m %s\n' "${label}"
    fi
}
check_eq() {
    local label="$1" expected="$2" actual="$3"
    if [[ "${expected}" == "${actual}" ]]; then
        PASS=$((PASS + 1))
    else
        FAIL=$((FAIL + 1))
        printf '  \033[31m✗\033[0m %s\n      attendu : %s\n      obtenu  : %s\n' "${label}" "${expected}" "${actual}"
    fi
}
not() { ! "$@"; }

# ── Noms de domaine ─────────────────────────────────────────────────────────
echo "Noms de domaine"
check "nom simple accepté"            is_valid_domain_name "hasan.exemple.net"
check "sous-domaines acceptés"        is_valid_domain_name "hasan.maison.dynv6.net"
check "adresse IPv4 refusée"          not is_valid_domain_name "192.168.1.10"
check "adresse IPv6 refusée"          not is_valid_domain_name "fe80::1"
check "nom sans point refusé"         not is_valid_domain_name "localhost"
check "schéma refusé"                 not is_valid_domain_name "https://hasan.exemple.net"
check "port refusé"                   not is_valid_domain_name "hasan.exemple.net:8767"
check "chemin refusé"                 not is_valid_domain_name "hasan.exemple.net/ws"
check "vide refusé"                   not is_valid_domain_name ""
check "points consécutifs refusés"    not is_valid_domain_name "hasan..net"
check "point final refusé"            not is_valid_domain_name "hasan.exemple.net."

# ── Hermès natif ────────────────────────────────────────────────────────────
echo "Détection de Hermès natif"
# Pas de Docker dans ces tests : aucun conteneur Hermès.
docker() { return 1; }

fake_python() {  # crée un « python » exécutable factice
    mkdir -p "$(dirname "$1")"
    printf '#!/bin/sh\nexit 0\n' > "$1"
    chmod +x "$1"
}

EMPTY="${TMP}/vide/.hermes"; mkdir -p "${EMPTY}"
check_eq "aucun Hermès → none" "none" "$(detect_hermes_mode "${EMPTY}")"
check_eq "aucun Hermès → pas de Python" "" "$(hermes_native_python "${EMPTY}")"

# Installation actuelle : code dans hermes-agent, SANS venv dedans,
# environnement géré décrit par installs/<id>/facts.json.
MANAGED="${TMP}/gere/.hermes"
mkdir -p "${MANAGED}/hermes-agent" "${MANAGED}/installs/abc"
touch "${MANAGED}/hermes-agent/run_agent.py"
ENV_DIR="${MANAGED}/installs/abc/environments/e1f2/venv"
fake_python "${ENV_DIR}/bin/python"
printf '{"packages": {"venv": {"environment": "%s"}}}\n' "${ENV_DIR}" > "${MANAGED}/installs/abc/facts.json"
check_eq "environnement géré, sans venv classique → native" "native" "$(detect_hermes_mode "${MANAGED}")"
check_eq "Python lu dans facts.json" "${ENV_DIR}/bin/python" "$(hermes_native_python "${MANAGED}")"

# Après un `hermes update` : un second facts.json plus récent, l'ancien
# environnement a disparu.
mkdir -p "${MANAGED}/installs/def"
NEW_ENV="${MANAGED}/installs/def/environments/9a8b/venv"
fake_python "${NEW_ENV}/bin/python"
printf '{"packages": {"venv": {"environment": "%s"}}}\n' "${NEW_ENV}" > "${MANAGED}/installs/def/facts.json"
touch -d '2020-01-01' "${MANAGED}/installs/abc/facts.json" 2>/dev/null || touch -t 202001010000 "${MANAGED}/installs/abc/facts.json"
check_eq "facts.json le plus récent d'abord" "${NEW_ENV}/bin/python" "$(hermes_native_python "${MANAGED}")"
rm -rf "${NEW_ENV}"
check_eq "environnement disparu → repli sur le précédent" "${ENV_DIR}/bin/python" "$(hermes_native_python "${MANAGED}")"

# facts.json illisible : ne doit ni planter ni renvoyer n'importe quoi.
BROKEN="${TMP}/casse/.hermes"
mkdir -p "${BROKEN}/hermes-agent" "${BROKEN}/installs/x"
touch "${BROKEN}/hermes-agent/run_agent.py"
echo 'pas du json' > "${BROKEN}/installs/x/facts.json"
check_eq "facts.json illisible → native quand même" "native" "$(detect_hermes_mode "${BROKEN}")"
check_eq "facts.json illisible → pas de Python" "" "$(hermes_native_python "${BROKEN}")"

# Ancienne disposition : venv classique dans hermes-agent.
LEGACY="${TMP}/ancien/.hermes"
fake_python "${LEGACY}/hermes-agent/venv/bin/python"
check_eq "ancien venv → native" "native" "$(detect_hermes_mode "${LEGACY}")"
check_eq "ancien venv → son Python" "${LEGACY}/hermes-agent/venv/bin/python" "$(hermes_native_python "${LEGACY}")"

# ── Conteneur Hermès prioritaire ────────────────────────────────────────────
echo "Détection de Hermès conteneurisé"
docker() {
    if [[ "$1" == "ps" ]]; then
        printf 'autre|nginx:latest\nmon-hermes|hasan-hermes:local\n'
    fi
}
check_eq "conteneur reconnu par son image" "mon-hermes" "$(detect_hermes_container)"
check_eq "le conteneur prime sur le natif" "container" "$(detect_hermes_mode "${MANAGED}")"

# ── Port 443 et réseaux Docker ──────────────────────────────────────────────
echo "Reverse proxy existant"
ss() { return 0; }
docker() {
    case "$1 ${2:-}" in
        "ps --format")
            if [[ "$3" == *Ports* ]]; then
                printf 'caddy\t0.0.0.0:80->80/tcp, 0.0.0.0:443->443/tcp\nhasan-bridge-x\t0.0.0.0:443->443/tcp\n'
            else
                printf 'caddy\n'
            fi
            ;;
        "inspect caddy") printf 'edge-net\nhomelab-net\n\n' ;;
        "network inspect")
            if [[ "$*" == *Gateway* ]]; then printf 'fd00::1\n172.18.0.1\n\n'; else printf '172.18.0.0/16\n\n'; fi
            ;;
    esac
}
check_eq "conteneur tiers sur 443" "caddy" "$(port_443_foreign_container)"
check_eq "détenteur nommé" "conteneur caddy" "$(port_443_foreign_holder)"
check_eq "réseaux du conteneur" "edge-net homelab-net" "$(container_networks caddy | tr '\n' ' ' | sed 's/ $//')"
check_eq "passerelle IPv4 du réseau" "172.18.0.1" "$(docker_network_gateway edge-net)"
check_eq "sous-réseau du réseau" "172.18.0.0/16" "$(docker_network_subnet edge-net)"

docker() {
    if [[ "$1 ${2:-}" == "ps --format" && "$3" != *Ports* ]]; then printf 'hasan-bridge-caddy-1\n'; fi
}
ss() { printf 'State Recv-Q\nLISTEN 0 users:(("caddy",pid=1,fd=3))\n'; }
check_eq "notre propre Caddy n'est pas un tiers" "" "$(port_443_foreign_holder)"
docker() { return 0; }
check_eq "process natif sur 443" 'process users:(("caddy",pid=1,fd=3))' "$(port_443_foreign_holder)"

# ── Fichiers générés ────────────────────────────────────────────────────────
echo "Fichiers générés"
ENVF="${TMP}/relay.env"
write_env_file "${ENVF}" "jeton" "hasan.exemple.net" "https://hermes.exemple.net" "secret" >/dev/null
check_eq "adresse publique sans port" "https://hasan.exemple.net" "$(read_env_var "${ENVF}" RELAY_PUBLIC_URL)"
check_eq "adresse de webui" "https://hermes.exemple.net" "$(read_env_var "${ENVF}" WEBUI_URL)"
check "plus de HERMES_API_BASE_URL" not grep -q '^HERMES_API_BASE_URL=' "${ENVF}"
write_env_file "${ENVF}" "jeton" "hasan.exemple.net" "https://hermes.exemple.net" "" >/dev/null
check "sans mot de passe, pas de WEBUI_URL" not grep -q '^WEBUI_' "${ENVF}"

HENV="${TMP}/hermes.env"
printf 'DEEPSEEK_API_KEY=abc\nHASAN_RELAY_URL=http://ancien\n' > "${HENV}"
upsert_env_var "${HENV}" HASAN_RELAY_URL "http://127.0.0.1:8767"
upsert_env_var "${HENV}" HASAN_RELAY_ADMIN_TOKEN "jeton"
check_eq "clé existante remplacée" "http://127.0.0.1:8767" "$(read_env_var "${HENV}" HASAN_RELAY_URL)"
check_eq "clé remplacée, pas dupliquée" "1" "$(grep -c '^HASAN_RELAY_URL=' "${HENV}")"
check_eq "nouvelle clé ajoutée" "jeton" "$(read_env_var "${HENV}" HASAN_RELAY_ADMIN_TOKEN)"
check_eq "autres clés intactes" "abc" "$(read_env_var "${HENV}" DEEPSEEK_API_KEY)"

OVR="${TMP}/docker-compose.network.yml"
write_network_override "${OVR}" "edge-net" >/dev/null
check "rattachement au réseau du Caddy" grep -q 'name: edge-net' "${OVR}"
write_network_override "${OVR}" "" >/dev/null
check "rattachement retiré s'il n'a plus lieu d'être" not test -f "${OVR}"

BLOCKS="$(caddy_site_blocks hasan.exemple.net hasan-relay:8767 hermes.exemple.net 172.18.0.1:8787)"
check "bloc du relay"   grep -q 'reverse_proxy hasan-relay:8767' <<<"${BLOCKS}"
check "bloc de webui"   grep -q 'reverse_proxy 172.18.0.1:8787' <<<"${BLOCKS}"
check "aucun tls internal"  not grep -q 'tls internal' <<<"${BLOCKS}"
check "aucun default_sni"   not grep -q 'default_sni' <<<"${BLOCKS}"

CADDYF="${TMP}/Caddyfile"
MARKER="# managed-by: hasan-bridge docker compose"
render_caddyfile "${SCRIPT_DIR}/../Caddyfile.template" "${CADDYF}" 0 "${MARKER}" \
    hasan.exemple.net 127.0.0.1:8767 hermes.exemple.net 127.0.0.1:8787 >/dev/null
check "Caddyfile rendu : relay"  grep -q '^hasan.exemple.net {' "${CADDYF}"
check "Caddyfile rendu : webui"  grep -q '^hermes.exemple.net {' "${CADDYF}"
check "Caddyfile rendu : plus de gabarit" not grep -q '{{' "${CADDYF}"
check "Caddyfile rendu : ni tls internal ni default_sni" not grep -Eq '^[[:space:]]*(tls internal|default_sni)' "${CADDYF}"
check "Caddyfile rendu : porte notre marqueur" caddyfile_is_ours "${CADDYF}" "${MARKER}"

# ── Version épinglée de hermes-webui ────────────────────────────────────────
echo "hermes-webui épinglé"
check "version par défaut ≠ master" test "${WEBUI_REF_DEFAULT}" != "master"
check "commit attendu complet (40 caractères hexadécimaux)" grep -Eq '^[0-9a-f]{40}$' <<<"${WEBUI_REF_DEFAULT_COMMIT}"
check "le Dockerfile de l'image dérivée épingle la même version" \
    grep -q "^ARG WEBUI_REF=${WEBUI_REF_DEFAULT}\$" "${SCRIPT_DIR}/../hermes-image/Dockerfile"

echo
if [[ "${FAIL}" -eq 0 ]]; then
    printf '\033[32m%d vérifications réussies\033[0m\n' "${PASS}"
else
    printf '\033[31m%d échec(s)\033[0m sur %d vérifications\n' "${FAIL}" "$((PASS + FAIL))"
    exit 1
fi
