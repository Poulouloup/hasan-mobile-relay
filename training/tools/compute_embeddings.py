"""
Etape 3 — Calcul des embeddings pour tous les fichiers audio.

Pipeline identique au service Android :
  audio 16kHz -> melspectrogram.onnx -> embedding_model.onnx -> vecteur 96-dim

Pour chaque clip de ~2s on produit une fenetre de 16 embeddings consecutifs
(stride 80ms), ce qui forme un exemple [16, 96] pour le classificateur.

Sources traitees :
  hasan_training/my_voice/*.wav  → positifs reels
  synthetic_data/positive/*.wav  → positifs synthetiques
  negative_data/**/*.wav         → negatifs

Sortie :
  embeddings/positive_embeddings.npy   shape (N_pos, 16, 96)
  embeddings/negative_embeddings.npy   shape (N_neg, 16, 96)
  embeddings/real_embeddings.npy       shape (30, 16, 96)  ← samples reels uniquement

Usage :
  python tools/compute_embeddings.py

Duree estimee : ~8 min pour ~3500 clips (CPU Windows)
"""

import wave
import random
import numpy as np
import scipy.signal
import onnxruntime as ort
from pathlib import Path

# ─────────────────────────── CHEMINS ──────────────────────────────────────────

ROOT    = Path(__file__).parent.parent.parent  # racine projet
TRAINING_ROOT = Path(__file__).parent.parent  # training/
ASSETS  = ROOT / "app" / "src" / "main" / "assets"
OUT_DIR = ROOT / "embeddings"
OUT_DIR.mkdir(exist_ok=True)

MEL_MODEL = str(ASSETS / "melspectrogram.onnx")
EMB_MODEL = str(ASSETS / "embedding_model.onnx")

VOICE_DIR  = TRAINING_ROOT / "hasan_training" / "my_voice"
SYNTH_DIR  = TRAINING_ROOT / "synthetic_data" / "positive"
NEG_DIRS   = [
    TRAINING_ROOT / "negative_data" / "speech",
    TRAINING_ROOT / "negative_data" / "noise",
    TRAINING_ROOT / "negative_data" / "context",
]

# ─────────────────────────── PARAMS PIPELINE ──────────────────────────────────

SAMPLE_RATE    = 16000
STRIDE         = 1280       # 80ms entre embeddings
MEL_SAMPLES    = 12640      # samples pour 1 embedding
EMBEDDING_DIM  = 96
HISTORY_LEN    = 16         # nb d'embeddings par exemple
CLIP_SAMPLES   = MEL_SAMPLES + (HISTORY_LEN - 1) * STRIDE  # 32960 ≈ 2.06s

# ─────────────────────────── PIPELINE ONNX ────────────────────────────────────

class EmbeddingPipeline:
    def __init__(self):
        print("  Chargement melspectrogram.onnx et embedding_model.onnx...")
        opts = ort.SessionOptions()
        opts.intra_op_num_threads = 2
        opts.log_severity_level   = 3
        self.mel = ort.InferenceSession(MEL_MODEL, opts)
        self.emb = ort.InferenceSession(EMB_MODEL, opts)
        self.mel_in  = self.mel.get_inputs()[0].name
        self.mel_out = self.mel.get_outputs()[0].name
        self.emb_in  = self.emb.get_inputs()[0].name
        self.emb_out = self.emb.get_outputs()[0].name
        print(f"  mel : in={self.mel_in!r} out={self.mel_out!r}")
        print(f"  emb : in={self.emb_in!r} out={self.emb_out!r}")

    def _one_embedding(self, audio_chunk):
        """float32[MEL_SAMPLES] -> float32[EMBEDDING_DIM]"""
        chunk = audio_chunk[np.newaxis, :]
        mo    = self.mel.run([self.mel_out], {self.mel_in: chunk})[0]   # [1,1,76,32]
        mf    = mo.reshape(1, mo.shape[2], mo.shape[3], 1)              # [1,76,32,1]
        eo    = self.emb.run([self.emb_out], {self.emb_in: mf})[0]      # [1,1,1,96]
        return eo.reshape(-1)[:EMBEDDING_DIM]

    def compute(self, audio):
        """
        audio : float32[>= CLIP_SAMPLES]
        retourne : float32[HISTORY_LEN, EMBEDDING_DIM]
        """
        if len(audio) < CLIP_SAMPLES:
            # Padding bruit si le clip est trop court
            pad = (np.random.randn(CLIP_SAMPLES) * 0.002).astype(np.float32)
            pad[:len(audio)] = audio
            audio = pad
        history = np.zeros((HISTORY_LEN, EMBEDDING_DIM), dtype=np.float32)
        for i in range(HISTORY_LEN):
            start = i * STRIDE
            history[i] = self._one_embedding(audio[start:start + MEL_SAMPLES])
        return history


# ─────────────────────────── UTILS ────────────────────────────────────────────

