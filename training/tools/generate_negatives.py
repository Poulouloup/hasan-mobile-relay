"""
Etape 2 — Generation des donnees negatives (tout sauf "ok hasan").

Sources :
  1. Phrases francaises communes via pyttsx3     → negative_data/speech/   (~1500 clips)
  2. Silence et bruit blanc synthetique (numpy)  → negative_data/noise/    (~500 clips)
  3. Contexte temporel des 30 samples reels      → negative_data/context/  (~60 clips)
     (500ms avant/apres le wake word = audio autour du mot, sans le mot)

Usage :
  python tools/generate_negatives.py

Sortie : negative_data/**/*.wav  (16kHz, mono, 16bit)
Duree estimee : ~5 min sur CPU Windows
"""

import os
import wave
import random
import tempfile
import numpy as np
import scipy.signal
import scipy.io.wavfile
from pathlib import Path

# ─────────────────────────── CHEMINS ──────────────────────────────────────────

ROOT         = Path(__file__).parent.parent.parent  # racine projet
TRAINING_ROOT = Path(__file__).parent.parent  # training/
VOICE_DIR    = TRAINING_ROOT / "hasan_training" / "my_voice"
OUT_SPEECH   = TRAINING_ROOT / "negative_data" / "speech"
OUT_NOISE    = TRAINING_ROOT / "negative_data" / "noise"
OUT_CONTEXT  = TRAINING_ROOT / "negative_data" / "context"

for d in [OUT_SPEECH, OUT_NOISE, OUT_CONTEXT]:
    d.mkdir(parents=True, exist_ok=True)

# ─────────────────────────── PARAMS ───────────────────────────────────────────

SAMPLE_RATE    = 16000
TARGET_SAMPLES = 32000   # 2s

N_SPEECH  = 1500
N_NOISE   = 500
# Contexte : 2 extraits par sample reel (avant + apres)

NEGATIVE_PHRASES = [
    # Commandes courantes
    "bonjour", "au revoir", "merci", "s'il vous plait",
    "comment allez-vous", "comment ca va", "ca va bien",
    "quelle heure est-il", "quelle heure il est",
    "ouvrir la porte", "fermer la porte",
    "allumer la lumiere", "eteindre la lumiere",
    "baisser le volume", "augmenter le volume",
    # Homophones / confusables
    "ok google", "ok siri", "alexa", "hey cortana", "hey siri",
    "hassan", "hassan bonjour", "a san", "casa", "basa",
    "d accord", "tres bien", "entendu", "compris",
    # Phrases diverses
    "bonsoir", "bonne nuit", "bonne journee",
    "un deux trois quatre cinq",
    "la meteo aujourd hui", "quelle temperature dehors",
    "mets de la musique", "arrete la television",
    "je voudrais savoir", "peux-tu m aider",
    "quel temps fait-il", "allume la tele",
    "appelle maman", "envoie un message",
    "rappelle-moi dans dix minutes",
    "quelle est la capitale de la france",
    "combien font deux plus deux",
    "joue du jazz", "mets une alarme",
    "il fait beau aujourd hui", "la circulation est fluide",
    "naviguer vers paris", "calculer un itineraire",
    # Mots isoles
    "lumiere", "television", "ordinateur", "telephone",
    "cuisine", "salon", "chambre", "garage",
    "chaud", "froid", "vite", "lentement",
    "oui", "non", "peut-etre", "jamais",
    "rouge", "bleu", "vert", "jaune",
]

SPEEDS = [-3, -2, -1, 0, 0, 1, 2, 3]   # unite SAPI (-10 a +10)

# ─────────────────────────── UTILS ────────────────────────────────────────────

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


def tts_to_pcm16k(text, speed):
    """speed = SAPI rate (-10 a +10)."""
    _init_sapi()
    tmp = tempfile.mktemp(suffix=".wav")
    try:
        _sapi_stream.Open(tmp, 3)
        _sapi_speak.AudioOutputStream = _sapi_stream
        _sapi_speak.Rate   = speed
        _sapi_speak.Volume = random.randint(70, 100)
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
    audio = audio * random.uniform(0.4, 1.5)
    audio = audio + (np.random.randn(len(audio)) * random.uniform(0.0, 0.01)).astype(np.float32)
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


def load_wav_16k(path):
    """Charge un WAV et le convertit en float32 16kHz mono."""
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
        raise ValueError(f"sampwidth non supporte : {sampwidth}")
    if n_ch == 2:
        samples = samples.reshape(-1, 2).mean(axis=1)
    if src_rate != SAMPLE_RATE:
        samples = scipy.signal.resample_poly(
            samples, SAMPLE_RATE, src_rate
        ).astype(np.float32)
    return samples

# ─────────────────────────── ETAPE 2a : PAROLE SYNTHETIQUE ────────────────────

def generate_speech_negatives():
    print("\n--- [2a] Parole synthetique negative ---")
    print(f"Nb cible : {N_SPEECH}  |  Sortie : {OUT_SPEECH}")

    existing = len(list(OUT_SPEECH.glob("*.wav")))
    if existing > 0:
        print(f"  {existing} fichiers deja presents — reprise a partir de {existing + 1}")

    generated = existing
    errors    = 0

    while generated < N_SPEECH:
        phrase = random.choice(NEGATIVE_PHRASES)
        speed  = random.choice(SPEEDS)
        try:
            audio = tts_to_pcm16k(phrase, speed)
            audio = augment(audio)
            audio = make_clip(audio, TARGET_SAMPLES)
            write_wav(OUT_SPEECH / f"neg_speech_{generated + 1:04d}.wav", audio)
            generated += 1
            if generated % 300 == 0 or generated == N_SPEECH:
                print(f"  Parole : {generated}/{N_SPEECH}")
        except Exception as e:
            errors += 1
            if errors > 60:
                print(f"  Trop d erreurs ({e}) — arret a {generated}")
                break

    print(f"  Genere : {generated} clips parole  (erreurs: {errors})")
    return generated


