# Image Hermès dérivée : Hermès officiel + hermes-webui supervisé.
#
# Pourquoi une image dérivée plutôt qu'un conteneur webui à part :
# hermes-webui n'est PAS un service autonome — il importe massivement le code
# interne de Hermès (from agent.*, from hermes_cli.*, >100 imports vérifiés).
# Il lui faut donc le venv ET le code source de Hermès sur son chemin, tous
# deux enfermés dans cette image. Le faire tourner "à côté" est impossible ;
# le faire tourner "dans" le conteneur via docker exec le rend non supervisé
# (perdu à chaque redémarrage). Cette image le grave comme service s6, au même
# titre que main-hermes et dashboard déjà présents.
#
# Ce N'EST PAS un fork : webui est cloné depuis l'amont (nesquena/hermes-webui,
# à une version épinglée, voir WEBUI_REF plus bas) et seulement PLACÉ
# ici. Aucun code tiers n'est modifié ni maintenu. À chaque mise à jour, il
# suffit de rebuild : l'image de base fait tout le gros œuvre.
#
# Le plugin hasan_delivery et ses skills, eux, ne sont PAS dans l'image : ils
# vivent dans le volume /opt/data (déposés par l'installateur), donc
# modifiables sans rebuild. Seul webui, qui a besoin d'être supervisé, est ici.

ARG HERMES_BASE=nousresearch/hermes-agent:latest
FROM ${HERMES_BASE}

# Version de hermes-webui figée au build : une étiquette amont, pas la tête de
# master — un rebuild doit redonner la même image. webui se met à jour EN MÊME
# TEMPS que Hermès (son README le demande) : cette étiquette date du même jour
# que Hermès v0.21.6. Pour une autre image de base, passer
# --build-arg WEBUI_REF=<étiquette>. Même valeur que WEBUI_REF_DEFAULT dans
# server/lib/actions.sh (vérifié par server/lib/selftest.sh).
ARG WEBUI_REF=exp-v0.52.459

USER root

# git n'est pas garanti présent dans l'image de base — on l'installe le temps
# du clone, puis on retire le cache apt pour ne pas gonfler l'image.
RUN if ! command -v git >/dev/null 2>&1; then \
        apt-get update && apt-get install -y --no-install-recommends git && \
        rm -rf /var/lib/apt/lists/*; \
    fi

# Clone dans /opt/hermes/hermes-webui (à côté de agent/ et hermes_cli/, que
# webui importe). Puis installation de ses deux seules dépendances dures
# (pyyaml, cryptography) dans le venv de Hermès — c'est CE venv que webui
# utilise, puisqu'il a besoin des modules agent qui y vivent.
# Le venv de l'image est géré par uv, pas par pip (vérifié : pas de binaire
# pip ni de module pip dans le venv, mais `uv` est présent). On installe donc
# via `uv pip`, en ciblant explicitement ce venv via VIRTUAL_ENV.
RUN git clone --depth 1 --branch "${WEBUI_REF}" \
        https://github.com/nesquena/hermes-webui.git /opt/hermes/hermes-webui && \
    VIRTUAL_ENV=/opt/hermes/.venv uv pip install --no-cache \
        -r /opt/hermes/hermes-webui/requirements.txt

# Service s6 pour webui, calqué sur le service dashboard de l'image de base
# (longrun, activé par variable d'env, drop vers l'utilisateur hermes).
COPY s6-webui/run    /etc/s6-overlay/s6-rc.d/hasan-webui/run
COPY s6-webui/finish /etc/s6-overlay/s6-rc.d/hasan-webui/finish
COPY s6-webui/type   /etc/s6-overlay/s6-rc.d/hasan-webui/type
# Dépendance sur `base` comme les autres services (attend l'init s6 de base).
RUN mkdir -p /etc/s6-overlay/s6-rc.d/hasan-webui/dependencies.d && \
    touch /etc/s6-overlay/s6-rc.d/hasan-webui/dependencies.d/base && \
    chmod +x /etc/s6-overlay/s6-rc.d/hasan-webui/run \
             /etc/s6-overlay/s6-rc.d/hasan-webui/finish && \
    # Active le service dans le bundle "user" — c'est ce dossier qui décide
    # quels services s6 démarre réellement (vérifié : dashboard + main-hermes
    # y sont référencés).
    touch /etc/s6-overlay/s6-rc.d/user/contents.d/hasan-webui

# On ne change PAS l'entrypoint ni le CMD : l'image de base lance /init (s6),
# qui découvrira et supervisera hasan-webui automatiquement.
