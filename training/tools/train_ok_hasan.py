"""
Entraine le modele wake word "ok hasan" sur donnees synthetiques Windows SAPI.

Pipeline mesure empiriquement :
  - 12640 samples @ 16kHz -> 76 frames mel -> 1 embedding 96-dim
  - stride : 1280 samples (80ms) entre chaque embedding
  - classificateur : historique de 16 embeddings = fenetre de ~1.8s

Usage :
  cd hasanv1
  python tools/train_ok_hasan.py

Sortie : app/src/main/assets/ok_hasan.onnx
"""

import os, sys, wave, tempfile, random
import numpy as np
import onnxruntime as ort
import torch
import torch.nn as nn
from pathlib import Path

# ─────────────────────────── CHEMINS ──────────────────────────────────────────

ROOT   = Path(__file__).parent.parent.parent
ASSETS = ROOT / "app" / "src" / "main" / "assets"
MODELS = Path(__file__).parent.parent / "models"
MODELS.mkdir(exist_ok=True)

MEL_MODEL = str(ASSETS / "melspectrogram.onnx")
EMB_MODEL = str(ASSETS / "embedding_model.onnx")
OUT_MODEL = str(ASSETS / "ok_hasan.onnx")

# ─────────────────────────── PARAMS PIPELINE ──────────────────────────────────

SAMPLE_RATE   = 16000
STRIDE        = 1280        # 80ms — stride entre embeddings (identique a Android)
MEL_SAMPLES   = 12640       # samples necessaires pour produire 76 frames mel -> 1 embedding
EMBEDDING_DIM = 96
HISTORY_LEN   = 16          # nb d embeddings dans la fenetre du classificateur

# Un exemple d entrainement = HISTORY_LEN embeddings consecutifs
# Audio necessaire pour 1 exemple : MEL_SAMPLES + (HISTORY_LEN-1)*STRIDE = 32960 samples = 2.06s
CLIP_SAMPLES = MEL_SAMPLES + (HISTORY_LEN - 1) * STRIDE   # 32960

# ─────────────────────────── PARAMS ENTRAINEMENT ──────────────────────────────

N_POSITIVE = 600
N_NEGATIVE = 1500
N_POS_VAL  = 120
N_NEG_VAL  = 250

EPOCHS     = 80
BATCH_SIZE = 64
LR         = 3e-4
PATIENCE   = 12

POSITIVE_VARIANTS = [
    "ok hasan", "ok hasan", "ok hasan",
    "o k hasan", "okay hasan",
]

NEGATIVE_PHRASES = [
    "bonjour", "au revoir", "merci", "s'il vous plait",
    "comment allez-vous", "quelle heure est-il",
    "ouvrir la porte", "allumer la lumiere",
    "ok google", "ok siri", "alexa", "hey cortana",
    "hassan", "a san", "casa",
    "bonsoir", "bonne nuit", "d accord", "tres bien",
    "un deux trois", "quatre cinq six",
    "la meteo aujourd hui", "quelle temperature",
    "musique", "television", "ordinateur",
    "je voudrais savoir", "peux-tu m aider",
    "quel temps fait-il", "allume la tele",
]

# ─────────────────────────── SAPI TTS ─────────────────────────────────────────

_sapi_speak = None
_sapi_stream = None

def _init_sapi():
    global _sapi_speak, _sapi_stream
    if _sapi_speak is not None:
        return
    import win32com.client
    _sapi_speak = win32com.client.Dispatch("SAPI.SpVoice")
    _sapi_stream = win32com.client.Dispatch("SAPI.SpFileStream")
    voices = _sapi_speak.GetVoices()
    for i in range(voices.Count):
        desc = voices.Item(i).GetDescription()
        if "French" in desc or "Hortense" in desc:
            _sapi_speak.Voice = voices.Item(i)
            break


