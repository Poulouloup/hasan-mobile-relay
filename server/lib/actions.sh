#!/usr/bin/env bash
# Actions d'installation, sourcées par install.sh. Séparées de detect.sh (qui,
# lui, ne fait que lire l'état) : ici on écrit, on installe, on redémarre.
#
# Chaque fonction est volontairement étroite pour rester lisible et testable.
# Elles supposent detect.sh déjà sourcé (elles s'appuient sur ses fonctions),
# ainsi que as_user/user_systemctl définis par install.sh.

# hermes-webui est installé à une version épinglée, pas à la tête de master :
# une installation doit donner le même résultat demain qu'aujourd'hui. Le
# README de webui demande de le mettre à jour EN MÊME TEMPS que Hermès : cette
# étiquette date du même jour que Hermès v0.21.6 (2026-10-10). Pour un autre
# Hermès, passer WEBUI_REF=<étiquette ou commit> à l'installateur.
WEBUI_REF_DEFAULT="exp-v0.52.459"
# Commit attendu derrière l'étiquette par défaut — une étiquette git peut être
# déplacée, un commit non. Vérifié après le checkout.
WEBUI_REF_DEFAULT_COMMIT="81e1e7f7c4f7d8c3d00055e3d0cce746f990a74a"
WEBUI_REPO_URL="https://github.com/nesquena/hermes-webui.git"

# ─────────────────────── webui absent en conteneur → abandon ───────────────

# Appelé quand Hermès est conteneurisé mais que webui n'est PAS dans l'image.
# webui ne peut pas être ajouté après coup (il importe le code de Hermès) :
# on refuse et on guide vers le rebuild, plutôt que de livrer un déploiement
# sans écran Chat.
webui_container_missing_abort() {
    cat >&2 <<EOF
Hermès est conteneurisé mais hermes-webui n'est pas dans l'image.

hermes-webui doit tourner DANS le conteneur Hermès — il importe le code de
Hermès et ne peut pas être ajouté à part. L'image utilisée est probablement
l'image officielle nue, sans webui.

À faire : reconstruire l'image dérivée qui embarque webui, puis pointer
votre compose Hermès dessus :

  docker build -t hasan-hermes ${REPO_ROOT}/server/hermes-image
  # dans votre docker-compose.yml Hermès :
  #   image: hasan-hermes    (au lieu de nousresearch/hermes-agent)
  docker compose up -d

Puis relancer ce script. Voir server/hermes-image/README.md.
EOF
    exit 1
}

# ─────────────────────── Fichiers clé=valeur ───────────────────────────────

# Valeur d'une clé dans un fichier KEY=value, ou vide.
read_env_var() {
    local file="$1" key="$2"
    [[ -f "${file}" ]] || return 0
    grep "^${key}=" "${file}" 2>/dev/null | head -n1 | cut -d= -f2-
    return 0
}

# Pose KEY=value dans un fichier d'environnement sans toucher aux autres
# lignes (le .env de Hermès contient les clés d'API de l'utilisateur).
# Remplace la ligne si la clé existe, l'ajoute sinon. Le fichier est écrit en
# tant qu'utilisateur invocateur et passé en 600.
upsert_env_var() {
    local file="$1" key="$2" value="$3" tmp
    tmp="$(mktemp)"
    if [[ -f "${file}" ]]; then
        grep -v "^${key}=" "${file}" > "${tmp}" || true
    fi
    printf '%s=%s\n' "${key}" "${value}" >> "${tmp}"
    chmod 644 "${tmp}"
    as_user mkdir -p "$(dirname "${file}")"
    as_user cp "${tmp}" "${file}"
    as_user chmod 600 "${file}"
    rm -f "${tmp}"
}

# ─────────────────────── Mot de passe hermes-webui ─────────────────────────

