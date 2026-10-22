"""Build a verifiable Forge catalog from official USDA downloads. Standard library only."""
import argparse
import csv
import hashlib
import io
import json
import math
import pathlib
import re
import zipfile
from collections import Counter, defaultdict

CORE = {'203': 'protein', '204': 'fat', '205': 'carbs', '208': 'energy'}
ALIASES = [
    (r'\byog[hu]?urt\b', 'dahi curd yogurt yoghurt'),
    (r'\b(chickpeas?|garbanzo)\b', 'chana chickpeas garbanzo bengal gram'),
    (r'\beggplant\b', 'brinjal eggplant aubergine'),
    (r'\b(chap+at+ti|chapati|roti)\b', 'roti chapati chappatti chapatti'),
    (r'\blentils?\b', 'dal daal lentils'),
]
INDIAN = re.compile(r'\b(paneer|chap+at+ti|chapati|roti|dosa|samosa|palak|idli|naan|paratha|biryani|lentils?|chickpeas?|garbanzo|rice|eggplant|yog[hu]?urt|curry|cumin|turmeric)\b', re.I)
AUDIT = Counter()

def amount(value):
    if value is None or value == '':
        return None
    result = float(value)
    if not math.isfinite(result) or result < 0:
        AUDIT['invalid_source_values_kept_unknown'] += 1
        return None
    return result

def nutrient(number, name, unit, value):
    number = str(number).strip()
    code = CORE.get(number, 'usda:' + number)
    return {'code': code, 'name': name.strip(), 'unit': unit.strip().lower(), 'value': amount(value)}

def canonical_nutrients(rows):
    # Published Atwater energy is a separate source measurement; use it only if kcal 208 is absent.
    values = {x['code']: x for x in rows}
    if 'energy' not in values:
        fallback = values.get('usda:958') or values.get('usda:957')
        if fallback and fallback['unit'] == 'kcal':
            values['energy'] = dict(fallback, code='energy')
    return sorted(values.values(), key=lambda x: x['code'])

def make_food(fdc_id, description, source, brand='', notes=''):
    name = description.strip()
    if not name or len(name) > 1000:
        raise ValueError('Invalid source name: ' + repr(name))
    synonyms = ' '.join(alias for pattern, alias in ALIASES if re.search(pattern, name, re.I))
    return dict(id='usda:fdc:' + str(fdc_id), name=name, brand=brand.strip(), aliases=synonyms,
                source=source, sourceId=str(fdc_id), sourceUrl=f'https://fdc.nal.usda.gov/food-details/{fdc_id}/nutrients',
                region='IN' if not brand and INDIAN.search(name) else None, tags='branded' if brand else 'generic',
                baseAmount=100.0, baseUnit='GRAM', gramsPerBase=100.0, millilitersPerBase=None,
                notes=notes[:5000], nutrients=[], portions=[])

def csv_rows(archive, basename):
    path = next(x for x in archive.namelist() if x.rsplit('/', 1)[-1] == basename)
    return csv.DictReader(io.TextIOWrapper(archive.open(path), encoding='utf-8-sig', newline=''))

def sr_foods(path):
    with zipfile.ZipFile(path) as archive:
        specs = {x['id']: x for x in csv_rows(archive, 'nutrient.csv')}
        measures = {x['id']: x['name'] for x in csv_rows(archive, 'measure_unit.csv')}
        foods = {x['fdc_id']: make_food(x['fdc_id'], x['description'], 'USDA SR Legacy (2018)')
                 for x in csv_rows(archive, 'food.csv')}
        for row in csv_rows(archive, 'food_nutrient.csv'):
            if row['fdc_id'] not in foods:
                continue
            spec = specs[row['nutrient_id']]
            foods[row['fdc_id']]['nutrients'].append(nutrient(spec['nutrient_nbr'], spec['name'], spec['unit_name'], row['amount']))
        for row in csv_rows(archive, 'food_portion.csv'):
            food = foods.get(row['fdc_id'])
            grams, count = amount(row['gram_weight']), amount(row['amount'])
            if food is None or not grams or not count:
                AUDIT['portions_without_positive_source_equivalent'] += 1
                continue
            unit = measures.get(row['measure_unit_id'], '')
            if unit.lower() in ('undetermined', 'unknown'):
                unit = ''
            description = row.get('portion_description', '').strip()
            modifier = row.get('modifier', '').strip()
            label = description or ' '.join(x for x in (str(count).rstrip('0').rstrip('.') if count % 1 else str(int(count)), unit, modifier) if x)
            if not label:
                continue
            food['portions'].append(dict(id='sr:' + row['id'], label=label[:250], baseQuantity=grams,
                                         grams=grams, milliliters=None, source='USDA source-defined portion'))
        for food in foods.values():
            food['nutrients'] = canonical_nutrients(food['nutrients'])
        return list(foods.values())

def json_foods(path, source):
    with zipfile.ZipFile(path) as archive:
        raw = json.loads(archive.read(next(x for x in archive.namelist() if x.endswith('.json'))))
    rows = next(x for x in raw.values() if isinstance(x, list))
    return [full_food(x, source) for x in rows]

