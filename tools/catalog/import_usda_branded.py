"""Build the optional USDA branded expansion from the full October 2026 archive."""

import argparse
from collections import Counter, defaultdict, deque
from dataclasses import dataclass, field
from datetime import date
import hashlib
import heapq
import json
import math
from pathlib import Path
import re
import zipfile

import import_usda as usda


PACK_ID = "usda-branded-expansion"
PACK_NAME = "USDA branded expansion"
PACK_VERSION = "1.0.0"
SOURCE = "USDA FDC Branded 2026-10-29"
SOURCE_URL = "https://fdc.nal.usda.gov/download-datasets/"
SOURCE_SHA = "149e0bce3477e90953d90b65b1af69b16407bfadc86926a9f4e16ba7dc227f4e"
LICENSE = "CC0 1.0 / public domain"
CITATION = "U.S. Department of Agriculture, Agricultural Research Service. FoodData Central."
HERE = Path(__file__).resolve().parent
CORE_PACK = HERE.parent.parent / "app/src/main/assets/nutrition/usda-core.forgefoods"
CORE_BRANDS = HERE / "source-samples/branded-200-with-units.json"
MARKETS = {"United States": "US", "US": "US", "New Zealand": "NZ"}
MARKET_LIMITS = {"US": 12_000, "NZ": 1_500}
US_PER_CATEGORY = 120
US_PER_BRAND_PER_CATEGORY = 12
US_POOL_PER_CATEGORY = 360
NZ_POOL_PER_CATEGORY = 1_500
CORE_UNITS = {"203": "g", "204": "g", "205": "g", "208": "kcal"}
LABEL_FIELDS = {"203": "protein", "204": "fat", "205": "carbohydrates", "208": "calories"}
SOURCE_UNITS = {"g", "mg", "ug", "μg", "mcg", "iu", "mg_ate", "kcal", "kj"}
MASS_UNITS = {"g", "grm", "grams"}
VOLUME_UNITS = {"ml", "mlt"}
INDIA_RELEVANT = re.compile(
    r"\b(?:paneer|dosa|idli|idly|dahi|ghee|biryani|samosa|chapati|chappatti|roti|paratha|"
    r"poha|chana|rajma|masala|bhujia|dal|daal|rasam|sambar|sambhar|makhana|pappad|papad|"
    r"pani puri|basmati|tandoori|tikka|namkeen|lassi)\b", re.I,
)
INDIA_RELEVANT_BRANDS = {
    "amul", "ashoka", "bikaji", "bikanervala", "gits", "haldiram", "haldiram's",
    "mtr", "mtr foods", "parle", "patak's", "priya", "sujata", "taj mahal", "tata sampann",
}


class Rejected(ValueError):
    """The archive is readable, but this record does not qualify for the pack."""


def plain(value):
    return value.strip() if isinstance(value, str) else ""


def normalized(value):
    return " ".join(plain(value).casefold().split())


def gtin(value):
    value = plain(value)
    if len(value) not in (8, 12, 13, 14) or not re.fullmatch(r"[0-9]+", value) or not int(value):
        return None
    checksum = sum(int(digit) * (3 if index % 2 == 0 else 1)
                   for index, digit in enumerate(reversed(value[:-1])))
    return value.zfill(14) if (checksum + int(value[-1])) % 10 == 0 else None


def calendar_key(value):
    parts = plain(value).split("/")
    try:
        if len(parts) == 3:
            month, day, year = map(int, parts)
            return date(year, month, day).toordinal()
        return date.fromisoformat(plain(value)).toordinal()
    except ValueError:
        return 0


def source_rows(archive):
    """Read the USDA line-oriented JSON dump without loading its array into memory."""
    with zipfile.ZipFile(archive) as source:
        entries = [entry for entry in source.infolist() if not entry.is_dir()]
        if len(entries) != 1 or not entries[0].filename.endswith(".json"):
            raise ValueError("Expected one USDA Branded Foods JSON member")
        with source.open(entries[0]) as rows:
            if not re.fullmatch(rb'\{\s*"BrandedFoods"\s*:\s*\[\s*', rows.readline()):
                raise ValueError("Expected the USDA BrandedFoods array header")
            closed = False
            for index, raw in enumerate(rows, 2):
                line = raw.strip()
                if not line:
                    continue
                if closed:
                    raise ValueError(f"Unexpected data after the BrandedFoods array at line {index}")
                if line == b"]}":
                    closed = True
                    continue
                try:
                    row = json.loads(line.removesuffix(b","))
                except json.JSONDecodeError:
                    # The pinned USDA dump appends the array and wrapper closers to its last record.
                    if not line.endswith(b"}]}"):
                        raise
                    row = json.loads(line[:-2])
                    closed = True
                if not isinstance(row, dict):
                    raise ValueError(f"Expected a branded food object at line {index}")
                yield row
            if not closed:
                raise ValueError("Incomplete BrandedFoods array")


