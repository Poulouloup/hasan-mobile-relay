"""
Etape 1 — Generation des donnees synthetiques positives "ok hasan".

Utilise Windows SAPI directement via win32com.client (meme backend que
train_ok_hasan.py) — environ 2 clips/sec, soit ~15 min pour 2000 clips.

pyttsx3 est intentionnellement evite : il reinitialise le moteur SAPI
a chaque appel save_to_file/runAndWait, ce qui le rend 100x plus lent.

Usage :
  pip install pywin32 scipy
  python tools/generate_synthetic.py

Sortie : synthetic_data/positive/*.wav  (16kHz, mono, 16bit)
Duree estimee : ~15 min pour 2000 clips (CPU Windows)
"""

import os
import wave
import random
import tempfile
import numpy as np
import scipy.signal
from pathlib import Path

# ─────────────────────────── CHEMINS ──────────────────────────────────────────

ROOT    = Path(__file__).parent.parent.parent  # racine projet
TRAINING_ROOT = Path(__file__).parent.parent  # training/
OUT_DIR = TRAINING_ROOT / "synthetic_data" / "positive"
OUT_DIR.mkdir(parents=True, exist_ok=True)

# ─────────────────────────── PARAMS ───────────────────────────────────────────

SAMPLE_RATE    = 16000
TARGET_SAMPLES = 32000   # 2s

POSITIVE_VARIANTS = [
    "ok hasan",
    "ok hasan",
    "ok hasan",
    "okay hasan",
    "ok hassan",
    "hasan",
    "o k hasan",
]

# Rate SAPI : -10 (tres lent) a +10 (tres rapide), 0 = normal
SAPI_RATES  = [-3, -2, -1, 0, 0, 0, 1, 2, 3]
SAPI_VOLUME = [70, 80, 90, 100, 100]

N_SAMPLES = 2000

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
    # Selectionne la voix francaise si disponible
    voices = _sapi_speak.GetVoices()
    for i in range(voices.Count):
        desc = voices.Item(i).GetDescription()
        if "French" in desc or "Hortense" in desc or "Zira" not in desc:
            try:
                _sapi_speak.Voice = voices.Item(i)
                if "French" in desc or "Hortense" in desc:
                    print(f"  Voix SAPI : {desc}")
                    break
            except Exception:
                pass


def sapi_to_pcm16k(text, rate=0, volume=100):
    """Synthetise text via Windows SAPI, resampled a 16kHz mono float32."""
    _init_sapi()
    tmp = tempfile.mktemp(suffix=".wav")
    try:
        _sapi_stream.Open(tmp, 3)          # 3 = SSFMCreateForWrite
        _sapi_speak.AudioOutputStream = _sapi_stream
        _sapi_speak.Rate   = rate
        _sapi_speak.Volume = volume
        _sapi_speak.Speak(text)
        _sapi_stream.Close()
        with wave.open(tmp) as w:
            src_rate = w.getframerate()
            raw      = w.readframes(w.getnframes())
        samples = np.frombuffer(raw, dtype=np.int16).astype(np.float32) / 32768.0
        if src_rate != SAMPLE_RATE:
            samples = scipy.signal.resample_poly(
                samples, SAMPLE_RATE, src_rate
            ).astype(np.float32)
        return samples
    finally:
        if os.path.exists(tmp):
            try:
                os.remove(tmp)
            except OSError:
                pass


def augment(audio):
    audio = audio * random.uniform(0.5, 1.4)
    audio = audio + (np.random.randn(len(audio)) * random.uniform(0.0, 0.008)).astype(np.float32)
    return audio.clip(-1.0, 1.0)


def make_clip(audio, target_len):
    n = len(audio)
    if n >= target_len:
        start = random.randint(0, n - target_len)
        return audio[start:start + target_len].copy()
    pad = (np.random.randn(target_len) * 0.003).astype(np.float32)
    offset = random.randint(0, target_len - n)
    pad[offset:offset + n] = audio
    return pad


def write_wav(path, audio_float32):
    samples_int16 = (audio_float32 * 32767).clip(-32768, 32767).astype(np.int16)
    with wave.open(str(path), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SAMPLE_RATE)
        w.writeframes(samples_int16.tobytes())


# ─────────────────────────── MAIN ─────────────────────────────────────────────

def main():
    print("=== [Etape 1] Generation des donnees synthetiques positives ===")
    print(f"Sortie        : {OUT_DIR}")
    print(f"Nb samples    : {N_SAMPLES}")
    print(f"Backend TTS   : Windows SAPI (win32com)")
    print(f"Duree estimee : ~15 min\n")

    random.seed(42)
    np.random.seed(42)

    existing = len(list(OUT_DIR.glob("*.wav")))
    if existing > 0:
        print(f"  {existing} fichiers deja presents — reprise a partir de {existing + 1}")
    generated = existing
    errors    = 0

    while generated < N_SAMPLES:
        text   = random.choice(POSITIVE_VARIANTS)
        rate   = random.choice(SAPI_RATES)
        volume = random.choice(SAPI_VOLUME)
        try:
            audio = sapi_to_pcm16k(text, rate=rate, volume=volume)
            audio = augment(audio)
            audio = make_clip(audio, TARGET_SAMPLES)
            write_wav(OUT_DIR / f"pos_{generated + 1:04d}.wav", audio)
            generated += 1
            if generated % 200 == 0 or generated == N_SAMPLES:
                print(f"  Positifs : {generated}/{N_SAMPLES}  (\"{text}\" rate={rate})")
        except Exception as e:
            errors += 1
            if errors > 30:
                print(f"  Trop d erreurs ({e}) — arret a {generated}")
                break

    print(f"\n  Genere : {generated} clips  (erreurs: {errors})")
    print(f"[1/6] Termine -> {generated} fichiers dans {OUT_DIR}")
    return generated


if __name__ == "__main__":
    main()
