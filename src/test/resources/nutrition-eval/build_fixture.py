#!/usr/bin/env python3
"""
Build the Nutrition5k evaluation fixture for the meal-analysis pipeline.

Downloads dish metadata from the public Nutrition5k GCS bucket, keeps only dishes
that have an overhead RGB image, samples them stratified by total mass, and writes:

  manifest.json   ground truth for each sampled dish  (COMMITTED — small)
  images/         overhead rgb.png per dish           (GITIGNORED — ~20 MB)

Stratifying by mass is deliberate: published evals find vision models systematically
UNDERestimate portions with the bias growing as portions get larger (slope -0.23 to
-0.50). A sample spread evenly across mass bands lets the harness fit that slope
instead of only reporting a single blended MAPE.

Dataset: Nutrition5k (Thames et al., CVPR 2021), Google Research. CC BY 4.0.
https://github.com/google-research-datasets/Nutrition5k

Usage:
    python3 build_fixture.py                # default: 60 dishes
    python3 build_fixture.py --n 120        # bigger sample
    python3 build_fixture.py --skip-images  # manifest only
"""

import argparse
import csv
import json
import random
import sys
import urllib.error
import urllib.request
from pathlib import Path

BUCKET = "https://storage.googleapis.com/nutrition5k_dataset/nutrition5k_dataset"
HERE = Path(__file__).parent
IMAGES_DIR = HERE / "images"
MANIFEST = HERE / "manifest.json"

# Dishes with an overhead RealSense capture are enumerated by the depth splits.
# The rgb_* splits include side-angle-only dishes, which have no overhead image.
OVERHEAD_SPLITS = ["dish_ids/splits/depth_test_ids.txt", "dish_ids/splits/depth_train_ids.txt"]
DISH_METADATA = ["metadata/dish_metadata_cafe1.csv", "metadata/dish_metadata_cafe2.csv"]

# Nutrition5k ground truth is dish-level totals plus repeating 7-field ingredient groups:
#   dish_id, kcal, mass_g, fat_g, carb_g, protein_g,
#   [ingr_id, name, grams, kcal, fat, carb, protein] * n
INGREDIENT_STRIDE = 7


def fetch(path: str) -> bytes:
    url = f"{BUCKET}/{path}"
    try:
        with urllib.request.urlopen(url, timeout=90) as r:
            return r.read()
    except urllib.error.HTTPError as e:
        raise SystemExit(f"fetch failed {url}: HTTP {e.code}") from e


def parse_dish_metadata(raw: bytes) -> dict:
    """Parse a dish_metadata CSV into {dish_id: ground_truth}."""
    dishes = {}
    for row in csv.reader(raw.decode("utf-8", errors="replace").splitlines()):
        if len(row) < 6 or not row[0].startswith("dish_"):
            continue
        try:
            dish = {
                "dish_id": row[0],
                "kcal": round(float(row[1]), 1),
                "mass_g": round(float(row[2]), 1),
                "fat_g": round(float(row[3]), 1),
                "carb_g": round(float(row[4]), 1),
                "protein_g": round(float(row[5]), 1),
            }
        except ValueError:
            continue

        ingredients = []
        # Ingredient groups start at column 6 and repeat every 7 fields.
        for i in range(6, len(row) - INGREDIENT_STRIDE + 1, INGREDIENT_STRIDE):
            name = row[i + 1].strip()
            try:
                grams = round(float(row[i + 2]), 1)
            except ValueError:
                continue
            if name:
                ingredients.append({"name": name, "grams": grams})

        # A dish with no parsed ingredients or zero mass carries no usable signal.
        if not ingredients or dish["mass_g"] <= 0:
            continue
        dish["ingredients"] = ingredients
        dishes[dish["dish_id"]] = dish
    return dishes