def revision(row):
    fdc_id = row.get("fdcId")
    if type(fdc_id) is not int or fdc_id <= 0:
        return None
    barcode = gtin(row.get("gtinUpc"))
    identity = f"usda:branded:gtin:{barcode}" if barcode else f"usda:fdc:{fdc_id}"
    return identity, (calendar_key(row.get("publicationDate")), calendar_key(row.get("modifiedDate")), fdc_id)


def load_core(core_pack, core_brands):
    with zipfile.ZipFile(core_pack) as archive, archive.open("foods.jsonl") as records:
        ids = {json.loads(row)["id"] for row in records if row.strip()}
    brands = json.loads(Path(core_brands).read_text(encoding="utf-8-sig"))
    barcodes = {barcode for row in brands if (barcode := gtin(row.get("gtinUpc")))}
    return ids, barcodes


def numeric(value):
    if type(value) not in (int, float) or not math.isfinite(value) or not 0 <= value <= 1_000_000_000:
        return None
    return value


def supported_nutrients(row, audit):
    by_code = {}
    conflicting = set()
    for entry in row.get("foodNutrients", ()):
        spec = entry.get("nutrient", entry)
        code = plain(spec.get("number"))
        unit = plain(spec.get("unitName")).casefold()
        name = plain(spec.get("name"))
        value = numeric(entry.get("amount"))
        valid = bool(re.fullmatch(r"[0-9]{1,10}", code) and name and len(name) <= 200
                     and unit in SOURCE_UNITS and value is not None)
        if code in CORE_UNITS and (not valid or unit != CORE_UNITS[code]):
            raise Rejected("invalid_core_value_or_unit")
        if not valid:
            audit["unusable_optional_nutrient_rows_excluded"] += 1
            continue
        converted = dict(number=code, name=name, unitName=unit, amount=value)
        previous = by_code.get(code)
        if previous is not None and (previous["unitName"], previous["amount"]) != (unit, value):
            if code in CORE_UNITS:
                raise Rejected("conflicting_core_nutrients")
            conflicting.add(code)
        by_code[code] = converted
    for code in conflicting:
        by_code.pop(code)
    audit["conflicting_optional_nutrient_codes_excluded"] += len(conflicting)
    if not CORE_UNITS.keys() <= by_code.keys():
        raise Rejected("missing_core_nutrients")
    return by_code