# Résout le mot de passe webui pour le QR de pairing, sans prompt si possible.
# Ordre : env HERMES_WEBUI_PASSWORD déjà exportée > source selon le mode
# (fichiers d'environnement en natif, env du conteneur en conteneurisé) >
# prompt manuel. Écrit le résultat sur stdout (chaîne vide si rien) — la
# fonction ne parle qu'à stderr pour ne pas polluer sa valeur de retour.
resolve_webui_password() {
    local user_home="$1" mode="$2" container="$3" pw=""

    if [[ -n "${HERMES_WEBUI_PASSWORD:-}" ]]; then
        echo "${HERMES_WEBUI_PASSWORD}"
        return
    fi

    if [[ "${mode}" == "container" ]]; then
        # En conteneurisé, le mot de passe vit dans l'environnement du
        # conteneur Hermès (posé via le compose ou le .env de /opt/data).
        pw="$(docker exec "${container}" sh -c 'printf %s "${HERMES_WEBUI_PASSWORD:-}"' 2>/dev/null)"
    else
        # Notre fichier d'abord (webui installé par ce script), puis le .env
        # d'un webui installé à la main.
        pw="$(read_env_var "${user_home}/.config/hasan/webui.env" HERMES_WEBUI_PASSWORD)"
        if [[ -z "${pw}" ]]; then
            pw="$(read_env_var "${user_home}/hermes-webui/.env" HERMES_WEBUI_PASSWORD)"
        fi
    fi

    if [[ -z "${pw}" ]]; then
        # Dernier recours : demander. Prompt et lecture sur le terminal (stderr
        # pour le prompt, la saisie ne transite pas par stdout).
        read -rsp "Mot de passe hermes-webui (vide = login manuel dans l'app) : " pw </dev/tty >&2
        echo >&2
    fi
    echo "${pw}"
}

# ─────────────────────── hermes-webui (mode natif) ─────────────────────────

# Place le dépôt hermes-webui sur la version épinglée [ref].
#
# Clone complet (pas --depth 1) : il faut pouvoir se placer sur une étiquette
# ou un commit arbitraire, et y revenir à la prochaine exécution.
checkout_webui_ref() {
    local webui_dir="$1" ref="$2" head

    if [[ -d "${webui_dir}/.git" ]]; then
        echo "  hermes-webui déjà cloné dans ${webui_dir} — récupération des versions."
        as_user git -C "${webui_dir}" fetch --quiet --tags origin || \
            echo "    (git fetch a échoué — on continue avec les versions déjà présentes.)"
    else
        echo "  Clone de hermes-webui dans ${webui_dir}."
        as_user git clone --quiet "${WEBUI_REPO_URL}" "${webui_dir}"
    fi

    if ! as_user git -C "${webui_dir}" checkout --quiet --detach "${ref}"; then
        echo "Impossible de placer hermes-webui sur « ${ref} »." >&2
        echo "Version inconnue, ou dépôt local modifié (${webui_dir})." >&2
        exit 1
    fi
    head="$(as_user git -C "${webui_dir}" rev-parse HEAD)"
    echo "  hermes-webui placé sur ${ref} (commit ${head:0:8})."

    if [[ "${ref}" == "${WEBUI_REF_DEFAULT}" && "${head}" != "${WEBUI_REF_DEFAULT_COMMIT}" ]]; then
        echo "L'étiquette ${ref} ne désigne plus le commit attendu." >&2
        echo "  attendu : ${WEBUI_REF_DEFAULT_COMMIT}" >&2
        echo "  obtenu  : ${head}" >&2
        echo "Elle a été déplacée en amont — vérifier avant de continuer, ou" >&2
        echo "relancer avec WEBUI_REF=<commit> pour choisir explicitement." >&2
        exit 1
    fi
}