def sapi_to_pcm16k(text, rate=0, volume=100):
    """Synthetise text via Windows SAPI FR, resampled a 16kHz mono float32."""
    import scipy.signal
    _init_sapi()
    tmp = tempfile.mktemp(suffix=".wav")
    try:
        _sapi_stream.Open(tmp, 3)
        _sapi_speak.AudioOutputStream = _sapi_stream
        _sapi_speak.Rate   = rate
        _sapi_speak.Volume = volume
        _sapi_speak.Speak(text)
        _sapi_stream.Close()
        with wave.open(tmp) as w:
            src_rate = w.getframerate()
            raw = w.readframes(w.getnframes())
        samples = np.frombuffer(raw, dtype=np.int16).astype(np.float32) / 32768.0
        if src_rate != SAMPLE_RATE:
            samples = scipy.signal.resample_poly(
                samples, SAMPLE_RATE, src_rate
            ).astype(np.float32)
        return samples
    finally:
        if os.path.exists(tmp):
            try: os.remove(tmp)
            except: pass


def make_clip(audio, target_len, noise_floor=0.003):
    """Ajuste la longueur du clip par padding bruit ou crop aleatoire."""
    n = len(audio)
    if n >= target_len:
        start = random.randint(0, n - target_len)
        return audio[start:start + target_len].copy()
    pad = (np.random.randn(target_len) * noise_floor).astype(np.float32)
    offset = random.randint(0, target_len - n)
    pad[offset:offset + n] = audio
    return pad


def augment(audio):
    """Gain aleatoire + bruit blanc additif."""
    audio = audio * random.uniform(0.5, 1.4)
    audio = audio + (np.random.randn(len(audio)) * random.uniform(0.0, 0.01)).astype(np.float32)
    return audio.clip(-1.0, 1.0)

# ─────────────────────────── PIPELINE EMBEDDINGS ──────────────────────────────

class EmbeddingPipeline:
    def __init__(self):
        opts = ort.SessionOptions()
        opts.intra_op_num_threads = 2
        opts.log_severity_level   = 3
        self.mel = ort.InferenceSession(MEL_MODEL, opts)
        self.emb = ort.InferenceSession(EMB_MODEL, opts)
        self.mel_in  = self.mel.get_inputs()[0].name
        self.mel_out = self.mel.get_outputs()[0].name
        self.emb_in  = self.emb.get_inputs()[0].name
        self.emb_out = self.emb.get_outputs()[0].name

    def _one_embedding(self, audio_chunk):
        """audio_chunk : float32[MEL_SAMPLES] -> float32[96]"""
        chunk = audio_chunk[np.newaxis, :]               # [1, 12640]
        mo = self.mel.run([self.mel_out], {self.mel_in: chunk})[0]  # [1,1,76,32]
        mf = mo.reshape(1, mo.shape[2], mo.shape[3], 1)             # [1,76,32,1]
        eo = self.emb.run([self.emb_out], {self.emb_in: mf})[0]     # [1,1,1,96]
        return eo.reshape(-1)[:EMBEDDING_DIM]

    def compute(self, audio):
        """
        audio : float32[CLIP_SAMPLES] (= 32960 samples = 2.06s)
        retourne : float32[HISTORY_LEN, EMBEDDING_DIM] = [16, 96]
        """
        assert len(audio) >= CLIP_SAMPLES, f"Need {CLIP_SAMPLES} samples, got {len(audio)}"
        history = np.zeros((HISTORY_LEN, EMBEDDING_DIM), dtype=np.float32)
        for i in range(HISTORY_LEN):
            start = i * STRIDE
            chunk = audio[start:start + MEL_SAMPLES]
            history[i] = self._one_embedding(chunk)
        return history

# ─────────────────────────── GENERATION DATASET ───────────────────────────────

def gen_samples(pipeline, phrases, n_target, label, desc):
    """Genere n_target exemples [16,96] pour les phrases donnees."""
    X, y = [], []
    rates   = [-3, -2, -1, 0, 0, 1, 2]
    volumes = [70, 80, 90, 100, 100]
    errors  = 0
    done    = 0
    while done < n_target:
        phrase = random.choice(phrases)
        try:
            audio = sapi_to_pcm16k(phrase, rate=random.choice(rates),
                                            volume=random.choice(volumes))
            audio = make_clip(augment(audio), CLIP_SAMPLES)
            emb   = pipeline.compute(audio)
            X.append(emb)
            y.append(label)
            done += 1
            if done % 100 == 0 or done == n_target:
                print(f"  {desc}: {done}/{n_target}")
        except Exception as e:
            errors += 1
            if errors > 30:
                print(f"  Trop d erreurs ({e}) — arret a {done}")
                break
        # Clips silence pour les negatifs
        if label == 0 and random.random() < 0.15:
            try:
                noise = (np.random.randn(CLIP_SAMPLES) * 0.005).astype(np.float32)
                emb   = pipeline.compute(noise)
                X.append(emb)
                y.append(0)
                done += 1
            except Exception:
                pass
    return np.array(X, dtype=np.float32), np.array(y, dtype=np.float32)


