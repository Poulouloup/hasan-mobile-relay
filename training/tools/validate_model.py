"""
Etape 6 — Validation du modele ok_hasan.onnx.

Tests effectues :
  1. Sur les 30 samples reels "ok hasan" enregistres a la voix
     → objectif : detecter >= 28/30 (taux >= 93%)
  2. Sur 10 phrases negatives synthetiques
     → objectif : rejeter >= 9/10 (taux >= 90%)
  3. Sur silence et bruit blanc
     → objectif : score < 0.3

Usage :
  python tools/validate_model.py

Prerequis : avoir execute run_training.py (ou les etapes 1-5 dans l'ordre)
"""

import wave
import numpy as np
import scipy.signal
import onnxruntime as ort
from pathlib import Path

# ─────────────────────────── CHEMINS ──────────────────────────────────────────

ROOT      = Path(__file__).parent.parent.parent  # racine projet
TRAINING_ROOT = Path(__file__).parent.parent  # training/
ASSETS    = ROOT / "app" / "src" / "main" / "assets"
VOICE_DIR = TRAINING_ROOT / "hasan_training" / "my_voice"

MEL_MODEL  = str(ASSETS / "melspectrogram.onnx")
EMB_MODEL  = str(ASSETS / "embedding_model.onnx")
WAKE_MODEL = str(ASSETS / "ok_hasan.onnx")

# ─────────────────────────── PARAMS PIPELINE ──────────────────────────────────

SAMPLE_RATE   = 16000
STRIDE        = 1280
MEL_SAMPLES   = 12640
EMBEDDING_DIM = 96
HISTORY_LEN   = 16
CLIP_SAMPLES  = MEL_SAMPLES + (HISTORY_LEN - 1) * STRIDE   # 32960

DETECTION_THRESHOLD = 0.5

# ─────────────────────────── PIPELINE ONNX ────────────────────────────────────

class FullPipeline:
    """
    Pipeline complet : audio -> mel -> embedding -> ok_hasan score.
    Detecte automatiquement le format de sortie du modele ONNX
    (PyTorch : output float32[1,1], skl2onnx : label + probas).
    """
    def __init__(self):
        opts = ort.SessionOptions()
        opts.intra_op_num_threads = 2
        opts.log_severity_level   = 3
        self.mel  = ort.InferenceSession(MEL_MODEL, opts)
        self.emb  = ort.InferenceSession(EMB_MODEL, opts)
        self.wake = ort.InferenceSession(WAKE_MODEL, opts)

        self.mel_in  = self.mel.get_inputs()[0].name
        self.mel_out = self.mel.get_outputs()[0].name
        self.emb_in  = self.emb.get_inputs()[0].name
        self.emb_out = self.emb.get_outputs()[0].name
        self.wake_in = self.wake.get_inputs()[0].name

        # Determine le type de modele wake word
        wake_outputs = self.wake.get_outputs()
        self._wake_output_names = [o.name for o in wake_outputs]
        # skl2onnx produit 2 sorties : [label, probas]
        # PyTorch produit 1 sortie  : [score]
        self._is_sklearn = len(wake_outputs) >= 2
        print(f"  Wake model sorties : {self._wake_output_names}")
        print(f"  Format : {'skl2onnx (sklearn)' if self._is_sklearn else 'PyTorch'}")

    def _one_embedding(self, audio_chunk):
        chunk = audio_chunk[np.newaxis, :]
        mo    = self.mel.run([self.mel_out], {self.mel_in: chunk})[0]
        mf    = mo.reshape(1, mo.shape[2], mo.shape[3], 1)
        eo    = self.emb.run([self.emb_out], {self.emb_in: mf})[0]
        return eo.reshape(-1)[:EMBEDDING_DIM]

    def _build_window(self, audio):
        """Construit le vecteur d'entree [1, 1536] depuis un clip audio."""
        if len(audio) < CLIP_SAMPLES:
            pad = np.zeros(CLIP_SAMPLES, dtype=np.float32)
            pad[:len(audio)] = audio
            audio = pad
        history = np.zeros((HISTORY_LEN, EMBEDDING_DIM), dtype=np.float32)
        for i in range(HISTORY_LEN):
            start = i * STRIDE
            history[i] = self._one_embedding(audio[start:start + MEL_SAMPLES])
        return history.reshape(1, -1).astype(np.float32)   # [1, 1536]

    def score(self, audio):
        """Retourne le score de detection (float entre 0 et 1)."""
        x       = self._build_window(audio)
        results = self.wake.run(None, {self.wake_in: x})
        if self._is_sklearn:
            proba = results[1]   # shape [1, 2]
            return float(proba[0, 1]) if proba.ndim == 2 else float(proba[0])
        else:
            return float(results[0][0][0])


# ─────────────────────────── UTILS ────────────────────────────────────────────

def load_wav_16k(path):
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


_val_sapi_speak = None
_val_sapi_stream = None

def tts_sample(text, rate=0):
    """Genere un clip audio via Windows SAPI pour les tests negatifs."""
    global _val_sapi_speak, _val_sapi_stream
    import os, tempfile, win32com.client
    if _val_sapi_speak is None:
        _val_sapi_speak  = win32com.client.Dispatch("SAPI.SpVoice")
        _val_sapi_stream = win32com.client.Dispatch("SAPI.SpFileStream")
        voices = _val_sapi_speak.GetVoices()
        for i in range(voices.Count):
            desc = voices.Item(i).GetDescription()
            if "French" in desc or "Hortense" in desc:
                _val_sapi_speak.Voice = voices.Item(i)
                break
    tmp = tempfile.mktemp(suffix=".wav")
    try:
        _val_sapi_stream.Open(tmp, 3)
        _val_sapi_speak.AudioOutputStream = _val_sapi_stream
        _val_sapi_speak.Rate = rate
        _val_sapi_speak.Speak(text)
        _val_sapi_stream.Close()
        return load_wav_16k(tmp)
    finally:
        if os.path.exists(tmp):
            try: os.remove(tmp)
            except: pass


