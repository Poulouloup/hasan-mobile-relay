#!/usr/bin/env bash
# Installe/met à jour le plugin hasan_delivery dans une installation Hermes existante.
#
# Copie ce dossier vers ${HERMES_HOME:-~/.hermes}/plugins/hasan_delivery/ (le
# loader de plugins Hermes scanne ce dossier au démarrage du gateway) et
# installe sa dépendance (httpx) dans le venv de hermes-agent.
#
# Ne configure PAS les variables d'environnement (HASAN_RELAY_URL,
# HASAN_RELAY_SESSION_TOKEN, ...) — voir README.md pour ça, ça dépend de
# choix propres à chaque installation (URL du relay, appairage du device).
#
# Usage :
#   ./install-plugin.sh                 # installation ou mise à jour
#
# Idempotent : peut être relancé pour mettre à jour une installation existante.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
HERMES_HOME="${HERMES_HOME:-$HOME/.hermes}"
PLUGIN_DEST="${HERMES_HOME}/plugins/hasan_delivery"
AGENT_VENV="${HERMES_HOME}/hermes-agent/venv"

if [[ ! -d "${HERMES_HOME}" ]]; then
    echo "Aucune installation Hermes trouvée dans ${HERMES_HOME}." >&2
    echo "Installer Hermes d'abord, ou définir HERMES_HOME si ce n'est pas le chemin par défaut." >&2
    exit 1
fi

if [[ ! -d "${AGENT_VENV}" ]]; then
    echo "Venv hermes-agent introuvable dans ${AGENT_VENV}." >&2
    echo "Structure Hermes inattendue — vérifier HERMES_HOME." >&2
    exit 1
fi

echo "==> Copie du plugin vers ${PLUGIN_DEST}"
mkdir -p "${HERMES_HOME}/plugins"
rm -rf "${PLUGIN_DEST}"
mkdir -p "${PLUGIN_DEST}"
# --exclude évite de copier les fichiers de ce dépôt (git, __pycache__, ce
# script lui-même) qui n'ont rien à faire dans l'arborescence Hermes.
cp -r "${SCRIPT_DIR}"/*.py "${SCRIPT_DIR}"/*.yaml "${SCRIPT_DIR}"/requirements.txt "${PLUGIN_DEST}/"

echo "==> Dépendances Python (httpx) dans le venv hermes-agent"
"${AGENT_VENV}/bin/pip" install --quiet -r "${PLUGIN_DEST}/requirements.txt"

cat <<EOF

Installation terminée.

Reste à faire, si pas déjà en place :
  1. Configurer les variables d'environnement (voir README.md) :
       HASAN_RELAY_URL, HASAN_RELAY_SESSION_TOKEN, HASAN_RELAY_ADMIN_TOKEN
  2. Redémarrer le gateway : hermes gateway restart
  3. Vérifier les logs : journalctl --user -u hermes-gateway.service -f | grep -i hasan_delivery
EOF