# Installe hermes-webui à côté d'un Hermès natif et le lance comme service
# utilisateur systemd. Réservé au cas natif : en conteneurisé, webui vit dans
# l'image dérivée (server/hermes-image/), pas ici.
#
# webui n'est PAS un service autonome — il importe le code de Hermès — donc il
# tourne avec le Python de Hermès, pas dans un venv à lui.
install_webui_native() {
    local hermes_home="$1" user_home="$2" bind_host="$3" webui_src="$4"
    local webui_dir="${user_home}/hermes-webui"
    local ref="${WEBUI_REF:-${WEBUI_REF_DEFAULT}}"
    local python env_file start_script unit_dir password

    python="$(hermes_native_python "${hermes_home}")"
    if [[ -z "${python}" ]]; then
        echo "Python de Hermès introuvable (${hermes_home}/installs/*/facts.json," >&2
        echo "${hermes_home}/hermes-agent/venv) — impossible de lancer hermes-webui." >&2
        exit 1
    fi

    checkout_webui_ref "${webui_dir}" "${ref}"

    # L'environnement géré de Hermès n'a pas pip, et n'en a pas besoin : les
    # dépendances de webui (httpx, cryptography, un lecteur YAML) y sont déjà.
    # On n'installe que si pip existe (anciens venv), et on vérifie le résultat
    # dans tous les cas plutôt que de le supposer.
    if python_has_pip "${python}"; then
        echo "  Installation des dépendances webui (pip du venv Hermès)."
        as_user "${python}" -m pip install --quiet -r "${webui_dir}/requirements.txt" || \
            echo "    (pip a échoué — vérification des modules ci-dessous.)"
    else
        echo "  Environnement Hermès sans pip (géré par uv) — rien à installer."
    fi
    if ! python_has_module "${python}" cryptography; then
        echo "    AVERTISSEMENT : module « cryptography » absent du Python de Hermès." >&2
    fi
    if ! python_has_module "${python}" yaml && ! python_has_module "${python}" ruamel.yaml; then
        echo "    AVERTISSEMENT : aucun lecteur YAML (yaml ou ruamel.yaml) dans le Python de Hermès." >&2
    fi

    env_file="${user_home}/.config/hasan/webui.env"
    start_script="${user_home}/.local/share/hasan/webui-start.sh"
    unit_dir="${user_home}/.config/systemd/user"

    # Mot de passe : celui déjà en place s'il y en a un, sinon un nouveau —
    # webui ne doit jamais démarrer sans, il donne accès au terminal de Hermès.
    password="${HERMES_WEBUI_PASSWORD:-}"
    [[ -n "${password}" ]] || password="$(read_env_var "${env_file}" HERMES_WEBUI_PASSWORD)"
    [[ -n "${password}" ]] || password="$(read_env_var "${webui_dir}/.env" HERMES_WEBUI_PASSWORD)"
    if [[ -z "${password}" ]]; then
        password="$(openssl rand -hex 16)"
        echo "  Mot de passe hermes-webui généré (dans ${env_file})."
    fi
    upsert_env_var "${env_file}" HERMES_WEBUI_PASSWORD "${password}"
    upsert_env_var "${env_file}" HERMES_WEBUI_HOST "${bind_host}"
    upsert_env_var "${env_file}" HERMES_HOME "${hermes_home}"

    as_user mkdir -p "$(dirname "${start_script}")" "${unit_dir}"
    as_user cp "${webui_src}/webui-start.sh" "${start_script}"
    as_user chmod 755 "${start_script}"

    local unit_tmp; unit_tmp="$(mktemp)"
    sed -e "s|{{ENV_FILE}}|${env_file}|g" -e "s|{{START_SCRIPT}}|${start_script}|g" \
        "${webui_src}/hermes-webui.service.template" > "${unit_tmp}"
    chmod 644 "${unit_tmp}"
    as_user cp "${unit_tmp}" "${unit_dir}/hermes-webui.service"
    rm -f "${unit_tmp}"

    echo "  Démarrage du service utilisateur hermes-webui (écoute sur ${bind_host}:8787)."
    if user_systemctl daemon-reload && user_systemctl enable --quiet hermes-webui.service \
        && user_systemctl restart hermes-webui.service; then
        :
    else
        echo "    (systemctl --user a échoué — lancer à la main : systemctl --user enable --now hermes-webui)"
    fi
}

# ─────────────────────── .env du relay ─────────────────────────────────────