# ─────────────────────────── TESTS ────────────────────────────────────────────

def test_real_samples(pipeline):
    """Test 1 : detecte les 30 samples reels enregistres a la voix."""
    print("\n--- Test 1 : Samples reels 'ok hasan' ---")
    files = sorted(VOICE_DIR.glob("*.wav"))
    if not files:
        print("  SKIP — aucun sample dans hasan_training/my_voice/")
        return None

    detected = 0
    for f in files:
        try:
            audio = load_wav_16k(f)
            s     = pipeline.score(audio)
            ok    = s >= DETECTION_THRESHOLD
            if ok:
                detected += 1
            status = "OK" if ok else "MISS"
            print(f"  {f.name} : score={s:.3f}  [{status}]")
        except Exception as e:
            print(f"  {f.name} : ERREUR — {e}")

    rate = detected / len(files) * 100
    result = detected >= 28
    print(f"\n  Detectes : {detected}/{len(files)}  ({rate:.0f}%)")
    print(f"  Resultat : {'PASS' if result else 'FAIL'}  (objectif >= 28/30)")
    return result


def test_negatives(pipeline):
    """Test 2 : rejette 10 phrases negatives synthétiques."""
    print("\n--- Test 2 : Negatifs (pyttsx3) ---")

    test_phrases = [
        "bonjour", "quelle heure est-il", "ok google",
        "allumer la lumiere", "au revoir",
        "hey siri", "hassan", "mets de la musique",
        "la meteo aujourd hui", "merci beaucoup",
    ]

    rejected = 0
    for phrase in test_phrases:
        try:
            audio = tts_sample(phrase, rate=0)
            s     = pipeline.score(audio)
            ok    = s < DETECTION_THRESHOLD
            if ok:
                rejected += 1
            status = "OK (rejet)" if ok else "FAUX POSITIF"
            print(f"  '{phrase}' : score={s:.3f}  [{status}]")
        except Exception as e:
            print(f"  '{phrase}' : ERREUR — {e}")

    rate   = rejected / len(test_phrases) * 100
    result = rejected >= 9
    print(f"\n  Rejetes : {rejected}/{len(test_phrases)}  ({rate:.0f}%)")
    print(f"  Resultat : {'PASS' if result else 'FAIL'}  (objectif >= 9/10)")
    return result


def test_silence_noise(pipeline):
    """Test 3 : scores bas sur silence et bruit blanc."""
    print("\n--- Test 3 : Silence et bruit ---")

    silence = np.zeros(CLIP_SAMPLES, dtype=np.float32)
    noise   = (np.random.randn(CLIP_SAMPLES) * 0.01).astype(np.float32)

    s_silence = pipeline.score(silence)
    s_noise   = pipeline.score(noise)

    ok_silence = s_silence < 0.3
    ok_noise   = s_noise   < 0.3

    print(f"  Silence     : score={s_silence:.4f}  {'OK' if ok_silence else 'FAIL (score trop haut)'}")
    print(f"  Bruit blanc : score={s_noise:.4f}  {'OK' if ok_noise   else 'FAIL (score trop haut)'}")

    return ok_silence and ok_noise


# ─────────────────────────── MAIN ─────────────────────────────────────────────

def main():
    print("=== [Etape 6] Validation du modele ok_hasan.onnx ===\n")

    if not Path(WAKE_MODEL).exists():
        raise FileNotFoundError(f"Modele manquant : {WAKE_MODEL}")

    pipeline = FullPipeline()

    r1 = test_real_samples(pipeline)
    r2 = test_negatives(pipeline)
    r3 = test_silence_noise(pipeline)

    print("\n" + "=" * 50)
    print("RAPPORT DE VALIDATION")
    print("=" * 50)

    tests = [
        ("Samples reels (>= 28/30)",    r1),
        ("Negatifs TTS (>= 9/10)",      r2),
        ("Silence/bruit (score < 0.3)", r3),
    ]
    passed = 0
    for name, result in tests:
        if result is None:
            status = "SKIP"
        elif result:
            status = "PASS"
            passed += 1
        else:
            status = "FAIL"
        print(f"  {status:5s}  {name}")

    valid_tests = [r for r in [r1, r2, r3] if r is not None]
    all_pass    = all(valid_tests)

    print()
    if all_pass:
        print("MODELE VALIDE")
        print(f"Copier dans app/src/main/assets/ok_hasan.onnx (deja fait)")
        print(f"Mettre WAKE_WORD_MODEL = \"ok_hasan.onnx\" dans HassanWakeWordService.kt")
    else:
        print("MODELE A AMELIORER")
        print("Conseils :")
        if r1 is not None and not r1:
            print("  - Rappel trop bas : augmenter N_SAMPLES dans generate_synthetic.py")
            print("    et/ou baisser DETECTION_THRESHOLD a 0.4f dans HassanWakeWordService.kt")
        if r2 is not None and not r2:
            print("  - Trop de faux positifs : augmenter N_SPEECH dans generate_negatives.py")
            print("    et/ou augmenter DETECTION_THRESHOLD a 0.6f dans HassanWakeWordService.kt")

    print(f"\n[6/6] Termine")
    return all_pass


if __name__ == "__main__":
    main()
