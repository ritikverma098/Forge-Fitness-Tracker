"""Build the India packaged foods pack from the pinned Mendeley Data v1 CSV."""
import argparse
import csv
import math
import pathlib
import re
import unicodedata
from collections import Counter

from import_usda import file_sha256, nutrient, write_pack


PACK_ID = 'india-packaged'
NAME = 'India packaged foods'
VERSION = '1.0.0'
SOURCE = 'Indian Packaged Foods Nutritional Composition Dataset (2026), v1'
SOURCE_URL = 'https://data.mendeley.com/datasets/rhswn9z9xr/1'
SOURCE_CSV_URL = ('https://data.mendeley.com/public-files/datasets/rhswn9z9xr/files/'
                  'e0b16335-a9ac-4fee-8b68-70fe7b421952/file_downloaded?version=1')
SOURCE_SHA256 = 'f64832743d4918c428aab31fb8a3681cd2dee89f3c5f305185ec0edfabe8f303'
LICENSE = 'CC BY 4.0 (https://creativecommons.org/licenses/by/4.0/)'
CITATION = ('Duddela Sai Prashanth, Ananya Devadiga, Saathwik Saathwik, Priya R Kamath and Rakshith Bhandary '
            '(2026). Indian Packaged Foods Nutritional Composition Dataset (2026), v1. Mendeley Data. '
            'https://doi.org/10.17632/rhswn9z9xr.1. Filtered and converted for Forge.')
NOTES = ('Nutrition uses the source serving in grams. Compare with your package and match the preparation '
         'state. Unreported or ambiguous optional values are omitted.')

CORE = (
    ('Calories_kcal', '208', 'Energy', 'kcal'),
    ('Carbohydrates_g', '205', 'Carbohydrate', 'g'),
    ('Proteins_g', '203', 'Protein', 'g'),
    ('Total_Fat_g', '204', 'Total fat', 'g'),
)
OPTIONAL = (
    ('Saturated_Fat_g', '606', 'Fatty acids, total saturated', 'g', 'fat'),
    ('Trans_Fat_g', '605', 'Fatty acids, total trans', 'g', 'fat'),
    ('Sugar_g', '269', 'Sugars, total', 'g', 'carbs'),
    ('Sodium_mg', '307', 'Sodium, Na', 'mg', 'milligrams'),
    ('Dietary_Fiber_g', '291', 'Fiber, total dietary', 'g', 'grams'),
    ('Cholesterol_mg', '601', 'Cholesterol', 'mg', 'milligrams'),
    ('Calcium_mg', '301', 'Calcium, Ca', 'mg', 'milligrams'),
    ('Iron_mg', '303', 'Iron, Fe', 'mg', 'milligrams'),
)
VITAMINS = {
    'b1': ('404', 'Thiamin', 'mg'),
    'b2': ('405', 'Riboflavin', 'mg'),
    'b3': ('406', 'Niacin', 'mg'),
    'b5': ('410', 'Pantothenic acid', 'mg'),
    'b6': ('415', 'Vitamin B-6', 'mg'),
    'b12': ('418', 'Vitamin B-12', 'µg'),
    'c': ('401', 'Vitamin C, total ascorbic acid', 'mg'),
    'd': ('328', 'Vitamin D (D2 + D3)', 'µg'),
}
# Kissan Mixed Fruit Jam row 761 transcribes biotin as B12. Omit the conflicting value.
# https://www.hul.co.in/files/92ui5egz/production/a7fcadce2a878b34009fa829fa5bb90cb2746c78.pdf
KNOWN_MISLABELLED_VITAMINS = {('761', 'usda:418')}
REQUIRED_COLUMNS = {
    'S.No', 'Item name', 'Brand_Name', 'Category', 'Sub_Category', 'Serving_Size_g', 'Vitamin',
    *(column for column, *_ in CORE), *(column for column, *_ in OPTIONAL),
}
VOLUME_CATEGORIES = {'juice', 'sauce'}
UNCERTAIN_BASIS = re.compile(
    r'\b(?:drinks?|beverage|milk\s?shake|juice|soda|nectar|kombucha|smoothie|syrup|chilli oil|'
    r'pure cream|pouring cream|condensed milk|matcha)\b|\b[0-9]+(?:\.[0-9]+)?\s*(?:ml|liters?|litres?|l)\b',
    re.IGNORECASE,
)
DECIMAL = r'(?:[0-9]+(?:\.[0-9]+)?|\.[0-9]+)'
UNIT = r'mg|mcg|[µμ]g'
VITAMIN = re.compile(
    rf'^@?vitamin\s+(?P<name>[a-z]+[0-9]*)\s*'
    rf'(?:\((?P<leading_unit>{UNIT})\)\s*(?P<following_amount>{DECIMAL})|'
    rf'(?P<leading_amount>{DECIMAL})\s*(?:\((?P<parenthesized_unit>{UNIT})\)|(?P<trailing_unit>{UNIT})))'
    rf'(?:\s*\((?:rda\s+)?{DECIMAL}%\))?\.?$',
    re.IGNORECASE,
)


