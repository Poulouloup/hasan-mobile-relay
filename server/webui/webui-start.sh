#!/bin/bash
# Lance hermes-webui avec le Python de l'environnement de Hermès — installé
# par server/install.sh (mode Hermès natif) et appelé par le service
# utilisateur hermes-webui.service.
#
# hermes-webui importe le code de Hermès : il doit tourner avec SON Python.
# Or l'environnement géré de Hermès change de dossier à chaque `hermes update`
# (chemin adressé par empreinte) : on le relit donc à chaque démarrage dans le
# fichier tenu par l'installateur de Hermès, au lieu de le figer ici.
#
# Repris du script écrit à la main sur le homelab (2026-10-12), où il tourne.
#
# Variables lues (voir ~/.config/hasan/webui.env, écrit par l'installateur) :
#   HERMES_WEBUI_PASSWORD  mot de passe de connexion (requis par webui)
#   HERMES_WEBUI_HOST      adresse d'écoute (défaut 127.0.0.1)
#   HERMES_WEBUI_PORT      port d'écoute (défaut 8787)
#   HERMES_HOME            défaut ~/.hermes
#   HERMES_WEBUI_DIR       défaut ~/hermes-webui
set -euo pipefail

export HERMES_HOME="${HERMES_HOME:-$HOME/.hermes}"
WEBUI_DIR="${HERMES_WEBUI_DIR:-$HOME/hermes-webui}"

PY=""
if command -v python3 >/dev/null 2>&1; then
    PY="$(python3 - "${HERMES_HOME}" <<'EOF'
import glob, json, os, sys
home = sys.argv[1]
facts = sorted(glob.glob(os.path.join(home, "installs", "*", "facts.json")), key=os.path.getmtime, reverse=True)
for f in facts:
    try:
        env = json.load(open(f))["packages"]["venv"]["environment"]
    except Exception:
        continue
    py = os.path.join(env, "bin", "python")
    if os.path.exists(py):
        print(py)
        break
EOF
)"
fi
# Installations plus anciennes : venv classique dans hermes-agent.
if [ -z "${PY}" ] && [ -x "${HERMES_HOME}/hermes-agent/venv/bin/python" ]; then
    PY="${HERMES_HOME}/hermes-agent/venv/bin/python"
fi
if [ -z "${PY}" ]; then
    echo "environnement Python de Hermès introuvable (${HERMES_HOME}/installs/*/facts.json, ${HERMES_HOME}/hermes-agent/venv)" >&2
    exit 1
fi

export HERMES_WEBUI_PYTHON="${PY}"
export HERMES_WEBUI_AGENT_DIR="${HERMES_HOME}/hermes-agent"
# Échouer clairement plutôt que laisser webui se créer un venv à lui, sans
# Hermès dedans : le chat échouerait plus tard, sans explication.
export HERMES_WEBUI_DISABLE_LOCAL_VENV=1
export HERMES_WEBUI_HOST="${HERMES_WEBUI_HOST:-127.0.0.1}"
export HERMES_WEBUI_PORT="${HERMES_WEBUI_PORT:-8787}"
# Un reverse proxy termine le TLS devant webui : sans ça le cookie de session
# n'est pas marqué Secure.
export HERMES_WEBUI_TRUST_FORWARDED_PROTO=1
export HERMES_WEBUI_DEFAULT_WORKSPACE="${HERMES_WEBUI_DEFAULT_WORKSPACE:-$HOME/workspace}"
export PATH="$HOME/.local/bin:$PATH"

mkdir -p "${HERMES_WEBUI_DEFAULT_WORKSPACE}"
cd "${WEBUI_DIR}"
exec /bin/bash "${WEBUI_DIR}/start.sh" --foreground
