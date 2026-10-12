#!/usr/bin/env bash
# Installe/met à jour le plugin hasan_delivery dans une installation Hermes
# NATIVE existante. Chemin manuel : server/install.sh fait la même chose, plus
# la configuration et le reste du bridge.
#
# Copie ce dossier vers ${HERMES_HOME:-~/.hermes}/plugins/hasan_delivery/ (le
# loader de plugins Hermes scanne ce dossier au démarrage du gateway), vérifie
# sa dépendance (httpx) et active le plugin. Copie aussi chaque sous-dossier de
# skills/ (ex: hasan-bridge-diagnosis, hasan-plugin-architecture) vers
# ${HERMES_HOME}/skills/general/ — skills lus par l'agent Hermes, format natif
# Hermes, distincts des skills .claude/skills/ de ce repo qui sont pour
# Claude Code.
#
# Ne configure PAS les variables d'environnement (HASAN_RELAY_URL,
# HASAN_RELAY_ADMIN_TOKEN) — voir README.md pour ça, ça dépend de choix
# propres à chaque installation (adresse du relay, jeton).
#
# Usage :
#   ./install-plugin.sh                 # installation ou mise à jour
#
# Idempotent : peut être relancé pour mettre à jour une installation existante.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
HERMES_HOME="${HERMES_HOME:-$HOME/.hermes}"
PLUGIN_DEST="${HERMES_HOME}/plugins/hasan_delivery"

if [[ ! -d "${HERMES_HOME}" ]]; then
    echo "Aucune installation Hermes trouvée dans ${HERMES_HOME}." >&2
    echo "Installer Hermes d'abord, ou définir HERMES_HOME si ce n'est pas le chemin par défaut." >&2
    exit 1
fi

# Python de Hermès. L'installateur officiel actuel crée un environnement géré
# hors de hermes-agent, dont le chemin change à chaque `hermes update` : on le
# lit dans installs/<id>/facts.json. Les installations plus anciennes ont un
# venv classique dans hermes-agent/venv. Même logique que hermes_native_python
# dans server/lib/detect.sh.
find_hermes_python() {
    local facts dir
    while IFS= read -r facts; do
        [[ -n "${facts}" ]] || continue
        dir="$(grep -o '"environment"[[:space:]]*:[[:space:]]*"[^"]*"' "${facts}" 2>/dev/null \
            | head -n1 | sed 's/.*:[[:space:]]*"\(.*\)"$/\1/')"
        if [[ -n "${dir}" && -x "${dir}/bin/python" ]]; then
            echo "${dir}/bin/python"
            return 0
        fi
    done < <(ls -t "${HERMES_HOME}"/installs/*/facts.json 2>/dev/null)
    if [[ -x "${HERMES_HOME}/hermes-agent/venv/bin/python" ]]; then
        echo "${HERMES_HOME}/hermes-agent/venv/bin/python"
    fi
    return 0
}

HERMES_PYTHON="$(find_hermes_python)"
if [[ -z "${HERMES_PYTHON}" ]]; then
    echo "Python de Hermès introuvable (${HERMES_HOME}/installs/*/facts.json," >&2
    echo "${HERMES_HOME}/hermes-agent/venv). Structure Hermes inattendue — vérifier HERMES_HOME." >&2
    exit 1
fi

echo "==> Copie du plugin vers ${PLUGIN_DEST}"
mkdir -p "${HERMES_HOME}/plugins"
rm -rf "${PLUGIN_DEST}"
mkdir -p "${PLUGIN_DEST}"
# Copie ciblée : rien des fichiers de ce dépôt (git, __pycache__, ce script
# lui-même, tests) n'a à se retrouver dans l'arborescence Hermes.
cp -r "${SCRIPT_DIR}"/*.py "${SCRIPT_DIR}"/*.yaml "${SCRIPT_DIR}"/requirements.txt "${PLUGIN_DEST}/"

echo "==> Dépendance Python (httpx)"
# httpx est déjà une dépendance de Hermès. L'environnement géré n'a pas pip
# (uv, verrouillé) : on n'installe que si httpx manque ET que pip existe.
if "${HERMES_PYTHON}" -c 'import httpx' >/dev/null 2>&1; then
    echo "    déjà présente dans le Python de Hermès — rien à installer."
elif "${HERMES_PYTHON}" -m pip --version >/dev/null 2>&1; then
    "${HERMES_PYTHON}" -m pip install --quiet -r "${PLUGIN_DEST}/requirements.txt"
else
    echo "    AVERTISSEMENT : httpx absent et pas de pip dans cet environnement —" >&2
    echo "    le plugin ne se chargera pas. Réparer Hermès (hermes update)." >&2
fi

SKILLS_SRC_DIR="${SCRIPT_DIR}/skills"
INSTALLED_SKILLS=()
if [[ -d "${SKILLS_SRC_DIR}" ]]; then
    mkdir -p "${HERMES_HOME}/skills/general"
    for skill_dir in "${SKILLS_SRC_DIR}"/*/; do
        [[ -d "${skill_dir}" ]] || continue
        skill_name="$(basename "${skill_dir}")"
        skill_dest="${HERMES_HOME}/skills/general/${skill_name}"
        echo "==> Copie du skill ${skill_name} vers ${skill_dest}"
        rm -rf "${skill_dest}"
        mkdir -p "${skill_dest}"
        cp -r "${skill_dir}"/. "${skill_dest}/"
        INSTALLED_SKILLS+=("${skill_name}")
    done
fi

echo "==> Activation du plugin"
# Les plugins tiers sont désactivés par défaut dans les Hermès récents.
HERMES_BIN=""
if [[ -x "${HOME}/.local/bin/hermes" ]]; then
    HERMES_BIN="${HOME}/.local/bin/hermes"
elif command -v hermes >/dev/null 2>&1; then
    HERMES_BIN="$(command -v hermes)"
fi
if [[ -n "${HERMES_BIN}" ]] && "${HERMES_BIN}" plugins enable hasan_delivery >/dev/null 2>&1; then
    echo "    activé (hermes plugins enable hasan_delivery)."
else
    echo "    non activé automatiquement — lancer : hermes plugins enable hasan_delivery"
    echo "    (sans objet sur un Hermès ancien, qui charge tous les plugins)."
fi

cat <<EOF

Installation terminée.

Reste à faire, si pas déjà en place :
  1. Configurer les variables d'environnement (voir README.md) :
       HASAN_RELAY_URL, HASAN_RELAY_ADMIN_TOKEN
  2. Redémarrer le gateway : hermes gateway restart
  3. Vérifier les logs : journalctl --user -u hermes-gateway.service -f | grep -i hasan_delivery
EOF
if [[ "${#INSTALLED_SKILLS[@]}" -gt 0 ]]; then
    echo "Skills installés dans ${HERMES_HOME}/skills/general/ : ${INSTALLED_SKILLS[*]}"
fi