def generate_dataset(pipeline):
    print("\n=== Generation des donnees synthetiques ===")
    print(f"Clip : {CLIP_SAMPLES} samples = {CLIP_SAMPLES/SAMPLE_RATE:.2f}s")
    print(f"Train: {N_POSITIVE} pos + {N_NEGATIVE} neg")
    print(f"Val  : {N_POS_VAL} pos + {N_NEG_VAL} neg\n")

    Xtp, ytp = gen_samples(pipeline, POSITIVE_VARIANTS, N_POSITIVE, 1, "train pos")
    Xtn, ytn = gen_samples(pipeline, NEGATIVE_PHRASES,  N_NEGATIVE, 0, "train neg")
    Xvp, yvp = gen_samples(pipeline, POSITIVE_VARIANTS, N_POS_VAL,  1, "val pos")
    Xvn, yvn = gen_samples(pipeline, NEGATIVE_PHRASES,  N_NEG_VAL,  0, "val neg")

    X_train = np.concatenate([Xtp, Xtn])
    y_train = np.concatenate([ytp, ytn])
    X_val   = np.concatenate([Xvp, Xvn])
    y_val   = np.concatenate([yvp, yvn])

    # Shuffle
    idx = np.random.permutation(len(X_train))
    X_train, y_train = X_train[idx], y_train[idx]
    idx = np.random.permutation(len(X_val))
    X_val, y_val = X_val[idx], y_val[idx]

    np.save(str(MODELS / "X_train.npy"), X_train)
    np.save(str(MODELS / "y_train.npy"), y_train)
    np.save(str(MODELS / "X_val.npy"),   X_val)
    np.save(str(MODELS / "y_val.npy"),   y_val)

    print(f"\nTrain: {X_train.shape}  pos={y_train.sum():.0f}")
    print(f"Val  : {X_val.shape}    pos={y_val.sum():.0f}")
    return X_train, y_train, X_val, y_val

# ─────────────────────────── MODELE PYTORCH ───────────────────────────────────

class WakeWordNet(nn.Module):
    def __init__(self):
        super().__init__()
        inp = HISTORY_LEN * EMBEDDING_DIM  # 1536
        self.net = nn.Sequential(
            nn.Flatten(),
            nn.Linear(inp, 128), nn.LayerNorm(128), nn.ReLU(),
            nn.Linear(128, 128), nn.LayerNorm(128), nn.ReLU(),
            nn.Linear(128,  64), nn.ReLU(),
            nn.Linear( 64,   1), nn.Sigmoid(),
        )

    def forward(self, x):
        return self.net(x)

# ─────────────────────────── ENTRAINEMENT ─────────────────────────────────────

