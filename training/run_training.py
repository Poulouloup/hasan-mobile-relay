"""
Script master — Entrainement complet du wake word "ok hasan".

Enchaine les 6 etapes dans l'ordre avec estimation de duree.

Usage :
  pip install pyttsx3 scikit-learn skl2onnx onnxruntime scipy
  python run_training.py

  # Pour forcer la regeneration de toutes les donnees :
  python run_training.py --reset

  # Pour reprendre depuis une etape specifique :
  python run_training.py --from 3

Duree totale estimee sur CPU Windows sans GPU :
  Etape 1 (positifs synthetiques)  : ~3 min
  Etape 2 (negatifs)               : ~5 min
  Etape 3 (calcul embeddings)      : ~8 min
  Etape 4 (entrainement MLP)       : ~1 min
  Etape 5 (export ONNX)            : <1 min
  Etape 6 (validation)             : ~2 min
  ─────────────────────────────────────────
  TOTAL                            : ~20 min
"""

import sys
import time
import shutil
import argparse
from pathlib import Path

ROOT       = Path(__file__).parent          # training/
PROJECT_ROOT = Path(__file__).parent.parent  # racine du projet (app/src/main/assets/)

# ─────────────────────────── HELPERS ──────────────────────────────────────────

def banner(step, total, title, estimate):
    bar = "─" * 50
    print(f"\n{bar}")
    print(f"[{step}/{total}] {title}")
    print(f"     Duree estimee : {estimate}")
    print(bar)


def elapsed(t0):
    s = time.time() - t0
    if s < 60:
        return f"{s:.0f}s"
    return f"{s//60:.0f}min {s%60:.0f}s"


def check_deps():
    """Verifie les dependances Python requises."""
    missing = []
    for pkg, import_name in [
        ("pyttsx3",     "pyttsx3"),
        ("scipy",       "scipy"),
        ("onnxruntime", "onnxruntime"),
        ("sklearn",     "sklearn"),
        ("skl2onnx",    "skl2onnx"),
        ("numpy",       "numpy"),
    ]:
        try:
            __import__(import_name)
        except ImportError:
            missing.append(pkg)
    if missing:
        print(f"DEPENDANCES MANQUANTES : {', '.join(missing)}")
        print(f"Installer avec :")
        print(f"  pip install {' '.join(missing)}")
        sys.exit(1)


def check_onnx_models():
    """Verifie que les modeles de base sont presents."""
    assets = PROJECT_ROOT / "app" / "src" / "main" / "assets"
    required = ["melspectrogram.onnx", "embedding_model.onnx"]
    missing = [m for m in required if not (assets / m).exists()]
    if missing:
        print(f"MODELES MANQUANTS dans {assets} :")
        for m in missing:
            print(f"  {m}")
        print("\nTelechargez-les depuis :")
        print("  https://github.com/dscripka/openWakeWord/releases/tag/v0.5.1")
        sys.exit(1)


# ─────────────────────────── STEPS ────────────────────────────────────────────

def step1_generate_synthetic():
    from tools.generate_synthetic import main
    return main()


def step2_generate_negatives():
    from tools.generate_negatives import main
    return main()


def step3_compute_embeddings():
    from tools.compute_embeddings import main
    return main()


def step4_train_classifier():
    from tools.train_classifier import main
    return main()


def step5_export_onnx():
    from tools.export_onnx import main
    return main()


def step6_validate():
    from tools.validate_model import main
    return main()


# ─────────────────────────── RESET ────────────────────────────────────────────

def reset_all():
    """Supprime toutes les donnees generees pour repartir de zero."""
    dirs = [
        ROOT / "synthetic_data",
        ROOT / "negative_data",
        ROOT / "embeddings",
    ]
    files = [
        ROOT / "models" / "classifier.pkl",
        ROOT / "models" / "scaler.pkl",
    ]
    print("Reset des donnees generees...")
    for d in dirs:
        if d.exists():
            shutil.rmtree(d)
            print(f"  Supprime : {d}")
    for f in files:
        if f.exists():
            f.unlink()
            print(f"  Supprime : {f}")
    print("Reset termine.\n")


# ─────────────────────────── MAIN ─────────────────────────────────────────────

STEPS = [
    (1, "Generation donnees synthetiques positives",  "~3 min",  step1_generate_synthetic),
    (2, "Generation donnees negatives",               "~5 min",  step2_generate_negatives),
    (3, "Calcul des embeddings",                      "~8 min",  step3_compute_embeddings),
    (4, "Entrainement du classificateur MLP",         "~1 min",  step4_train_classifier),
    (5, "Export ONNX",                                "<1 min",  step5_export_onnx),
    (6, "Validation du modele",                       "~2 min",  step6_validate),
]


def main():
    parser = argparse.ArgumentParser(description="Entrainement wake word ok hasan")
    parser.add_argument("--reset",  action="store_true",
                        help="Supprime toutes les donnees et recommence de zero")
    parser.add_argument("--from",   dest="from_step", type=int, default=1,
                        metavar="N", help="Demarre a partir de l'etape N (1-6)")
    args = parser.parse_args()

    print("=" * 50)
    print("ENTRAINEMENT WAKE WORD 'ok hasan'")
    print("=" * 50)
    print(f"\nDuree totale estimee : ~20 min sur CPU Windows\n")

    check_deps()
    check_onnx_models()

    if args.reset:
        reset_all()

    t_total = time.time()
    results = {}

    for step_num, title, estimate, func in STEPS:
        if step_num < args.from_step:
            print(f"  [SKIP] Etape {step_num} — {title}")
            continue

        banner(step_num, len(STEPS), title, estimate)
        t_step = time.time()
        try:
            result = func()
            results[step_num] = result
            print(f"\n  Etape {step_num} terminee en {elapsed(t_step)}")
        except Exception as e:
            print(f"\n  ERREUR a l'etape {step_num} : {e}")
            print(f"  Arret du pipeline.")
            import traceback
            traceback.print_exc()
            sys.exit(1)

    # ── Rapport final ──────────────────────────────────────────────────────────
    total_time = elapsed(t_total)
    print("\n" + "=" * 50)
    print("ENTRAINEMENT TERMINE")
    print("=" * 50)
    print(f"\nDuree totale : {total_time}")

    onnx_path = PROJECT_ROOT / "app" / "src" / "main" / "assets" / "ok_hasan.onnx"
    if onnx_path.exists():
        size_kb = onnx_path.stat().st_size // 1024
        f1      = results.get(4, "?")
        valid   = results.get(6, False)

        print(f"\nModele : {onnx_path}  ({size_kb} KB)")
        if isinstance(f1, float):
            print(f"F1 train : {f1:.3f}")
        print(f"Validation : {'PASS' if valid else 'FAIL'}")

        if valid:
            print("""
OK — Prochaine etape Android :
  1. Le fichier ok_hasan.onnx est deja dans app/src/main/assets/
  2. Verifier dans HassanWakeWordService.kt :
       private const val WAKE_WORD_MODEL = "ok_hasan.onnx"
  3. Rebuilder l'APK et tester sur le telephone
""")
        else:
            print("""
AVERTISSEMENT — Validation echouee.
  Options :
  - Enregistrer 30 samples supplementaires (relancer record_samples.py)
  - Augmenter N_SAMPLES=3000 dans tools/generate_synthetic.py
  - Ajuster DETECTION_THRESHOLD dans HassanWakeWordService.kt
  - Relancer : python run_training.py --reset
""")
    else:
        print("\nATTENTION : ok_hasan.onnx non genere — verifier les logs ci-dessus")

    return results.get(6, False)


if __name__ == "__main__":
    main()