def clean_label(value):
    return ' '.join(value.split())


def identity(value):
    return clean_label(unicodedata.normalize('NFKC', value.replace('®', '').replace('™', '')).casefold())


def number(value):
    value = value.strip()
    if not re.fullmatch(DECIMAL, value):
        return None
    parsed = float(value)
    return parsed if math.isfinite(parsed) else None


def vitamin_rows(raw, grams):
    # Quantities without an unambiguous nutrient and mass unit stay unknown. Some D and B12
    # entries are marked mg while comparable source labels are in µg, so accept only explicit µg.
    values = {}
    for field in raw.split(','):
        match = VITAMIN.fullmatch(field.strip())
        if match is None:
            continue
        vitamin = match['name'].lower()
        if vitamin not in VITAMINS:
            continue
        code, label, unit = VITAMINS[vitamin]
        source_unit = (match['leading_unit'] or match['parenthesized_unit'] or match['trailing_unit']).lower()
        value = number(match['following_amount'] or match['leading_amount'])
        if not value or (unit == 'µg' and source_unit == 'mg'):
            continue
        if unit == 'mg' and source_unit != 'mg':
            value /= 1000
        if value <= grams * (1_000_000 if unit == 'µg' else 1000):
            values.setdefault(code, []).append(nutrient(code, label, unit, value))
    return [entries[0] for entries in values.values() if len(entries) == 1]


def import_row(row, duplicates, audit):
    name = clean_label(row['Item name'])
    brand = clean_label(row['Brand_Name'])
    category = identity(row['Category'])
    if not name or not brand or len(name) > 1000 or len(brand) > 250 or not category:
        audit['invalid_identity_rows'] += 1
        return None
    if category in VOLUME_CATEGORIES or UNCERTAIN_BASIS.search(name + ' ' + clean_label(row['Sub_Category'])):
        audit['ambiguous_serving_basis_rows'] += 1
        return None
    if duplicates[(identity(name), identity(brand))] > 1:
        audit['duplicate_identity_rows'] += 1
        return None
    grams = number(row['Serving_Size_g'])
    core_values = [number(row[column]) for column, *_ in CORE]
    if grams is None or not 0 < grams <= 1000 or any(value is None for value in core_values):
        audit['invalid_core_or_serving_rows'] += 1
        return None
    kcal, carbs, protein, fat = core_values
    if not 0 < kcal <= 9 * grams + 1:
        audit['impossible_energy_rows'] += 1
        return None
    if carbs + protein + fat > grams * 1.02:
        audit['macro_mass_mismatch_rows'] += 1
        return None
    # Atwater factors serve as a plausibility check only. Keep the published energy value.
    if abs(kcal - (4 * (carbs + protein) + 9 * fat)) > max(5, 0.20 * kcal):
        audit['energy_macro_mismatch_rows'] += 1
        return None

    nutrients = [nutrient(code, label, unit, value)
                 for (_, code, label, unit), value in zip(CORE, core_values)]
    rounding = max(0.25, 0.02 * grams)
    maximums = dict(fat=min(grams, fat + rounding), carbs=min(grams, carbs + rounding),
                    grams=grams, milligrams=grams * 1000)
    for column, code, label, unit, limit in OPTIONAL:
        value = number(row[column])
        if value and value <= maximums[limit]:
            nutrients.append(nutrient(code, label, unit, value))
        elif value:
            audit['optional_out_of_range_values_omitted'] += 1
    source_id = row['S.No'].strip()
    for vitamin in vitamin_rows(row['Vitamin'], grams):
        if (source_id, vitamin['code']) in KNOWN_MISLABELLED_VITAMINS:
            audit['known_mislabelled_vitamins_omitted'] += 1
        else:
            nutrients.append(vitamin)
    portions = []
    if grams != 100:
        portions.append(dict(id='label', label=f'{grams:g} g source serving', baseQuantity=grams,
                             grams=grams, milliliters=None, source=SOURCE))
    return dict(id=f'{PACK_ID}:{source_id}', name=name, brand=brand, aliases='', source=SOURCE,
                sourceId=source_id, sourceUrl=SOURCE_URL, region='IN', tags='branded',
                baseAmount=grams, baseUnit='GRAM', gramsPerBase=grams, millilitersPerBase=None,
                notes=NOTES, nutrients=sorted(nutrients, key=lambda item: item['code']), portions=portions)