def train(X_train, y_train, X_val, y_val):
    print("\n=== Entrainement ===")
    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    print(f"Device : {device}\n")

    model     = WakeWordNet().to(device)
    optimizer = torch.optim.Adam(model.parameters(), lr=LR)
    criterion = nn.BCELoss(reduction="none")

    n_pos = y_train.sum()
    n_neg = len(y_train) - n_pos
    pos_w = torch.tensor([n_neg / max(n_pos, 1)], dtype=torch.float32).to(device)

    Xt = torch.from_numpy(X_train).to(device)
    yt = torch.from_numpy(y_train).to(device)
    Xv = torch.from_numpy(X_val).to(device)
    yv = torch.from_numpy(y_val).to(device)

    best_f1    = 0.0
    best_state = None
    no_improve = 0

    for epoch in range(1, EPOCHS + 1):
        model.train()
        idx = torch.randperm(len(Xt))
        Xt, yt = Xt[idx], yt[idx]
        total_loss, steps = 0.0, 0

        for i in range(0, len(Xt), BATCH_SIZE):
            xb = Xt[i:i + BATCH_SIZE]
            yb = yt[i:i + BATCH_SIZE]
            pred = model(xb).squeeze(1)
            w    = torch.where(yb == 1, pos_w.expand_as(yb), torch.ones_like(yb))
            loss = (criterion(pred, yb) * w).mean()
            optimizer.zero_grad()
            loss.backward()
            optimizer.step()
            total_loss += loss.item(); steps += 1

        model.eval()
        with torch.no_grad():
            vp   = model(Xv).squeeze(1)
            vbin = (vp >= 0.5).float()
            tp   = ((vbin == 1) & (yv == 1)).sum().item()
            fp   = ((vbin == 1) & (yv == 0)).sum().item()
            fn   = ((vbin == 0) & (yv == 1)).sum().item()
            prec   = tp / max(tp + fp, 1)
            recall = tp / max(tp + fn, 1)
            f1     = 2 * prec * recall / max(prec + recall, 1e-6)

        print(f"Epoch {epoch:3d}/{EPOCHS}  loss={total_loss/steps:.4f}"
              f"  prec={prec:.3f}  recall={recall:.3f}  F1={f1:.3f}")

        if f1 > best_f1:
            best_f1    = f1
            best_state = {k: v.clone() for k, v in model.state_dict().items()}
            no_improve = 0
        else:
            no_improve += 1
            if no_improve >= PATIENCE:
                print(f"Early stop epoch {epoch}  best F1={best_f1:.3f}")
                break

    print(f"\nMeilleur F1 validation : {best_f1:.3f}")
    model.load_state_dict(best_state)
    return model

# ─────────────────────────── EXPORT ONNX ──────────────────────────────────────

def export_onnx(model):
    """
    Exporte le modele en ONNX avec l'interface attendue par openwakeword-android-kt :
      Input  : float32[1, 16, 96]  (HISTORY_LEN x EMBEDDING_DIM)
      Output : float32[1, 1]       (score entre 0 et 1)
    """
    model.eval()
    dummy = torch.zeros(1, HISTORY_LEN, EMBEDDING_DIM)

    # API legacy (torch.onnx.utils.export) — evite le bug UnicodeEncodeError
    # de la nouvelle API dynamo sur Windows (emojis dans les logs)
    torch.onnx.utils.export(
        model, (dummy,), OUT_MODEL,
        input_names   = ["input"],
        output_names  = ["output"],
        dynamic_axes  = {"input": {0: "batch_size"}},
        opset_version = 11,
    )
    size_kb = os.path.getsize(OUT_MODEL) // 1024
    print(f"\nModele exporte : {OUT_MODEL}  ({size_kb} KB)")

    # Verification : input rank=3, output rank=2
    sess     = ort.InferenceSession(OUT_MODEL)
    in_name  = sess.get_inputs()[0].name
    out_name = sess.get_outputs()[0].name
    print(f"  Input  : {in_name!r}  shape={sess.get_inputs()[0].shape}")
    print(f"  Output : {out_name!r}  shape={sess.get_outputs()[0].shape}")

    inp = np.zeros((1, HISTORY_LEN, EMBEDDING_DIM), dtype=np.float32)
    out = sess.run([out_name], {in_name: inp})[0]
    print(f"  Score sur silence : {out[0][0]:.4f}  (attendu ~0.0)")

# ─────────────────────────── MAIN ─────────────────────────────────────────────

if __name__ == "__main__":
    random.seed(42)
    np.random.seed(42)
    torch.manual_seed(42)

    print("=== Train ok_hasan.onnx ===")
    print(f"Assets : {ASSETS}")
    print(f"Sortie : {OUT_MODEL}")

    pipeline = EmbeddingPipeline()
    print("Pipeline ONNX initialise.")

    xt_path = MODELS / "X_train.npy"
    if xt_path.exists():
        print("\nDataset existant trouve — rechargement...")
        X_train = np.load(str(MODELS / "X_train.npy"))
        y_train = np.load(str(MODELS / "y_train.npy"))
        X_val   = np.load(str(MODELS / "X_val.npy"))
        y_val   = np.load(str(MODELS / "y_val.npy"))
        print(f"Train: {X_train.shape}  Val: {X_val.shape}")
    else:
        X_train, y_train, X_val, y_val = generate_dataset(pipeline)

    model = train(X_train, y_train, X_val, y_val)
    export_onnx(model)
    print("\nTermine. Changer WAKE_WORD_MODEL = \"ok_hasan.onnx\" dans HassanWakeWordService.kt")