def load_wav_16k(path):
    """Charge un WAV et retourne float32 16kHz mono."""
    with wave.open(str(path), "rb") as wf:
        src_rate  = wf.getframerate()
        n_ch      = wf.getnchannels()
        sampwidth = wf.getsampwidth()
        raw       = wf.readframes(wf.getnframes())
    if sampwidth == 2:
        samples = np.frombuffer(raw, dtype=np.int16).astype(np.float32) / 32768.0
    elif sampwidth == 4:
        samples = np.frombuffer(raw, dtype=np.int32).astype(np.float32) / 2147483648.0
    else:
        raise ValueError(f"sampwidth {sampwidth} non supporte")
    if n_ch == 2:
        samples = samples.reshape(-1, 2).mean(axis=1)
    if src_rate != SAMPLE_RATE:
        samples = scipy.signal.resample_poly(
            samples, SAMPLE_RATE, src_rate
        ).astype(np.float32)
    return samples


def process_directory(pipeline, wav_dir, label_str, max_files=None):
    """
    Calcule les embeddings de tous les WAV dans wav_dir.
    Retourne (embeddings np.array[N,16,96], n_errors).
    """
    files = sorted(wav_dir.glob("*.wav"))
    if max_files:
        files = files[:max_files]
    if not files:
        print(f"  Aucun fichier dans {wav_dir} — ignoré")
        return np.empty((0, HISTORY_LEN, EMBEDDING_DIM), dtype=np.float32), 0

    embeddings = []
    errors     = 0
    for i, f in enumerate(files):
        try:
            audio = load_wav_16k(f)
            emb   = pipeline.compute(audio)
            embeddings.append(emb)
        except Exception as e:
            errors += 1
        if (i + 1) % 200 == 0 or (i + 1) == len(files):
            print(f"  {label_str}: {i + 1}/{len(files)}  erreurs={errors}")

    arr = np.array(embeddings, dtype=np.float32) if embeddings else \
          np.empty((0, HISTORY_LEN, EMBEDDING_DIM), dtype=np.float32)
    return arr, errors


# ─────────────────────────── MAIN ─────────────────────────────────────────────

def main():
    print("=== [Etape 3] Calcul des embeddings ===")
    print(f"Sortie : {OUT_DIR}")
    print(f"Duree estimee : ~8 min\n")

    pipeline = EmbeddingPipeline()
    print()

    # ── Positifs reels ─────────────────────────────────────────────────────────
    print("--- Samples reels (hasan_training/my_voice) ---")
    real_emb, _ = process_directory(pipeline, VOICE_DIR, "samples reels")
    np.save(str(OUT_DIR / "real_embeddings.npy"), real_emb)
    print(f"  Sauvegarde : real_embeddings.npy  shape={real_emb.shape}")

    # ── Positifs synthetiques ──────────────────────────────────────────────────
    print("\n--- Positifs synthetiques (synthetic_data/positive) ---")
    synth_emb, _ = process_directory(pipeline, SYNTH_DIR, "positifs synthetiques")

    # Combine reels + synthetiques
    if real_emb.shape[0] > 0 and synth_emb.shape[0] > 0:
        pos_emb = np.concatenate([real_emb, synth_emb], axis=0)
    elif real_emb.shape[0] > 0:
        pos_emb = real_emb
    else:
        pos_emb = synth_emb

    np.save(str(OUT_DIR / "positive_embeddings.npy"), pos_emb)
    print(f"  Sauvegarde : positive_embeddings.npy  shape={pos_emb.shape}")
    print(f"  (dont {real_emb.shape[0]} reels + {synth_emb.shape[0]} synthetiques)")

    # ── Negatifs ───────────────────────────────────────────────────────────────
    print("\n--- Negatifs ---")
    neg_parts = []
    for neg_dir in NEG_DIRS:
        if neg_dir.exists():
            part, _ = process_directory(pipeline, neg_dir, f"neg/{neg_dir.name}")
            if part.shape[0] > 0:
                neg_parts.append(part)
        else:
            print(f"  {neg_dir} absent — ignore")

    if neg_parts:
        neg_emb = np.concatenate(neg_parts, axis=0)
    else:
        neg_emb = np.empty((0, HISTORY_LEN, EMBEDDING_DIM), dtype=np.float32)

    np.save(str(OUT_DIR / "negative_embeddings.npy"), neg_emb)
    print(f"  Sauvegarde : negative_embeddings.npy  shape={neg_emb.shape}")

    # ── Statistiques ──────────────────────────────────────────────────────────
    print("\n--- Statistiques ---")
    print(f"  Positifs : {pos_emb.shape[0]}  (reels={real_emb.shape[0]}, synthetiques={synth_emb.shape[0]})")
    print(f"  Negatifs : {neg_emb.shape[0]}")
    if pos_emb.shape[0] > 0:
        print(f"  Embedding pos mean : {pos_emb.mean():.4f}  std : {pos_emb.std():.4f}")
    if neg_emb.shape[0] > 0:
        print(f"  Embedding neg mean : {neg_emb.mean():.4f}  std : {neg_emb.std():.4f}")

    print(f"\n[3/6] Termine -> embeddings/ pret pour l'entrainement")
    return pos_emb.shape[0], neg_emb.shape[0]


if __name__ == "__main__":
    main()
