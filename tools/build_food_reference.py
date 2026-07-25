#!/usr/bin/env python3
"""
Build the bundled food-reference table from USDA FoodData Central (SR Legacy).

Output: src/main/resources/data/food-reference-usda.json

USDA FDC is a US Government work and is in the **public domain** — it can be bundled
and redistributed without restriction. This is deliberately NOT the IFCT 2017 dataset:
the widely-mirrored `ifct2017` package is AGPL-3.0, whose network-use clause would reach
a served application. Indian composite dishes are covered instead by the hand-authored
food-reference-indian.json, which carries no third-party licence.

Usage:
    python3 tools/build_food_reference.py            # downloads + builds
    python3 tools/build_food_reference.py --keep     # keep the download for inspection
"""

import argparse
import csv
import io
import json
import re
import sys
import urllib.request
import zipfile
from pathlib import Path

SR_LEGACY_URL = (
    "https://fdc.nal.usda.gov/fdc-datasets/FoodData_Central_sr_legacy_food_csv_2018-04.zip"
)
INNER = "FoodData_Central_sr_legacy_food_csv_2018-04"
OUT = Path(__file__).resolve().parents[1] / "src/main/resources/data/food-reference-usda.json"

# USDA nutrient id -> our field name. Everything is per 100 g of edible portion.
NUTRIENTS = {
    "1008": "kcal",
    "1003": "protein",
    "1005": "carbs",
    "1004": "fat",
    "1079": "fiber",
    "2000": "sugar",
    "1093": "sodium",       # mg
    "1258": "satFat",
    "1092": "potassium",    # mg
    "1253": "cholesterol",  # mg
}

# Portion rows worth keeping: real household measures, not "1 oz" noise.
USEFUL_PORTION = re.compile(
    r"\b(cup|tbsp|tablespoon|tsp|teaspoon|slice|piece|medium|large|small|"
    r"whole|fillet|breast|thigh|link|patty|bowl|package|container)\b",
    re.I,
)

# SR Legacy carries many hyper-specific industrial entries that only add matching noise
# for a consumer meal logger (baby formula, restaurant-brand SKUs, raw commodity cuts).
NOISE = re.compile(
    r"\b(infant formula|babyfood|baby food|formula,|USDA Commodity|school lunch|"
    r"leavening agents|gelatin desserts?|alcoholic beverage)\b",
    re.I,
)


def fetch_zip(keep: bool) -> zipfile.ZipFile:
    cache = Path("/tmp/fdc_sr_legacy.zip")
    if cache.exists():
        print(f"using cached {cache}")
        return zipfile.ZipFile(cache)
    print(f"downloading {SR_LEGACY_URL} (~6 MB)...")
    data = urllib.request.urlopen(SR_LEGACY_URL, timeout=300).read()
    if keep:
        cache.write_bytes(data)
    return zipfile.ZipFile(io.BytesIO(data))


def read_csv(z: zipfile.ZipFile, name: str):
    with z.open(f"{INNER}/{name}") as fh:
        yield from csv.DictReader(io.TextIOWrapper(fh, encoding="utf-8-sig"))


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--keep", action="store_true", help="cache the download in /tmp")
    args = ap.parse_args()

    z = fetch_zip(args.keep)

    print("reading foods...")
    foods = {}
    for r in read_csv(z, "food.csv"):
        desc = r["description"].strip()
        if not desc or NOISE.search(desc):
            continue
        foods[r["fdc_id"]] = {"id": "usda-" + r["fdc_id"], "name": desc, "per100g": {}}
    print(f"  {len(foods)} foods after noise filter")

    print("reading nutrients (this is the big one)...")
    for r in read_csv(z, "food_nutrient.csv"):
        food = foods.get(r["fdc_id"])
        if food is None:
            continue
        field = NUTRIENTS.get(r["nutrient_id"])
        if field is None:
            continue
        try:
            food["per100g"][field] = round(float(r["amount"]), 2)
        except (ValueError, TypeError):
            pass

    print("reading household portions...")
    for r in read_csv(z, "food_portion.csv"):
        food = foods.get(r["fdc_id"])
        if food is None:
            continue
        label = " ".join(x for x in (r.get("portion_description", ""),
                                     r.get("modifier", "")) if x).strip()
        if not label or not USEFUL_PORTION.search(label):
            continue
        try:
            grams = round(float(r["gram_weight"]), 1)
        except (ValueError, TypeError):
            continue
        if not 1 <= grams <= 1500:
            continue
        food.setdefault("portions", [])
        if len(food["portions"]) < 4:
            food["portions"].append({"label": label[:48], "grams": grams})

    # A food without an energy value cannot contribute to a macro total, so it is
    # dead weight in both the matcher and the bundle.
    kept = [f for f in foods.values() if "kcal" in f["per100g"]]
    kept.sort(key=lambda f: f["name"])
    print(f"  {len(kept)} foods with energy data")

    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(
        json.dumps(
            {
                "source": "USDA FoodData Central — SR Legacy (2018-04)",
                "url": "https://fdc.nal.usda.gov/download-datasets.html",
                "license": "Public domain (US Government work)",
                "units": "per 100 g edible portion; sodium/potassium/cholesterol in mg, rest in g",
                "foods": kept,
            },
            separators=(",", ":"),
        )
        + "\n"
    )
    print(f"\nwrote {OUT.relative_to(OUT.parents[4])}  ({OUT.stat().st_size / 1e6:.1f} MB)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
