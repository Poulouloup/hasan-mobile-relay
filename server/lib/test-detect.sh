#!/usr/bin/env bash
# Vérifie la détection d'infrastructure SANS RIEN INSTALLER ni modifier.
#
# À lancer en premier sur une machine cible : il affiche ce que le vrai
# installateur va décider, et pourquoi. Si une ligne est fausse, l'installation
# le serait aussi — autant s'en apercevoir avant.
#
# Usage :
#   ./server/lib/test-detect.sh
#
# Ne demande pas root : aucune écriture, aucun conteneur lancé. Docker est
# seulement interrogé en lecture (docker ps/inspect), ce qui peut nécessiter
# d'être dans le groupe docker — auquel cas le script le signale au lieu
# d'échouer.

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=./detect.sh
source "${SCRIPT_DIR}/detect.sh"

if [[ -n "${SUDO_USER:-}" ]]; then
    USER_HOME="$(getent passwd "${SUDO_USER}" | cut -d: -f6)"
else
    USER_HOME="${HOME}"
fi
HERMES_HOME="${HERMES_HOME:-${USER_HOME}/.hermes}"
CADDY_MARKER="# managed-by: hasan-bridge docker compose"

ok()   { printf '  \033[32m✓\033[0m %s\n' "$1"; }
no()   { printf '  \033[31m✗\033[0m %s\n' "$1"; }
info() { printf '    %s\n' "$1"; }

echo
echo "=== Détection de l'infrastructure — aucune modification ==="
echo

# ── 1. Garde-fou ────────────────────────────────────────────────────────────
echo "1. Emplacement d'exécution"
if running_inside_container; then
    no "DANS un conteneur — le vrai installateur refuserait de continuer"
    info "Relancer depuis la machine hôte."
else
    ok "Sur la machine hôte (/.dockerenv absent)"
fi
echo

# ── 2. Docker ───────────────────────────────────────────────────────────────
echo "2. Docker"
if command -v docker >/dev/null 2>&1; then
    if docker ps >/dev/null 2>&1; then
        ok "présent et accessible ($(docker --version 2>/dev/null | head -n1))"
    else
        no "présent mais inaccessible sans privilèges"
        info "Relancer avec sudo, ou ajouter l'utilisateur au groupe docker."
        info "La détection Hermes/Caddy ci-dessous sera incomplète."
    fi
else
    no "absent — le vrai installateur l'installerait via get.docker.com"
fi
echo

# ── 3. Hermes ───────────────────────────────────────────────────────────────
echo "3. Hermes"
HERMES_MODE="$(detect_hermes_mode "${HERMES_HOME}")"
HERMES_PYTHON=""
CONTAINER=""
case "${HERMES_MODE}" in
    container)
        CONTAINER="$(detect_hermes_container)"
        ok "conteneurisé — conteneur « ${CONTAINER} »"
        VOL="$(detect_hermes_volume_host_path "${CONTAINER}")"
        if [[ -n "${VOL}" ]]; then
            info "volume /opt/data monté depuis : ${VOL}"
            info "→ le plugin sera déposé dans ${VOL}/plugins/"
        else
            no "aucun volume monté sur /opt/data"
            info "Sans volume, le plugin serait perdu au prochain redémarrage."
            info "Ajouter au compose : - ~/.hermes:/opt/data"
        fi
        if hermes_container_has_httpx "${CONTAINER}"; then
            ok "httpx déjà présent dans l'image — rien à installer"
        else
            no "httpx absent de l'image"
            info "Inattendu : l'image officielle l'embarque (vérifié en 0.28.1)."
            info "Une image dérivée serait nécessaire pour ce cas."
        fi
        ;;
    native)
        ok "natif — code dans ${HERMES_HOME}/hermes-agent"
        HERMES_PYTHON="$(hermes_native_python "${HERMES_HOME}")"
        if [[ -n "${HERMES_PYTHON}" ]]; then
            ok "Python de Hermès : ${HERMES_PYTHON}"
            if python_has_module "${HERMES_PYTHON}" httpx; then
                ok "httpx déjà présent — rien à installer"
            elif python_has_pip "${HERMES_PYTHON}"; then
                info "httpx absent — sera installé par pip"
            else
                no "httpx absent et pas de pip dans cet environnement"
                info "Le plugin ne se chargerait pas : réparer Hermès (hermes update)."
            fi
            if ! python_has_pip "${HERMES_PYTHON}"; then
                info "environnement sans pip (géré par uv) — aucun pip install ne sera tenté"
            fi
        else
            no "environnement Python de Hermès introuvable"
            info "Cherché : ${HERMES_HOME}/installs/*/facts.json, puis hermes-agent/venv."
            info "Le vrai installateur s'arrêterait ici."
        fi
        info "→ le plugin sera déposé dans ${HERMES_HOME}/plugins/, puis activé"
        info "  (hermes plugins enable hasan_delivery)"
        ;;
    none)
        no "introuvable (ni conteneur, ni ${HERMES_HOME}/hermes-agent)"
        info "Le vrai installateur s'arrêterait ici : il n'installe pas Hermes."
        info "Installer Hermes d'abord, ou définir HERMES_HOME."
        ;;
