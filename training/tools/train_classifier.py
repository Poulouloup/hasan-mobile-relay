"""
Etape 4 — Entrainement du classificateur wake word.

Charge les embeddings calcules par compute_embeddings.py et entraine
un MLP sklearn a 2 couches cachees. Le MLP est choisi car il
s'exporte facilement en ONNX via skl2onnx (contrairement a un SVC).

Entree :
  embeddings/positive_embeddings.npy  shape (N_pos, 16, 96)
  embeddings/negative_embeddings.npy  shape (N_neg, 16, 96)

Sortie :
  models/classifier.pkl     le modele entraine
  models/scaler.pkl         le StandardScaler (normalisation des features)

Metriques affichees : precision, rappel, F1, matrice de confusion
Objectif minimum   : F1 >= 0.85

Usage :
  pip install scikit-learn
  python tools/train_classifier.py

Duree estimee : ~1 min sur CPU Windows
"""

import pickle
import numpy as np
from pathlib import Path
from sklearn.neural_network import MLPClassifier
from sklearn.preprocessing import StandardScaler
from sklearn.model_selection import train_test_split
from sklearn.metrics import precision_score, recall_score, f1_score, confusion_matrix

# ─────────────────────────── CHEMINS ──────────────────────────────────────────

ROOT       = Path(__file__).parent.parent.parent  # racine projet
TRAINING_ROOT = Path(__file__).parent.parent  # training/
EMB_DIR    = TRAINING_ROOT / "embeddings"
MODELS_DIR = TRAINING_ROOT / "models"
MODELS_DIR.mkdir(exist_ok=True)

# ─────────────────────────── PARAMS ───────────────────────────────────────────

HISTORY_LEN   = 16
EMBEDDING_DIM = 96
FEATURE_DIM   = HISTORY_LEN * EMBEDDING_DIM   # 1536

TEST_SIZE     = 0.20   # 80% train / 20% test
RANDOM_STATE  = 42

# ─────────────────────────── MAIN ─────────────────────────────────────────────

def main():
    print("=== [Etape 4] Entrainement du classificateur ===")
    print(f"Duree estimee : ~1 min\n")

    # ── Chargement des embeddings ──────────────────────────────────────────────
    pos_path = EMB_DIR / "positive_embeddings.npy"
    neg_path = EMB_DIR / "negative_embeddings.npy"

    if not pos_path.exists() or not neg_path.exists():
        raise FileNotFoundError(
            "Embeddings manquants — executer d'abord compute_embeddings.py"
        )

    pos_emb = np.load(str(pos_path))   # (N_pos, 16, 96)
    neg_emb = np.load(str(neg_path))   # (N_neg, 16, 96)

    print(f"  Positifs  : {pos_emb.shape[0]} exemples")
    print(f"  Negatifs  : {neg_emb.shape[0]} exemples")

    if pos_emb.shape[0] == 0:
        raise ValueError("Aucun positif — verifier generate_synthetic.py et compute_embeddings.py")
    if neg_emb.shape[0] == 0:
        raise ValueError("Aucun negatif — verifier generate_negatives.py et compute_embeddings.py")

    # ── Preparation X, y ──────────────────────────────────────────────────────
    # Flatten [N, 16, 96] -> [N, 1536]
    X_pos = pos_emb.reshape(pos_emb.shape[0], -1)
    X_neg = neg_emb.reshape(neg_emb.shape[0], -1)

    X = np.concatenate([X_pos, X_neg], axis=0)
    y = np.concatenate([
        np.ones(X_pos.shape[0],  dtype=np.int32),
        np.zeros(X_neg.shape[0], dtype=np.int32),
    ])

    print(f"\n  Total exemples : {len(X)}")
    print(f"  Ratio positifs : {y.mean():.2%}")

    # ── Split train / test ─────────────────────────────────────────────────────
    X_train, X_test, y_train, y_test = train_test_split(
        X, y,
        test_size    = TEST_SIZE,
        random_state = RANDOM_STATE,
        stratify     = y,
    )
    print(f"\n  Train : {len(X_train)}  |  Test : {len(X_test)}")

    # ── Normalisation ──────────────────────────────────────────────────────────
    scaler  = StandardScaler()
    X_train = scaler.fit_transform(X_train)
    X_test  = scaler.transform(X_test)

    # ── Entrainement MLP ───────────────────────────────────────────────────────
    print("\n  Entrainement MLP (hidden_layer_sizes=(256, 128, 64))...")
    clf = MLPClassifier(
        hidden_layer_sizes = (256, 128, 64),
        activation         = "relu",
        solver             = "adam",
        alpha              = 1e-4,         # regularisation L2
        batch_size         = 64,
        learning_rate_init = 3e-4,
        max_iter           = 300,
        early_stopping     = True,
        validation_fraction= 0.10,
        n_iter_no_change   = 15,
        random_state       = RANDOM_STATE,
        verbose            = False,
    )
    clf.fit(X_train, y_train)
    print(f"  Iterations effectuees : {clf.n_iter_}")

    # ── Evaluation ────────────────────────────────────────────────────────────
    y_pred = clf.predict(X_test)
    prec   = precision_score(y_test, y_pred, zero_division=0)
    rec    = recall_score(y_test, y_pred, zero_division=0)
    f1     = f1_score(y_test, y_pred, zero_division=0)
    cm     = confusion_matrix(y_test, y_pred)

    print(f"\n  --- Metriques sur le test set ---")
    print(f"  Precision : {prec:.3f}")
    print(f"  Rappel    : {rec:.3f}")
    print(f"  F1        : {f1:.3f}")
    print(f"\n  Matrice de confusion :")
    print(f"              Predit NEG  Predit POS")
    print(f"  Reel NEG :  {cm[0][0]:10d}  {cm[0][1]:10d}")
    print(f"  Reel POS :  {cm[1][0]:10d}  {cm[1][1]:10d}")

    # Seuil de qualite
    if f1 >= 0.85:
        print(f"\n  OK — F1={f1:.3f} >= 0.85  (objectif atteint)")
    else:
        print(f"\n  ATTENTION — F1={f1:.3f} < 0.85")
        print("  Conseils : augmenter N_SAMPLES dans generate_synthetic.py")
        print("             ou augmenter N_SPEECH dans generate_negatives.py")

    # ── Sauvegarde ─────────────────────────────────────────────────────────────
    clf_path    = MODELS_DIR / "classifier.pkl"
    scaler_path = MODELS_DIR / "scaler.pkl"
    with open(clf_path,    "wb") as f: pickle.dump(clf,    f)
    with open(scaler_path, "wb") as f: pickle.dump(scaler, f)
    print(f"\n  Sauvegarde : {clf_path}")
    print(f"  Sauvegarde : {scaler_path}")

    print(f"\n[4/6] Termine -> classifier.pkl  F1={f1:.3f}")
    return f1


if __name__ == "__main__":
    main()
