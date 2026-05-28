"""
Etape 5 — Export du classificateur sklearn en ONNX.

Convertit models/classifier.pkl + models/scaler.pkl en un seul graphe
ONNX (scaler + MLP fusionnes) via skl2onnx.

Le modele ONNX produit attendu :
  Input  : float32[batch, 1536]   (= 16 embeddings * 96 dims)
  Output : float32[batch, 1]      (score entre 0 et 1)

Compatible avec OrtInferenceSession Android —
meme interface que hey_jarvis.onnx et ok_hasan.onnx (ancien modele PyTorch).

Usage :
  pip install skl2onnx
  python tools/export_onnx.py

Sortie : app/src/main/assets/ok_hasan.onnx
Duree estimee : < 30 sec
"""

import pickle
import numpy as np
import onnxruntime as ort
from pathlib import Path
from sklearn.pipeline import Pipeline

# ─────────────────────────── CHEMINS ──────────────────────────────────────────

ROOT       = Path(__file__).parent.parent.parent  # racine projet
TRAINING_ROOT = Path(__file__).parent.parent  # training/
MODELS_DIR = TRAINING_ROOT / "models"
ASSETS_DIR = ROOT / "app" / "src" / "main" / "assets"
EMB_DIR    = TRAINING_ROOT / "embeddings"

CLF_PATH    = MODELS_DIR / "classifier.pkl"
SCALER_PATH = MODELS_DIR / "scaler.pkl"
ONNX_PATH   = ASSETS_DIR / "ok_hasan.onnx"

HISTORY_LEN   = 16
EMBEDDING_DIM = 96
FEATURE_DIM   = HISTORY_LEN * EMBEDDING_DIM  # 1536

# ─────────────────────────── MAIN ─────────────────────────────────────────────

def main():
    print("=== [Etape 5] Export ONNX ===")
    print(f"Duree estimee : < 30 sec\n")

    # ── Chargement des modeles sklearn ────────────────────────────────────────
    if not CLF_PATH.exists():
        raise FileNotFoundError(f"classifier.pkl manquant — executer train_classifier.py")
    if not SCALER_PATH.exists():
        raise FileNotFoundError(f"scaler.pkl manquant — executer train_classifier.py")

    with open(CLF_PATH,    "rb") as f: clf    = pickle.load(f)
    with open(SCALER_PATH, "rb") as f: scaler = pickle.load(f)
    print(f"  Modele charge : {type(clf).__name__}")
    print(f"  Scaler  charge : {type(scaler).__name__}")

    # ── Construction d'un pipeline sklearn (scaler + clf) ─────────────────────
    skl_pipeline = Pipeline([
        ("scaler", scaler),
        ("clf",    clf),
    ])

    # ── Conversion ONNX via skl2onnx ──────────────────────────────────────────
    from skl2onnx import convert_sklearn
    from skl2onnx.common.data_types import FloatTensorType

    print(f"\n  Conversion via skl2onnx...")
    initial_types = [("input", FloatTensorType([None, FEATURE_DIM]))]
    onnx_model    = convert_sklearn(
        skl_pipeline,
        initial_types     = initial_types,
        target_opset      = 15,
        options           = {clf.__class__: {"zipmap": False}},
    )

    # ── Sauvegarde ────────────────────────────────────────────────────────────
    with open(ONNX_PATH, "wb") as f:
        f.write(onnx_model.SerializeToString())
    size_kb = ONNX_PATH.stat().st_size // 1024
    print(f"  Sauvegarde : {ONNX_PATH}  ({size_kb} KB)")

    # ── Verification des sorties ───────────────────────────────────────────────
    print("\n  --- Verification du modele ONNX ---")
    sess     = ort.InferenceSession(str(ONNX_PATH))
    in_name  = sess.get_inputs()[0].name
    out_names= [o.name for o in sess.get_outputs()]
    print(f"  Entree  : {in_name!r}  shape={sess.get_inputs()[0].shape}")
    print(f"  Sorties : {out_names}")

    # Test sur zero (silence)
    zero_input = np.zeros((1, FEATURE_DIM), dtype=np.float32)
    results    = sess.run(None, {in_name: zero_input})
    # La sortie de probabilite est la 2eme (index 1) pour un classifieur sklearn
    # (index 0 = label predit, index 1 = probabilites)
    if len(results) >= 2:
        prob = results[1]  # shape [1, 2] ou [1]
        if prob.ndim == 2:
            score_silence = float(prob[0, 1])   # proba classe 1
        else:
            score_silence = float(prob[0])
    else:
        score_silence = float(results[0][0])
    print(f"  Score sur silence : {score_silence:.4f}  (attendu proche de 0)")

    # Test sur bruit blanc
    rand_input  = np.random.randn(1, FEATURE_DIM).astype(np.float32) * 0.01
    results2    = sess.run(None, {in_name: rand_input})
    if len(results2) >= 2:
        p2 = results2[1]
        score_noise = float(p2[0, 1]) if p2.ndim == 2 else float(p2[0])
    else:
        score_noise = float(results2[0][0])
    print(f"  Score sur bruit   : {score_noise:.4f}  (attendu proche de 0)")

    # ── Verification coherence sklearn vs ONNX ────────────────────────────────
    print("\n  --- Coherence sklearn vs ONNX ---")
    pos_path = EMB_DIR / "positive_embeddings.npy"
    neg_path = EMB_DIR / "negative_embeddings.npy"

    if pos_path.exists() and neg_path.exists():
        pos_emb = np.load(str(pos_path))
        neg_emb = np.load(str(neg_path))

        # Prendre 20 exemples de chaque
        n_check = min(20, pos_emb.shape[0], neg_emb.shape[0])
        check_X = np.concatenate([
            pos_emb[:n_check].reshape(n_check, -1),
            neg_emb[:n_check].reshape(n_check, -1),
        ], axis=0)
        check_y = np.concatenate([
            np.ones(n_check, dtype=np.int32),
            np.zeros(n_check, dtype=np.int32),
        ])

        # Predictions sklearn
        skl_pred = skl_pipeline.predict(check_X)

        # Predictions ONNX
        onnx_results = sess.run(None, {in_name: check_X.astype(np.float32)})
        if len(onnx_results) >= 2:
            p = onnx_results[1]
            onnx_scores = p[:, 1] if p.ndim == 2 else p.flatten()
        else:
            onnx_scores = onnx_results[0].flatten()
        onnx_pred = (onnx_scores >= 0.5).astype(np.int32)

        match = np.sum(skl_pred == onnx_pred)
        print(f"  Coherence sklearn/ONNX : {match}/{len(check_y)} predictions identiques")

        if match < len(check_y) * 0.95:
            print("  ATTENTION : divergence importante entre sklearn et ONNX !")
    else:
        print("  (embeddings absents — verification sautee)")

    # ── Informations pour l'integration Android ───────────────────────────────
    print(f"""
  --- Integration Android ---
  Le modele ONNX a ete copie dans :
    {ONNX_PATH}

  Dans HassanWakeWordService.kt, verifier :
    private const val WAKE_WORD_MODEL = "ok_hasan.onnx"

  IMPORTANT : ce modele a une interface differente du modele PyTorch precedent.
  L'entree est float32[1, 1536] (features flatten)
  La sortie est variable selon skl2onnx — voir validate_model.py pour le mapping.
""")

    print(f"[5/6] Termine -> ok_hasan.onnx  ({size_kb} KB)")
    return True


if __name__ == "__main__":
    main()