write_env_file() {
    local env_file="$1" admin_token="$2" relay_host="$3"
    local webui_url="${4:-}" webui_password="${5:-}"
    cat > "${env_file}" <<EOF
# Généré par server/install.sh — ne pas éditer à la main.
RELAY_ADMIN_TOKEN=${admin_token}
# Adresse publique du relay, portée dans le QR de pairing. Sans port : le
# reverse proxy la sert en https sur 443.
RELAY_PUBLIC_URL=https://${relay_host}
# Sessions de pairing persistées dans le volume du conteneur relay.
RELAY_SESSIONS_PATH=/data/sessions.json
EOF
    # WEBUI_URL/WEBUI_PASSWORD : le relay les embarque dans le QR de pairing
    # pour que l'app se connecte au Chat automatiquement. Les deux ou aucun
    # (le relay valide cette règle). Sans mot de passe, on n'écrit ni l'un ni
    # l'autre — l'utilisateur saisira le login dans l'app.
    if [[ -n "${webui_url}" ]] && [[ -n "${webui_password}" ]]; then
        cat >> "${env_file}" <<EOF
WEBUI_URL=${webui_url}
WEBUI_PASSWORD=${webui_password}
EOF
    fi
    chmod 600 "${env_file}"
    echo "  ${env_file} écrit (permissions 600)."
}

# ─────────────────────── Réseau Docker du relay ────────────────────────────

# Écrit le fichier compose complémentaire qui attache le relay au réseau
# Docker d'un Caddy existant (voir container_networks dans detect.sh) — ou le
# supprime si ce n'est pas nécessaire, pour qu'une relance dans une autre
# topologie ne garde pas un rattachement périmé.
write_network_override() {
    local override_file="$1" caddy_network="$2"
    if [[ -z "${caddy_network}" ]]; then
        rm -f "${override_file}"
        return 0
    fi
    cat > "${override_file}" <<EOF
# Généré par server/install.sh — ne pas éditer à la main.
# Attache le relay au réseau Docker du reverse proxy existant, qui le joint
# par son nom (hasan-relay:8767).
services:
  relay:
    networks:
      - default
      - caddy_net

networks:
  caddy_net:
    external: true
    name: ${caddy_network}
EOF
    echo "  ${override_file} écrit (relay attaché au réseau « ${caddy_network} »)."
}

# ─────────────────────── Caddyfile ─────────────────────────────────────────

# Les deux blocs de site, pour un Caddyfile existant ou pour le nôtre. Aucune
# directive tls : Caddy obtient lui-même un certificat reconnu pour ces noms
# (ou utilise celui que l'utilisateur a déjà configuré, ex. un certificat
# générique par challenge DNS).
caddy_site_blocks() {
    local relay_host="$1" relay_upstream="$2" webui_host="$3" webui_upstream="$4"
    cat <<EOF
${relay_host} {
	reverse_proxy ${relay_upstream} {
		flush_interval -1
	}
}

${webui_host} {
	reverse_proxy ${webui_upstream} {
		flush_interval -1
	}
}
EOF
}

render_caddyfile() {
    local template="$1" caddyfile="$2" force="$3" marker="$4"
    local relay_host="$5" relay_upstream="$6" webui_host="$7" webui_upstream="$8"

    if [[ -f "${caddyfile}" ]] && ! head -n1 "${caddyfile}" | grep -qF "${marker}"; then
        if [[ "${force}" -ne 1 ]]; then
            echo "Un ${caddyfile} existe sans notre marqueur — refus d'écraser." >&2
            echo "Le sauvegarder puis relancer avec --force." >&2
            exit 1
        fi
        echo "  Caddyfile tiers remplacé (--force)."
    fi

    sed -e "s|{{RELAY_HOST}}|${relay_host}|g" -e "s|{{RELAY_UPSTREAM}}|${relay_upstream}|g" \
        -e "s|{{WEBUI_HOST}}|${webui_host}|g" -e "s|{{WEBUI_UPSTREAM}}|${webui_upstream}|g" \
        "${template}" > "${caddyfile}"
    echo "  ${caddyfile} rendu pour ${relay_host} et ${webui_host}."
}