def stratified_sample(dishes: list, n: int, bands: int, seed: int) -> list:
    """Sample n dishes spread evenly across `bands` equal-count mass quantiles."""
    rng = random.Random(seed)
    by_mass = sorted(dishes, key=lambda d: d["mass_g"])
    if len(by_mass) <= n:
        return by_mass

    band_size = len(by_mass) // bands
    per_band = max(1, n // bands)
    picked = []
    for b in range(bands):
        lo = b * band_size
        hi = len(by_mass) if b == bands - 1 else (b + 1) * band_size
        bucket = by_mass[lo:hi]
        picked.extend(rng.sample(bucket, min(per_band, len(bucket))))

    # Top up from whatever is left if integer division left us short.
    if len(picked) < n:
        remaining = [d for d in by_mass if d not in picked]
        picked.extend(rng.sample(remaining, min(n - len(picked), len(remaining))))
    return sorted(picked[:n], key=lambda d: d["mass_g"])


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--n", type=int, default=60, help="dishes to sample (default 60)")
    ap.add_argument("--bands", type=int, default=6, help="mass strata (default 6)")
    ap.add_argument("--seed", type=int, default=20260724, help="sampling seed")
    ap.add_argument("--skip-images", action="store_true", help="manifest only")
    args = ap.parse_args()

    print("fetching dish metadata...")
    dishes = {}
    for path in DISH_METADATA:
        dishes.update(parse_dish_metadata(fetch(path)))
    print(f"  parsed {len(dishes)} dishes with ingredient breakdowns")

    print("fetching overhead-capture dish ids...")
    overhead = set()
    for path in OVERHEAD_SPLITS:
        overhead.update(
            line.strip() for line in fetch(path).decode().splitlines() if line.strip()
        )
    print(f"  {len(overhead)} dishes have an overhead capture")

    eligible = [d for did, d in dishes.items() if did in overhead]
    print(f"  {len(eligible)} eligible (metadata AND overhead image)")
    if not eligible:
        raise SystemExit("no eligible dishes — dataset layout may have changed")

    sample = stratified_sample(eligible, args.n, args.bands, args.seed)
    masses = [d["mass_g"] for d in sample]
    print(f"\nsampled {len(sample)} dishes, mass {min(masses):.0f}–{max(masses):.0f} g")

    if not args.skip_images:
        IMAGES_DIR.mkdir(parents=True, exist_ok=True)
        print("downloading overhead images...")
        kept = []
        for i, d in enumerate(sample, 1):
            dest = IMAGES_DIR / f"{d['dish_id']}.png"
            if not dest.exists():
                try:
                    dest.write_bytes(fetch(f"imagery/realsense_overhead/{d['dish_id']}/rgb.png"))
                except SystemExit:
                    print(f"  [{i}/{len(sample)}] {d['dish_id']} MISSING — dropped")
                    continue
            kept.append(d)
            if i % 10 == 0 or i == len(sample):
                print(f"  [{i}/{len(sample)}]")
        sample = kept
        total_mb = sum(f.stat().st_size for f in IMAGES_DIR.glob("*.png")) / 1e6
        print(f"  images/: {len(list(IMAGES_DIR.glob('*.png')))} files, {total_mb:.1f} MB")

    MANIFEST.write_text(
        json.dumps(
            {
                "dataset": "Nutrition5k",
                "source": "https://github.com/google-research-datasets/Nutrition5k",
                "citation": "Thames et al., Nutrition5k: Towards Automatic Nutritional "
                            "Understanding of Generic Food. CVPR 2021.",
                "license": "CC BY 4.0",
                "sampling": {
                    "n": len(sample),
                    "bands": args.bands,
                    "seed": args.seed,
                    "strategy": "stratified by total mass to expose portion-size bias slope",
                },
                "caveat": "Google cafeteria food (Western, largely non-vegetarian). Validates "
                          "pipeline machinery and portion estimation. Does NOT cover South "
                          "Indian dishes or the IFCT portion ladder — a personal eval set is "
                          "still required for those. See design/NUTRITION_ACCURACY_PLAN.md.",
                "dishes": sample,
            },
            indent=2,
        )
        + "\n"
    )
    print(f"\nwrote {MANIFEST.relative_to(HERE.parent.parent.parent.parent)}  ({len(sample)} dishes)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