def convert(row, identity, market, audit):
    if plain(row.get("discontinuedDate")) or any(
        normalized(item.get("name")) == "food discontinued" for item in row.get("foodAttributes", ())
    ):
        raise Rejected("discontinued_latest_records")
    if row.get("foodClass") != "Branded" or row.get("dataType") != "Branded":
        raise Rejected("nonbranded_records")
    name, brand = plain(row.get("description")), plain(row.get("brandName")) or plain(row.get("brandOwner"))
    if not name or not brand or len(name) > 1000 or len(brand) > 250:
        raise Rejected("missing_or_invalid_product_identity")
    unit = plain(row.get("servingSizeUnit")).casefold()
    if unit not in MASS_UNITS | VOLUME_UNITS:
        raise Rejected("unsupported_serving_basis")
    serving = numeric(row.get("servingSize"))
    if serving is None or not 0 < serving <= 10_000:
        raise Rejected("invalid_native_serving_quantity")
    nutrients = supported_nutrients(row, audit)
    energy = nutrients["208"]["amount"]
    macro_total = sum(nutrients[code]["amount"] for code in ("203", "204", "205"))
    # Allow ordinary label rounding. Volume labels have a separate generous ceiling,
    # without inventing a density or converting the source basis to grams.
    if (unit in MASS_UNITS and (energy > 950 or macro_total > 105)) or (
        unit in VOLUME_UNITS and (energy > 1_200 or macro_total > 180)
    ):
        raise Rejected("implausible_per_100_values")
    labels = row.get("labelNutrients") or {}
    for code, field_name in LABEL_FIELDS.items():
        item = labels.get(field_name)
        if item is None:
            continue
        actual = numeric(item.get("value"))
        estimate = nutrients[code]["amount"] * serving / 100
        # USDA rounds the normalized amount, and some label values have one fewer decimal.
        slack = max(2 if code == "208" else 0.25, estimate * 0.03)
        if actual is None or abs(actual - estimate) > slack:
            raise Rejected("conflicting_label_and_reference_values")
    category = plain(row.get("brandedFoodCategory")) or "Uncategorized"
    market_name = "United States" if market == "US" else "New Zealand"
    provenance = f"USDA label market: {market_name}\nUSDA category: {category}"
    state = plain(row.get("preparationStateCode"))
    if state:
        provenance += f"\nSource preparation state: {state}"
    original_note = plain(row.get("footnote"))
    food = usda.full_food({
        **row, "description": name, "brandName": brand,
        "brandOwner": plain(row.get("brandOwner")),
        "footnote": "\n".join(part for part in (provenance, original_note) if part),
        "servingSize": serving, "servingSizeUnit": unit,
        "householdServingFullText": plain(row.get("householdServingFullText")),
        "foodNutrients": list(nutrients.values()),
    }, SOURCE)
    food.update(id=identity, region=market)
    relevant = bool(INDIA_RELEVANT.search(name) or normalized(brand) in INDIA_RELEVANT_BRANDS)
    return food, category, relevant


def product_key(row, food):
    # Size, preparation, ingredients and full serving/nutrient contents distinguish
    # variants whose visible name and brand happen to match.
    fields = [
        normalized(food["name"]), normalized(food["brand"]), food["region"], food["baseUnit"],
        *[normalized(row.get(key)) for key in ("subbrandName", "packageWeight", "ingredients", "preparationStateCode")],
        [(normalized(p["label"]), p["baseQuantity"], p["grams"], p["milliliters"]) for p in food["portions"]],
        [(n["code"], n["unit"], n["value"]) for n in food["nutrients"]],
    ]
    return hashlib.sha256(json.dumps(fields, ensure_ascii=False, separators=(",", ":")).encode("utf-8")).digest()


@dataclass(order=True, slots=True)
class Candidate:
    rank: tuple
    brand: str = field(compare=False)
    duplicate_key: bytes = field(compare=False)
    food_json: str = field(compare=False)