# ─────────────────────── Dépôt du plugin ───────────────────────────────────

# Dépose le plugin et ses skills là où Hermès les scanne. Le CHEMIN dépend du
# mode, pour une raison découverte au test :
#
#   - Natif : ~/.hermes appartient à l'utilisateur, on copie directement.
#   - Conteneurisé : le volume /opt/data appartient à l'uid 10000 du conteneur
#     (mode 0700), l'utilisateur hôte n'y a AUCUN accès. On ne peut donc pas
#     écrire depuis l'hôte — on passe par `docker cp`, qui écrit en tant que
#     propriétaire du conteneur, avec les bons droits d'emblée.
install_plugin() {
    local plugin_src="$1" dest_dir="$2" mode="$3" hermes_home="$4"

    if [[ "${mode}" == "container" ]]; then
        install_plugin_container "${plugin_src}" "${HERMES_CONTAINER}"
        return
    fi
    install_plugin_native "${plugin_src}" "${dest_dir}" "${hermes_home}"
}

install_plugin_native() {
    local plugin_src="$1" dest_dir="$2" hermes_home="$3"
    local plugins_dir="${dest_dir%/}/plugins/hasan_delivery"
    local skills_dir="${dest_dir%/}/skills/general"
    local python

    as_user mkdir -p "${plugins_dir}" "${skills_dir}"
    echo "  Copie du plugin vers ${plugins_dir}"
    as_user rm -rf "${plugins_dir}"
    as_user mkdir -p "${plugins_dir}"
    as_user cp "${plugin_src}"/*.py "${plugin_src}"/*.yaml \
        "${plugin_src}/requirements.txt" "${plugins_dir}/"
    copy_plugin_skills_native "${plugin_src}" "${skills_dir}"

    # httpx est déjà une dépendance de Hermès. On ne passe par pip que s'il
    # manque ET que pip existe — l'environnement géré de Hermès n'en a pas.
    python="$(hermes_native_python "${hermes_home}")"
    if python_has_module "${python}" httpx; then
        echo "  httpx déjà présent dans le Python de Hermès — rien à installer."
    elif python_has_pip "${python}"; then
        echo "  Installation de httpx dans le venv Hermès"
        as_user "${python}" -m pip install --quiet httpx || \
            echo "    (échec pip httpx — vérifier le venv)"
    else
        echo "    AVERTISSEMENT : httpx absent du Python de Hermès et pas de pip pour" >&2
        echo "    l'installer — le plugin ne se chargera pas. Réparer Hermès (hermes update)." >&2
    fi
}

copy_plugin_skills_native() {
    local plugin_src="$1" skills_dir="$2"
    [[ -d "${plugin_src}/skills" ]] || return 0
    for skill_dir in "${plugin_src}/skills"/*/; do
        [[ -d "${skill_dir}" ]] || continue
        local name; name="$(basename "${skill_dir}")"
        echo "  Copie du skill ${name}"
        as_user rm -rf "${skills_dir:?}/${name}"
        as_user cp -r "${skill_dir}" "${skills_dir}/${name}"
    done
}