esac
echo

# ── 4. Reverse proxy / port 443 ─────────────────────────────────────────────
echo "4. Reverse proxy (TLS, point d'entrée unique)"
WEBUI_BIND_HOST="127.0.0.1"
CADDY_HOLDER="$(port_443_foreign_holder)"
CADDY_CONTAINER="$(port_443_foreign_container)"
if [[ -n "${CADDY_HOLDER}" ]]; then
    ok "le port 443 est déjà tenu par un tiers : ${CADDY_HOLDER}"
    info "L'installateur le réutilisera plutôt que d'en lancer un second"
    info "(qui crash-looperait — piège du 2026-07-28), et affichera les deux"
    info "blocs à ajouter à son Caddyfile."
    if [[ -n "${CADDY_CONTAINER}" ]]; then
        mapfile -t NETS < <(container_networks "${CADDY_CONTAINER}")
        if [[ "${#NETS[@]}" -gt 0 && "${NETS[0]}" != "host" ]]; then
            info "Ce conteneur est en réseau Docker classique : ${NETS[*]}"
            info "→ il ne joint pas 127.0.0.1 de l'hôte ; le relay rejoindra son réseau"
            NET="${CADDY_NETWORK:-${NETS[0]}}"
            GW="$(docker_network_gateway "${NET}")"
            if [[ -n "${GW}" ]]; then
                WEBUI_BIND_HOST="${GW}"
                info "→ hermes-webui devra écouter sur la passerelle de « ${NET} » : ${GW}"
                [[ "${#NETS[@]}" -gt 1 ]] && info "  (plusieurs réseaux : l'installateur demandera lequel, ou CADDY_NETWORK=…)"
            else
                no "passerelle du réseau « ${NET} » introuvable"
            fi
        else
            info "Ce conteneur est en réseau hôte : il joint le relay et webui sur 127.0.0.1."
        fi
    fi
else
    info "port 443 libre — l'installateur lancera son propre Caddy (Let's Encrypt :"
    info "les noms doivent pointer ici et les ports 80/443 être joignables d'Internet)"
fi
if caddyfile_is_ours "${SCRIPT_DIR}/../Caddyfile" "${CADDY_MARKER}"; then
    ok "server/Caddyfile porte notre marqueur — déploiement existant"
elif [[ -f "${SCRIPT_DIR}/../Caddyfile" ]]; then
    no "server/Caddyfile existe SANS notre marqueur"
    info "L'installateur refuserait de l'écraser sans --force."
fi
echo

# ── 5. hermes-webui ─────────────────────────────────────────────────────────
echo "5. hermes-webui (écran Chat de l'app)"
if [[ "${HERMES_MODE}" == "container" ]]; then
    if webui_embedded_in_container "${CONTAINER}"; then
        ok "embarqué dans l'image du conteneur Hermès — rien à installer"
    else
        no "absent de l'image du conteneur Hermès"
        info "Le vrai installateur refuserait : reconstruire server/hermes-image/."
    fi
elif webui_is_running "${WEBUI_BIND_HOST}"; then
    ok "répond sur ${WEBUI_BIND_HOST}:8787 — rien à installer"
elif webui_is_installed "${USER_HOME}"; then
    no "cloné dans ${USER_HOME}/hermes-webui mais ne répond pas sur ${WEBUI_BIND_HOST}:8787"
    info "L'installateur le placera sur sa version épinglée et le (re)lancera"
    info "comme service utilisateur systemd, à l'écoute sur ${WEBUI_BIND_HOST}."
else
    no "absent — sera cloné depuis github.com/nesquena/hermes-webui"
    info "À une version épinglée (pas la tête de master), lancé comme service"
    info "utilisateur systemd avec le Python de Hermès."
fi
echo

# ── 6. Ports ────────────────────────────────────────────────────────────────
echo "6. Ports requis"
for port in 443 8767; do
    holder="$(port_holder "${port}")"
    if [[ -n "${holder}" ]]; then
        no "port ${port} occupé par : ${holder}"
    else
        ok "port ${port} libre"
    fi
done
info "Ports occupés par un déploiement Hasan existant = normal (mise à jour)."
echo

# ── Verdict ─────────────────────────────────────────────────────────────────
echo "=== Résumé ==="
echo "  Mode Hermes détecté : ${HERMES_MODE}"
case "${HERMES_MODE}" in
    container) echo "  → plugin copié dans le volume, httpx déjà là, redémarrage via docker restart" ;;
    native)
        if [[ -n "${HERMES_PYTHON}" ]]; then
            echo "  → plugin copié dans ${HERMES_HOME}/plugins, activé, redémarrage via systemctl --user"
        else
            echo "  → installation impossible en l'état : environnement Python de Hermès introuvable"
        fi
        ;;
    none)      echo "  → installation impossible en l'état : Hermes est requis" ;;
esac
echo "  L'installateur demandera deux noms de domaine (relay, hermes-webui) :"
echo "  l'app refuse une adresse IP ou un certificat non reconnu."
echo
