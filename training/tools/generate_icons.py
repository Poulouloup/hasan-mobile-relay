"""
Génère toutes les icônes Android à partir de hasan_logo.jpeg.
Exécuter depuis la racine du projet :
    python tools/generate_icons.py
"""

import os
from pathlib import Path
from PIL import Image

# Chemin du logo source (à la racine du projet)
PROJECT_ROOT = Path(__file__).parent.parent.parent
SOURCE_IMAGE = PROJECT_ROOT / "app" / "src" / "main" / "assets" / "hasan_logo.jpeg"
RES_DIR = PROJECT_ROOT / "app" / "src" / "main" / "res"

# ---- Tailles icônes legacy (ic_launcher.png / ic_launcher_round.png) ----
LEGACY_SIZES = {
    "mipmap-mdpi":    48,
    "mipmap-hdpi":    72,
    "mipmap-xhdpi":   96,
    "mipmap-xxhdpi":  144,
    "mipmap-xxxhdpi": 192,
}

# ---- Tailles foreground adaptive (108dp équivalent par densité) ----
ADAPTIVE_SIZES = {
    "mipmap-mdpi":    108,
    "mipmap-hdpi":    162,
    "mipmap-xhdpi":   216,
    "mipmap-xxhdpi":  324,
    "mipmap-xxxhdpi": 432,
}

WHITE = (255, 255, 255, 255)


def make_square_on_white(img: Image.Image, size: int) -> Image.Image:
    """Centre le logo sur fond blanc carré, conserve le ratio, ajoute un padding de 10%."""
    img = img.convert("RGBA")
    padding = int(size * 0.10)
    inner = size - 2 * padding

    # Redimensionne en conservant le ratio
    img.thumbnail((inner, inner), Image.LANCZOS)

    canvas = Image.new("RGBA", (size, size), WHITE)
    x = (size - img.width) // 2
    y = (size - img.height) // 2
    canvas.paste(img, (x, y), img)
    return canvas.convert("RGB")


def make_foreground(img: Image.Image, size: int) -> Image.Image:
    """
    Foreground adaptive : logo centré sur fond transparent,
    dans une zone de 66% du canvas (safe zone Android).
    """
    img = img.convert("RGBA")
    safe = int(size * 0.66)
    img.thumbnail((safe, safe), Image.LANCZOS)

    canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    x = (size - img.width) // 2
    y = (size - img.height) // 2
    canvas.paste(img, (x, y), img)
    return canvas


def main():
    if not SOURCE_IMAGE.exists():
        print(f"[Erreur] Image source introuvable : {SOURCE_IMAGE}")
        print("Dépose hasan_logo.jpeg dans app/src/main/assets/ puis relance ce script.")
        return

    src = Image.open(SOURCE_IMAGE)
    print(f"[OK] Image source : {src.size} {src.mode}")

    # ---- Icônes legacy ----
    for folder, size in LEGACY_SIZES.items():
        out_dir = RES_DIR / folder
        out_dir.mkdir(parents=True, exist_ok=True)

        icon = make_square_on_white(src, size)
        for name in ["ic_launcher.png", "ic_launcher_round.png"]:
            out_path = out_dir / name
            icon.save(out_path, "PNG", optimize=True)
            print(f"  {out_path.relative_to(PROJECT_ROOT)}  ({size}x{size})")

    # ---- Foreground adaptive ----
    for folder, size in ADAPTIVE_SIZES.items():
        out_dir = RES_DIR / folder
        out_dir.mkdir(parents=True, exist_ok=True)

        fg = make_foreground(src, size)
        out_path = out_dir / "ic_launcher_foreground.png"
        fg.save(out_path, "PNG", optimize=True)
        print(f"  {out_path.relative_to(PROJECT_ROOT)}  ({size}x{size})")

    print("\n[Terminé] Toutes les icônes ont été générées.")


if __name__ == "__main__":
    main()