# Dépose le plugin DANS le conteneur via docker cp — la seule façon d'écrire
# dans /opt/data quand il appartient à l'uid 10000. On copie d'abord dans un
# staging propre (sans .git/__pycache__/scripts), puis on cp ce staging.
install_plugin_container() {
    local plugin_src="$1" container="$2"
    local staging; staging="$(mktemp -d)"

    cp "${plugin_src}"/*.py "${plugin_src}"/*.yaml \
        "${plugin_src}/requirements.txt" "${staging}/"

    echo "  docker cp du plugin vers ${container}:/opt/data/plugins/hasan_delivery"
    docker exec "${container}" rm -rf /opt/data/plugins/hasan_delivery 2>/dev/null || true
    docker exec "${container}" mkdir -p /opt/data/plugins /opt/data/skills/general
    docker cp "${staging}/." "${container}:/opt/data/plugins/hasan_delivery"

    if [[ -d "${plugin_src}/skills" ]]; then
        for skill_dir in "${plugin_src}/skills"/*/; do
            [[ -d "${skill_dir}" ]] || continue
            local name; name="$(basename "${skill_dir}")"
            echo "  docker cp du skill ${name}"
            docker exec "${container}" rm -rf "/opt/data/skills/general/${name}" 2>/dev/null || true
            docker cp "${skill_dir%/}" "${container}:/opt/data/skills/general/${name}"
        done
    fi

    # Les fichiers copiés par docker cp appartiennent à root dans le conteneur.
    # Hermès tourne en uid 10000 : on rend le tout lisible pour lui, et on
    # aligne le propriétaire sur hermes pour éviter toute surprise d'écriture.
    docker exec "${container}" sh -c \
        'chown -R hermes:hermes /opt/data/plugins/hasan_delivery /opt/data/skills/general 2>/dev/null; \
         chmod -R a+rX /opt/data/plugins/hasan_delivery /opt/data/skills/general' || true

    echo "  httpx déjà présent dans l'image conteneur — rien à installer."
    rm -rf "${staging}"
}

# ─────────────────────── Configuration et activation du plugin ─────────────

# Donne au plugin l'adresse du relay et son jeton d'administration, dans le
# .env de Hermès. Le plugin joint le relay en local (port publié sur
# 127.0.0.1), jamais par l'adresse publique. Aucun jeton de session de
# téléphone n'est nécessaire (voir plugin/hasan_delivery/README.md).
configure_plugin_native() {
    local hermes_home="$1" admin_token="$2"
    local env_file="${hermes_home}/.env"
    upsert_env_var "${env_file}" HASAN_RELAY_URL "http://127.0.0.1:8767"
    upsert_env_var "${env_file}" HASAN_RELAY_ADMIN_TOKEN "${admin_token}"
    echo "  HASAN_RELAY_URL et HASAN_RELAY_ADMIN_TOKEN posés dans ${env_file}"
}

# Les plugins tiers sont désactivés par défaut dans les Hermès récents : sans
# cette étape le plugin est copié mais jamais chargé.
enable_plugin_native() {
    local user_home="$1" hermes_bin=""
    if [[ -x "${user_home}/.local/bin/hermes" ]]; then
        hermes_bin="${user_home}/.local/bin/hermes"
    elif command -v hermes >/dev/null 2>&1; then
        hermes_bin="$(command -v hermes)"
    fi
    if [[ -z "${hermes_bin}" ]]; then
        echo "    (commande hermes introuvable — activer à la main : hermes plugins enable hasan_delivery)"
        return 0
    fi
    if as_user "${hermes_bin}" plugins enable hasan_delivery >/dev/null 2>&1; then
        echo "  Plugin activé (hermes plugins enable hasan_delivery)."
    else
        echo "    (« hermes plugins enable hasan_delivery » a échoué — sans effet sur un"
        echo "     Hermès ancien qui charge tous les plugins ; sinon l'activer à la main.)"
    fi
}

# ─────────────────────── Redémarrage de Hermès ─────────────────────────────

# Hermès ne scanne plugins/ qu'au démarrage : il faut le redémarrer pour qu'il
# découvre le plugin fraîchement déposé.
restart_hermes() {
    local mode="$1" container="$2"
    case "${mode}" in
        container)
            echo "  docker restart ${container}"
            docker restart "${container}" >/dev/null 2>&1 || \
                echo "    (échec — redémarrer manuellement : docker restart ${container})"
            ;;
        native)
            # Le gateway Hermès tourne en service utilisateur (systemd --user).
            echo "  Redémarrage du gateway Hermès (systemctl --user)"
            user_systemctl restart hermes-gateway.service 2>/dev/null || \
                echo "    (service hermes-gateway introuvable — redémarrer Hermès à la main)"
            ;;
    esac
}

# ─────────────────────── Résumé final ──────────────────────────────────────