def load_foods(path):
    with path.open(encoding='utf-8-sig', newline='') as file:
        reader = csv.DictReader(file, strict=True)
        columns = reader.fieldnames or []
        if not REQUIRED_COLUMNS.issubset(columns) or len(columns) != len(set(columns)):
            raise ValueError('Unexpected India packaged foods CSV columns')
        rows = list(reader)
    if any(None in row or None in row.values() for row in rows):
        raise ValueError('Malformed India packaged foods CSV row')
    ids = [row['S.No'].strip() for row in rows]
    if any(not re.fullmatch(r'[1-9][0-9]{0,8}', source_id) for source_id in ids) or len(set(ids)) != len(ids):
        raise ValueError('India packaged foods CSV requires unique positive source row IDs')

    identities = Counter((identity(row['Item name']), identity(row['Brand_Name'])) for row in rows)
    categories = Counter()
    audit = Counter()
    foods = []
    for row in rows:
        food = import_row(row, identities, audit)
        if food is not None:
            foods.append(food)
            categories[identity(row['Category'])] += 1

    core_codes = {'energy', 'carbs', 'protein', 'fat'}
    micronutrient_codes = {f'usda:{code}' for code, _, _ in VITAMINS.values()}
    micronutrient_codes.update(('usda:307', 'usda:301', 'usda:303'))
    optional_counts = Counter(n['code'] for food in foods for n in food['nutrients'] if n['code'] not in core_codes)
    coverage = dict(sourceRows=len(rows), retainedFoods=len(foods), excludedRows=len(rows) - len(foods),
                    uniqueNormalizedBrands=len({identity(food['brand']) for food in foods}),
                    categories=dict(sorted(categories.items())), completeMacros=len(foods),
                    non100gServings=sum(bool(food['portions']) for food in foods),
                    withMicronutrients=sum(any(n['code'] in micronutrient_codes for n in food['nutrients'])
                                           for food in foods),
                    optionalNutrientFoodCounts=dict(sorted(optional_counts.items())),
                    limitations=['Packaged foods recorded in India, not an exhaustive Indian market or recipe catalog.',
                                 'No barcode coverage, and only normalized identical brand and name pairs can be checked.',
                                 'Source label transcriptions have not all been rechecked against current packaging.',
                                 'Ambiguous bases excluded. No volume conversion or missing nutrient imputation.'])
    return foods, audit, coverage


def build(input_path, output, version=VERSION):
    if file_sha256(input_path) != SOURCE_SHA256:
        raise ValueError('Input SHA-256 does not match the pinned Mendeley Data v1 CSV')
    foods, audit, coverage = load_foods(input_path)
    return write_pack(foods, output=output, pack_id=PACK_ID, name=NAME, version=version, source_url=SOURCE_URL,
                      license_text=LICENSE, citation=CITATION, audit=audit, paths=[input_path], coverage=coverage)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__, epilog=f'Official source CSV: {SOURCE_CSV_URL}')
    parser.add_argument('--input', required=True, type=pathlib.Path)
    parser.add_argument('--output', required=True, type=pathlib.Path)
    parser.add_argument('--version', default=VERSION)
    args = parser.parse_args()
    try:
        build(args.input, args.output, args.version)
    except (ValueError, csv.Error, OSError) as error:
        parser.exit(1, f'{error}\n')