# ─────────────────────────── ETAPE 2b : BRUIT SYNTHETIQUE ─────────────────────

def generate_noise_negatives():
    print("\n--- [2b] Bruit synthetique ---")
    print(f"Nb cible : {N_NOISE}  |  Sortie : {OUT_NOISE}")

    existing = len(list(OUT_NOISE.glob("*.wav")))
    if existing > 0:
        print(f"  {existing} fichiers deja presents — reprise a partir de {existing + 1}")
        start = existing
    else:
        start = 0

    for i in range(start, N_NOISE):
        clip_type = random.randint(0, 3)

        if clip_type == 0:
            # Silence pur
            audio = np.zeros(TARGET_SAMPLES, dtype=np.float32)

        elif clip_type == 1:
            # Bruit blanc faible
            level = random.uniform(0.001, 0.02)
            audio = (np.random.randn(TARGET_SAMPLES) * level).astype(np.float32)

        elif clip_type == 2:
            # Bruit rose approximatif (bruit blanc filtre passe-bas)
            white = np.random.randn(TARGET_SAMPLES).astype(np.float32)
            b = np.array([1.0, -0.99])
            a = np.array([1.0])
            audio = scipy.signal.lfilter(b, a, white).astype(np.float32)
            audio = audio * random.uniform(0.002, 0.015) / (np.abs(audio).max() + 1e-9)

        else:
            # Bruit avec tonalite (voix en fond simulee)
            freq  = random.uniform(80, 300)
            t     = np.linspace(0, TARGET_SAMPLES / SAMPLE_RATE, TARGET_SAMPLES)
            tone  = np.sin(2 * np.pi * freq * t).astype(np.float32)
            noise = (np.random.randn(TARGET_SAMPLES) * 0.005).astype(np.float32)
            audio = (tone * random.uniform(0.01, 0.05) + noise).astype(np.float32)

        audio = audio.clip(-1.0, 1.0)
        write_wav(OUT_NOISE / f"neg_noise_{i + 1:04d}.wav", audio)

        if (i + 1) % 100 == 0 or (i + 1) == N_NOISE:
            print(f"  Bruit : {i + 1}/{N_NOISE}")

    print(f"  Genere : {N_NOISE} clips bruit")
    return N_NOISE


# ─────────────────────────── ETAPE 2c : CONTEXTE REEL ─────────────────────────

def generate_context_negatives():
    """
    Extrait 500ms avant et apres chaque sample reel "ok hasan".
    Ces zones contiennent la voix de l'utilisateur sans le wake word
    (inspiration, fin de phrase, etc.) — negatifs difficiles.
    """
    print("\n--- [2c] Contexte temporel des samples reels ---")
    print(f"Source : {VOICE_DIR}  |  Sortie : {OUT_CONTEXT}")

    voice_files = sorted(VOICE_DIR.glob("*.wav"))
    if not voice_files:
        print("  Aucun sample reel trouve — etape ignoree")
        return 0

    CONTEXT_MS   = 500
    CONTEXT_SAMP = int(SAMPLE_RATE * CONTEXT_MS / 1000)  # 8000 samples

    generated = 0
    for wav_path in voice_files:
        try:
            audio = load_wav_16k(wav_path)
            n = len(audio)

            # Extrait AVANT (0 → 500ms ou moins si clip trop court)
            if n > CONTEXT_SAMP:
                before = audio[:CONTEXT_SAMP]
                before = make_clip(before, TARGET_SAMPLES)
                before = augment(before)
                write_wav(OUT_CONTEXT / f"ctx_{wav_path.stem}_before.wav", before)
                generated += 1

            # Extrait APRES (500ms depuis la fin)
            if n > CONTEXT_SAMP:
                after = audio[-CONTEXT_SAMP:]
                after = make_clip(after, TARGET_SAMPLES)
                after = augment(after)
                write_wav(OUT_CONTEXT / f"ctx_{wav_path.stem}_after.wav", after)
                generated += 1

        except Exception as e:
            print(f"  Erreur sur {wav_path.name} : {e}")

    print(f"  Genere : {generated} clips contexte (2 par sample reel)")
    return generated


# ─────────────────────────── MAIN ─────────────────────────────────────────────

def main():
    print("=== [Etape 2] Generation des donnees negatives ===")
    print(f"Duree estimee : ~5 min\n")

    random.seed(42)
    np.random.seed(42)

    n_speech  = generate_speech_negatives()
    n_noise   = generate_noise_negatives()
    n_context = generate_context_negatives()

    total = n_speech + n_noise + n_context
    print(f"\n[2/6] Termine -> {total} negatifs")
    print(f"      Parole  : {n_speech}  ({OUT_SPEECH})")
    print(f"      Bruit   : {n_noise}   ({OUT_NOISE})")
    print(f"      Contexte: {n_context}  ({OUT_CONTEXT})")
    return total


if __name__ == "__main__":
    main()