print_summary() {
    cat <<EOF

═══════════════════════════════════════════════════════════════════
Déploiement terminé — mode Hermès : ${HERMES_MODE}

  Relay (Android)     : https://${RELAY_HOST}
  Chat (hermes-webui) : ${WEBUI_URL}$( [[ -z "${WEBUI_PASSWORD}" ]] && echo "  (login à saisir dans l'app)" )

  RELAY_ADMIN_TOKEN (à conserver, affiché une seule fois) :
    ${RELAY_ADMIN_TOKEN}
EOF

    if [[ "${CADDY_MODE}" != "own" ]]; then
        cat <<EOF

  Un reverse proxy existant tient le port 443 (${CADDY_443_HOLDER}) : il a
  été réutilisé. Ajoutez ces deux blocs à son Caddyfile, puis rechargez-le.
  Aucune directive tls : les deux noms doivent être couverts par un
  certificat reconnu (celui que ce Caddy obtient déjà pour vos autres sites).

$(caddy_site_blocks "${RELAY_HOST}" "${RELAY_UPSTREAM}" "${WEBUI_HOST}" "${WEBUI_UPSTREAM}" | sed 's/^/    /')
EOF
    else
        cat <<EOF

  Caddy a été lancé par cet installateur. Il demande lui-même un certificat
  Let's Encrypt pour ces deux noms : ils doivent pointer vers ce serveur et
  les ports 80 et 443 doivent être joignables depuis Internet. Sinon
  (serveur privé, Tailscale), utilisez un Caddy à vous avec un challenge
  DNS — l'installateur le réutilisera à la prochaine exécution.
EOF
    fi

    if [[ "${CADDY_MODE}" == "reuse-bridge" && "${HERMES_MODE}" == "native" ]]; then
        cat <<EOF

  Pare-feu : ce Caddy est sur le réseau Docker « ${CADDY_NETWORK} » et joint
  hermes-webui par la passerelle de ce réseau (${WEBUI_BIND_HOST}:8787). Si un
  pare-feu est actif sur l'hôte, autorisez ce seul sous-réseau, par exemple :

    sudo ufw allow from ${CADDY_SUBNET:-<sous-réseau du réseau Docker>} to ${WEBUI_BIND_HOST} port 8787 proto tcp
EOF
    fi

    if [[ "${CADDY_MODE}" == "reuse-bridge" && "${HERMES_MODE}" == "container" ]]; then
        cat <<EOF

  Le conteneur Hermès (${HERMES_CONTAINER}) doit être attaché au réseau
  Docker « ${CADDY_NETWORK} » pour que ce Caddy joigne hermes-webui par son
  nom. Cette combinaison n'a pas été testée : vérifier avec
    docker network connect ${CADDY_NETWORK} ${HERMES_CONTAINER}
EOF
    fi

    if [[ "${HERMES_MODE}" == "container" ]]; then
        cat <<EOF

  Plugin : ajoutez ces variables à l'environnement du conteneur Hermès
  (son compose ou son .env), puis redémarrez-le. Le relay est publié sur
  127.0.0.1:8767 de l'hôte ; adaptez l'adresse si Hermès n'est pas en
  network_mode: host.

    HASAN_RELAY_URL=http://127.0.0.1:8767
    HASAN_RELAY_ADMIN_TOKEN=<le jeton ci-dessus>
EOF
    fi

    cat <<EOF

  Code de pairing (à scanner en QR depuis l'app, valable 10 minutes) :
    ${PAIRING_JSON:-échec de génération — relancer POST /pairing/create}

  L'app n'accepte que des adresses https:// sous un nom de domaine dont le
  certificat est reconnu par Android. N'ouvrez que le port 443 ; 8767 et
  8787 ne doivent jamais être joignables depuis l'extérieur.

  Diagnostic :
    docker compose -p ${COMPOSE_PROJECT} ps
    docker compose -p ${COMPOSE_PROJECT} logs -f relay
EOF
    if [[ "${HERMES_MODE}" == "native" ]]; then
        cat <<EOF
    systemctl --user status hermes-webui hermes-gateway
    journalctl --user -u hermes-webui -f
EOF
    fi
    echo "═══════════════════════════════════════════════════════════════════"
}