def select_foods(archive, core_ids, core_barcodes):
    audit = Counter()
    source_markets = Counter()
    latest = {}
    for row in source_rows(archive):
        market_name = plain(row.get("marketCountry"))
        source_markets[market_name or "Unknown"] += 1
        market = MARKETS.get(market_name)
        if market is None:
            audit["unsupported_source_market_rows"] += 1
            continue
        entry = revision(row)
        if entry is None:
            audit["missing_fdc_identity_rows"] += 1
            continue
        identity, rank = entry
        previous = latest.get(identity)
        if previous is not None:
            audit["older_gtin_or_fdc_revisions_excluded"] += 1
        if previous is None or rank > previous:
            latest[identity] = rank

    pools = defaultdict(list)
    for row in source_rows(archive):
        market = MARKETS.get(plain(row.get("marketCountry")))
        if market is None:
            continue
        entry = revision(row)
        if entry is None:
            continue
        identity, rank = entry
        if latest.get(identity) != rank:
            continue
        del latest[identity]  # identical repeated source rows must only be considered once
        fdc_id = row["fdcId"]
        barcode = gtin(row.get("gtinUpc"))
        if f"usda:fdc:{fdc_id}" in core_ids or (barcode and barcode in core_barcodes):
            audit["bundled_fdc_or_gtin_duplicates_excluded"] += 1
            continue
        try:
            food, category, relevant = convert(row, identity, market, audit)
        except Rejected as failure:
            audit[str(failure)] += 1
            continue
        audit["eligible_latest_products"] += 1
        pool = pools[market, category]
        pool_limit = US_POOL_PER_CATEGORY if market == "US" else NZ_POOL_PER_CATEGORY
        # Bucket source age by publication year so a single publisher's daily batch
        # does not monopolize the candidates. The hash tie-break is stable across input order.
        year = date.fromordinal(rank[0]).year if rank[0] else 0
        score = (relevant, year, hashlib.sha256(identity.encode()).digest(), identity)
        if len(pool) >= pool_limit and score <= pool[0].rank:
            audit["outside_category_candidate_pool"] += 1
            continue
        candidate = Candidate(score, normalized(food["brand"]), product_key(row, food),
                              json.dumps(food, ensure_ascii=False, separators=(",", ":"), allow_nan=False))
        if len(pool) >= pool_limit:
            heapq.heapreplace(pool, candidate)
            audit["outside_category_candidate_pool"] += 1
        else:
            heapq.heappush(pool, candidate)

    foods = []
    coverage = {
        "sourceMarketRows": dict(sorted(source_markets.items())),
        "selectedMarketFoods": {},
        "selectedCategoryFoods": {},
        "indiaRelevantNameOrBrandFoods": 0,
        "nativeMilliliterFoods": 0,
        "foodsWithAdditionalNutrients": 0,
        "gtinIdentityFoods": 0,
    }
    for market, max_foods in MARKET_LIMITS.items():
        categories = {key: deque(sorted(pool, reverse=True)) for (area, key), pool in sorted(pools.items())
                      if area == market}
        category_counts, brand_counts = Counter(), Counter()
        seen = set()
        selected = 0
        # Put India-relevant product names or brands first, then round-robin categories.
        for prioritize in (True, False):
            while selected < max_foods:
                added = False
                for category, pool in categories.items():
                    if market == "US" and category_counts[category] >= US_PER_CATEGORY:
                        continue
                    while pool and pool[0].rank[0] == prioritize:
                        candidate = pool.popleft()
                        if candidate.duplicate_key in seen:
                            audit["exact_product_duplicates_excluded"] += 1
                            continue
                        brand_key = (category, candidate.brand)
                        if market == "US" and brand_counts[brand_key] >= US_PER_BRAND_PER_CATEGORY:
                            continue
                        food = json.loads(candidate.food_json)
                        foods.append(food)
                        seen.add(candidate.duplicate_key)
                        brand_counts[brand_key] += 1
                        category_counts[category] += 1
                        selected += 1
                        coverage["indiaRelevantNameOrBrandFoods"] += prioritize
                        coverage["nativeMilliliterFoods"] += food["baseUnit"] == "MILLILITER"
                        coverage["foodsWithAdditionalNutrients"] += len(food["nutrients"]) > 4
                        coverage["gtinIdentityFoods"] += food["id"].startswith("usda:branded:gtin:")
                        added = True
                        break
                    if selected >= max_foods:
                        break
                if not added:
                    break
        coverage["selectedMarketFoods"][market] = selected
        coverage["selectedCategoryFoods"][market] = dict(sorted(category_counts.items()))
    audit["eligible_products_not_selected"] = audit["eligible_latest_products"] - len(foods)
    return foods, coverage, audit


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--archive", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--core-pack", default=CORE_PACK, type=Path)
    parser.add_argument("--core-brands", default=CORE_BRANDS, type=Path)
    args = parser.parse_args()
    with args.archive.open("rb") as source:
        if hashlib.file_digest(source, "sha256").hexdigest() != SOURCE_SHA:
            parser.error("The source ZIP does not match the official October 2026 archive checksum")
    core_ids, core_barcodes = load_core(args.core_pack, args.core_brands)
    foods, coverage, audit = select_foods(args.archive, core_ids, core_barcodes)
    if len(foods) < 8_000 or coverage["selectedMarketFoods"]["NZ"] < 100:
        parser.error("Source selection did not meet the expansion's verified coverage floor")
    usda.write_pack(
        foods, output=args.output, pack_id=PACK_ID, name=PACK_NAME, version=PACK_VERSION,
        source_url=SOURCE_URL, license_text=LICENSE, citation=CITATION, audit=audit,
        paths=[args.archive, args.core_pack, args.core_brands], coverage=coverage,
    )


if __name__ == "__main__":
    main()
