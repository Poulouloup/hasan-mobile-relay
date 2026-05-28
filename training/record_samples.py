import sounddevice as sd
import scipy.io.wavfile as wav
import numpy as np
import os
import time

SAMPLE_RATE = 16000
DURATION = 2  # secondes par enregistrement
OUTPUT_DIR = "hasan_training/my_voice"
os.makedirs(OUTPUT_DIR, exist_ok=True)

print("=== Enregistrement wake word 'ok Hasan' ===")
print(f"Chaque enregistrement dure {DURATION} secondes.")
print("Appuie sur ENTRÉE, attends le BIP, dis 'ok Hasan', attends la fin.\n")

def beep(freq=880, duration=0.15, volume=0.6):
    t = np.linspace(0, duration, int(SAMPLE_RATE * duration), endpoint=False)
    tone = (np.sin(2 * np.pi * freq * t) * volume * 32767).astype(np.int16)
    sd.play(tone, samplerate=SAMPLE_RATE)
    sd.wait()

count = len(os.listdir(OUTPUT_DIR)) + 1

while True:
    input(f"[{count}/30] Appuie sur ENTRÉE pour enregistrer...")
    beep()
    print("🔴 Enregistrement...")
    audio = sd.rec(
        int(DURATION * SAMPLE_RATE),
        samplerate=SAMPLE_RATE,
        channels=1,
        dtype='int16'
    )
    sd.wait()
    filename = f"{OUTPUT_DIR}/sample_{count:03d}.wav"
    wav.write(filename, SAMPLE_RATE, audio)
    print(f"✅ Sauvegardé : {filename}")
    count += 1
    if count > 30:
        print("\n✅ 30 samples enregistrés. C'est terminé !")
        break