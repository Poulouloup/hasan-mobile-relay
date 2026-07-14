#!/usr/bin/env bash
# Installe/met à jour le relay server Hasan sur un VPS Debian/Ubuntu.
#
# Ne couvre PAS : la mise en place TLS (Caddy, reverse proxy) ni le firewall
# réseau (règles GCP/AWS/ufw selon le provider) — ces deux points dépendent de
# l'environnement d'hébergement et ont été faits manuellement lors du premier
# déploiement (voir .claude/v2-relay-merge-journal.md). Ce script se limite à
# la partie applicative reproductible : utilisateur système, code, venv,
# service systemd.
#
# Usage :
#   sudo ./install-relay.sh                 # installation ou mise à jour
#   sudo ./install-relay.sh --repo <url>     # utilise un autre remote git
#
# Idempotent : peut être relancé pour mettre à jour une installation existante
# (git pull + réinstall des dépendances + redémarrage du service).

set -euo pipefail

REPO_URL="https://github.com/Poulouloup/hasan-mobile-relay.git"
INSTALL_DIR="/opt/hasan-relay"
SERVICE_USER="hasanrelay"
SERVICE_NAME="hermes-relay"

while [[ $# -gt 0 ]]; do
    case "$1" in
        --repo)
            REPO_URL="$2"
            shift 2
            ;;
        *)
            echo "Argument inconnu : $1" >&2
            exit 1
            ;;
    esac
done

if [[ "$(id -u)" -ne 0 ]]; then
    echo "Ce script doit être exécuté en root (sudo)." >&2
    exit 1
fi

echo "==> Dépendances système (python3-venv, git)"
apt-get update -qq
apt-get install -y --no-install-recommends python3-venv python3-pip git >/dev/null

echo "==> Utilisateur système ${SERVICE_USER}"
if ! id "${SERVICE_USER}" >/dev/null 2>&1; then
    adduser --system --group "${SERVICE_USER}"
    echo "Utilisateur ${SERVICE_USER} créé."
else
    echo "Utilisateur ${SERVICE_USER} déjà existant, inchangé."
fi

echo "==> Code source dans ${INSTALL_DIR}"
if [[ -d "${INSTALL_DIR}/.git" ]]; then
    echo "Dépôt existant détecté — mise à jour (git pull)."
    git -C "${INSTALL_DIR}" pull --ff-only
else
    echo "Premier déploiement — clonage depuis ${REPO_URL}."
    mkdir -p "${INSTALL_DIR}"
    git clone "${REPO_URL}" "${INSTALL_DIR}"
fi

echo "==> Environnement virtuel Python"
if [[ ! -d "${INSTALL_DIR}/venv" ]]; then
    python3 -m venv "${INSTALL_DIR}/venv"
fi
"${INSTALL_DIR}/venv/bin/pip" install --quiet --upgrade pip
"${INSTALL_DIR}/venv/bin/pip" install --quiet -r "${INSTALL_DIR}/server/relay/requirements.txt"

echo "==> Permissions"
chown -R "${SERVICE_USER}:${SERVICE_USER}" "${INSTALL_DIR}"

echo "==> Service systemd"
cp "${INSTALL_DIR}/server/relay/hermes-relay.service" "/etc/systemd/system/${SERVICE_NAME}.service"
systemctl daemon-reload
systemctl enable "${SERVICE_NAME}"
systemctl restart "${SERVICE_NAME}"

echo "==> Statut du service"
sleep 1
systemctl status "${SERVICE_NAME}" --no-pager -l || true

cat <<'EOF'

Installation terminée.

Rappel — non couvert par ce script, à faire manuellement si pas déjà en place :
  - Reverse proxy TLS (Caddy) devant le port 8767 du relay
  - Règle de firewall réseau côté provider (GCP/AWS/...) ouvrant le port
    utilisé par le reverse proxy (443 en général, pas 8767 directement)

Voir les logs en direct : journalctl -u hermes-relay -f
EOF