def full_food(raw, source):
    brand = raw.get('brandName') or raw.get('brandOwner') or ''
    notes = raw.get('footnote', '')
    if raw.get('brandName') and raw.get('brandOwner'):
        notes = '\n'.join(x for x in (notes, 'Manufacturer: ' + raw['brandOwner']) if x)
    food = make_food(raw['fdcId'], raw['description'], source, brand, notes)
    nutrients = []
    for row in raw.get('foodNutrients', []):
        spec = row.get('nutrient', row)
        if spec.get('number'):
            nutrients.append(nutrient(spec['number'], spec['name'], spec['unitName'], row.get('amount')))
    food['nutrients'] = canonical_nutrients(nutrients)
    for row in raw.get('foodPortions', []):
        grams = amount(row.get('gramWeight'))
        label = row.get('portionDescription') or row.get('modifier') or ''
        # A source gramWeight applies to its entire named source portion, not to 100g or arbitrary mL.
        if grams and label and not str(label).isdigit():
            food['portions'].append(dict(id='fdc:' + str(row['id']), label=label[:250], baseQuantity=grams,
                                         grams=grams, milliliters=None, source='USDA source-defined portion'))
    serving = amount(raw.get('servingSize'))
    unit = str(raw.get('servingSizeUnit', '')).lower()
    if serving and unit in ('g', 'grm', 'grams'):
        food['portions'].append(dict(id='label', label=(raw.get('householdServingFullText') or f'{serving:g} g serving')[:250],
                                     baseQuantity=serving, grams=serving, milliliters=None, source='USDA branded label serving'))
    # mL label portions are never equated to grams without a source-provided density.
    if serving and unit in ('ml', 'mlt'):
        AUDIT['ml_label_portions_without_density_not_offered'] += 1
    return food

def build(args):
    sources = []
    paths = []
    if args.sr:
        sources.extend(sr_foods(args.sr)); paths.append(args.sr)
    if args.survey:
        sources.extend(json_foods(args.survey, 'USDA FNDDS 2021–2023')); paths.append(args.survey)
    if args.foundation:
        sources.extend(json_foods(args.foundation, 'USDA Foundation April 2026')); paths.append(args.foundation)
    if args.branded:
        raw = json.loads(pathlib.Path(args.branded).read_text(encoding='utf-8-sig'))
        latest = {}
        for row in raw:
            key = row.get('gtinUpc') or str(row['fdcId'])
            if key not in latest or row.get('publicationDate', '') > latest[key].get('publicationDate', ''):
                latest[key] = row
        sources.extend(full_food(x, 'USDA branded label') for x in latest.values()); paths.append(args.branded)
    if args.branded_detail:
        detail = full_food(json.loads(pathlib.Path(args.branded_detail).read_text(encoding='utf-8-sig')), 'USDA branded label')
        sources = [detail if x['id'] == detail['id'] else x for x in sources]
        paths.append(args.branded_detail)
    chosen, names = {}, set()
    for food in sources:
        key = (food['name'].casefold(), food['brand'].casefold())
        if food['id'] in chosen or key in names:
            AUDIT['duplicate_source_records_excluded'] += 1
            continue
        names.add(key); chosen[food['id']] = food
    foods = sorted(chosen.values(), key=lambda x: x['id'])
    payload = ''.join(json.dumps(x, ensure_ascii=False, separators=(',', ':'), allow_nan=False) + '\n' for x in foods).encode('utf-8')
    counts = dict(Counter(x['source'] for x in foods))
    manifest = dict(format='forge-food-catalog', formatVersion=1, id=args.pack_id, name=args.name, version=args.version,
                    sourceUrl='https://fdc.nal.usda.gov/download-datasets/', license='CC0 1.0 / public domain',
                    citation='U.S. Department of Agriculture, Agricultural Research Service. FoodData Central.',
                    sha256=hashlib.sha256(payload).hexdigest(), foodCount=len(foods), payloadBytes=len(payload), sourceCounts=counts)
    output = pathlib.Path(args.output); output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(output, 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for name, contents in [('manifest.json', json.dumps(manifest, ensure_ascii=False, indent=2).encode('utf-8')), ('foods.jsonl', payload)]:
            info = zipfile.ZipInfo(name, (2026, 10, 12, 0, 0, 0)); info.compress_type = zipfile.ZIP_DEFLATED
            archive.writestr(info, contents, compresslevel=9)
    evidence = dict(manifest, archiveBytes=output.stat().st_size, archiveSha256=hashlib.sha256(output.read_bytes()).hexdigest(),
                    nutrientRows=sum(len(x['nutrients']) for x in foods), portionRows=sum(len(x['portions']) for x in foods),
                    indianFilterFoods=sum(x['region'] == 'IN' for x in foods), audit=dict(AUDIT),
                    inputFiles=[dict(name=pathlib.Path(p).name, sha256=hashlib.sha256(pathlib.Path(p).read_bytes()).hexdigest()) for p in paths])
    pathlib.Path(str(output) + '.report.json').write_text(json.dumps(evidence, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps(evidence, ensure_ascii=False, indent=2))

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    for flag in ('sr', 'survey', 'foundation', 'branded', 'branded-detail'):
        parser.add_argument('--' + flag)
    parser.add_argument('--output', required=True)
    parser.add_argument('--pack-id', default='usda-core')
    parser.add_argument('--name', default='USDA everyday foods')
    parser.add_argument('--version', default='2026-10-12')
    build(parser.parse_args())
