"""
Serveur de simulation Hermes — API compatible OpenAI Chat Completions.

Usage :
    python server.py

Prérequis :
    pip install fastapi uvicorn

Certificat TLS auto-signé (voir README.md pour la commande openssl).
"""

import asyncio
import json
import time
from pathlib import Path

# Chemins relatifs au script, quel que soit le dossier de lancement
_HERE = Path(__file__).parent
from fastapi import FastAPI, HTTPException, Request, Depends
from fastapi.responses import StreamingResponse
from fastapi.security import HTTPBearer, HTTPAuthorizationCredentials

# --- Configuration ---
AUTH_TOKEN = "HASAN_DEV_TOKEN"
HOST = "0.0.0.0"
PORT = 8443
CERT_FILE = str(_HERE / "cert.pem")
KEY_FILE  = str(_HERE / "key.pem")

# --- Application ---
app = FastAPI(title="Hermes Simulator", version="0.1.0")
security = HTTPBearer()


def verify_token(credentials: HTTPAuthorizationCredentials = Depends(security)):
    """Vérifie le token Bearer. Lève 401 si invalide."""
    if credentials.credentials != AUTH_TOKEN:
        raise HTTPException(status_code=401, detail="Token invalide")
    return credentials.credentials


@app.get("/health")
async def health():
    """Endpoint de vérification de vie — appelé périodiquement par l'app Android."""
    return {"status": "ok", "timestamp": time.time()}


@app.post("/v1/chat/completions")
async def chat_completions(request: Request, token: str = Depends(verify_token)):
    """
    Simule l'endpoint OpenAI Chat Completions avec streaming SSE.
    Reçoit le message de l'utilisateur et renvoie une réponse mot par mot.
    """
    body = await request.json()

    # Extrait le dernier message utilisateur
    messages = body.get("messages", [])
    if not messages:
        raise HTTPException(status_code=400, detail="Champ 'messages' manquant")

    user_text = messages[-1].get("content", "")
    stream = body.get("stream", False)

    print(f"\n[Hasan] Reçu : {user_text!r}")

    # Réponse simulée
    response_text = f"J'ai bien reçu : {user_text}. quoicoubeh"

    if stream:
        return StreamingResponse(
            _stream_response(response_text),
            media_type="text/event-stream",
            headers={
                "Cache-Control": "no-cache",
                "X-Accel-Buffering": "no",
            },
        )
    else:
        # Réponse non-streaming (compatibilité)
        return {
            "id": f"chatcmpl-sim-{int(time.time())}",
            "object": "chat.completion",
            "created": int(time.time()),
            "model": "hermes-agent",
            "choices": [
                {
                    "index": 0,
                    "message": {"role": "assistant", "content": response_text},
                    "finish_reason": "stop",
                }
            ],
        }


async def _stream_response(text: str):
    """
    Générateur SSE : envoie le texte mot par mot au format OpenAI streaming.
    Chaque token est un mot suivi d'un espace, sauf le dernier.
    """
    words = text.split(" ")

    for i, word in enumerate(words):
        # Reconstitue l'espace entre les mots
        token = word if i == len(words) - 1 else word + " "

        chunk = {
            "id": f"chatcmpl-sim-{int(time.time())}",
            "object": "chat.completion.chunk",
            "created": int(time.time()),
            "model": "hermes-agent",
            "choices": [
                {
                    "index": 0,
                    "delta": {"content": token},
                    "finish_reason": None,
                }
            ],
        }
        yield f"data: {json.dumps(chunk, ensure_ascii=False)}\n\n"
        # Délai simulant la vitesse de génération (~80 mots/seconde)
        await asyncio.sleep(0.05)

    # Chunk final : finish_reason = "stop"
    stop_chunk = {
        "id": f"chatcmpl-sim-{int(time.time())}",
        "object": "chat.completion.chunk",
        "created": int(time.time()),
        "model": "hermes-agent",
        "choices": [{"index": 0, "delta": {}, "finish_reason": "stop"}],
    }
    yield f"data: {json.dumps(stop_chunk)}\n\n"
    yield "data: [DONE]\n\n"


if __name__ == "__main__":
    import uvicorn
    import ssl
    import os

    if not os.path.exists(CERT_FILE) or not os.path.exists(KEY_FILE):
        print(f"[Erreur] Certificat TLS introuvable ({CERT_FILE} / {KEY_FILE})")
        print("Générez-le avec :")
        print(f'  openssl req -x509 -newkey rsa:4096 -keyout {KEY_FILE} -out {CERT_FILE} -days 365 -nodes -subj "/CN=hasan-dev"')
        exit(1)

    ssl_ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    ssl_ctx.load_cert_chain(CERT_FILE, KEY_FILE)

    print(f"[Hasan Simulator] Démarrage sur https://{HOST}:{PORT}")
    print(f"[Hasan Simulator] Token : {AUTH_TOKEN}")

    uvicorn.run(
        app,
        host=HOST,
        port=PORT,
        ssl_certfile=CERT_FILE,
        ssl_keyfile=KEY_FILE,
        log_level="info",
    )
