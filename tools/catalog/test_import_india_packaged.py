import csv
import hashlib
import json
import pathlib
import tempfile
import unittest
import zipfile
from unittest.mock import patch

from import_india_packaged import REQUIRED_COLUMNS, SOURCE_URL, build, load_foods


class IndiaPackagedImporterTest(unittest.TestCase):
    def setUp(self):
        folder = tempfile.TemporaryDirectory()
        self.addCleanup(folder.cleanup)
        self.input = pathlib.Path(folder.name) / 'input.csv'
        self.output = pathlib.Path(folder.name) / 'india.forgefoods'

    def row(self, source_id, name, **overrides):
        row = dict.fromkeys(REQUIRED_COLUMNS, '')
        row.update({'S.No': str(source_id), 'Item name': name, 'Brand_Name': 'Example Brand',
                    'Category': 'INSTANT FOOD', 'Serving_Size_g': '50', 'Calories_kcal': '154',
                    'Carbohydrates_g': '25', 'Proteins_g': '5', 'Total_Fat_g': '4'})
        row.update(overrides)
        return row

    def write_csv(self, *rows):
        with self.input.open('w', encoding='utf-8-sig', newline='') as file:
            writer = csv.DictWriter(file, fieldnames=[*sorted(REQUIRED_COLUMNS), 'Ingredients', 'Collected_By'])
            writer.writeheader()
            writer.writerows(rows)

    def test_preserves_source_values_and_distinct_preparation_states(self):
        self.write_csv(
            self.row(1, 'Gulab Jamun Mix', Sodium_mg='240', Calcium_mg='20', Sugar_g='10',
                     Vitamin='Vitamin B12 (mcg) 0.5, Vitamin C (mcg) 250, Vitamin A (mcg) 10',
                     Ingredients='Raw ingredient text', Collected_By='Collector details'),
            self.row(2, 'Gulab Jamun Ready to Eat', Serving_Size_g='100', Iron_mg='0',
                     Sodium_mg='<15', Sugar_g='90', Vitamin='Vitamin D (mg) 1, Vitamin B12 3%, Vitamin C (mg) 5%'),
        )

        foods, audit, coverage = load_foods(self.input)
        first, second = foods
        self.assertEqual(('india-packaged:1', '1', SOURCE_URL, 'Example Brand', 'IN'),
                         (first['id'], first['sourceId'], first['sourceUrl'], first['brand'], first['region']))
        self.assertEqual((50, 'GRAM', 50, None),
                         (first['baseAmount'], first['baseUnit'], first['gramsPerBase'], first['millilitersPerBase']))
        self.assertEqual((50, 50, None), tuple(first['portions'][0][key]
                                              for key in ('baseQuantity', 'grams', 'milliliters')))
        self.assertEqual({'energy': (154, 'kcal'), 'carbs': (25, 'g'), 'protein': (5, 'g'), 'fat': (4, 'g'),
                          'usda:269': (10, 'g'), 'usda:301': (20, 'mg'), 'usda:307': (240, 'mg'),
                          'usda:401': (0.25, 'mg'), 'usda:418': (0.5, 'µg')},
                         {n['code']: (n['value'], n['unit']) for n in first['nutrients']})
        self.assertEqual('Gulab Jamun Ready to Eat', second['name'])
        self.assertEqual([], second['portions'])
        self.assertEqual(4, len(second['nutrients']))
        self.assertNotIn('Collector details', json.dumps(foods))
        self.assertNotIn('Raw ingredient text', json.dumps(foods))
        self.assertEqual(1, audit['optional_out_of_range_values_omitted'])
        self.assertEqual(1, coverage['withMicronutrients'])
        self.assertEqual(2, coverage['completeMacros'])
        self.assertEqual(1, coverage['non100gServings'])

    def test_excludes_ambiguous_bases_duplicate_products_and_invalid_nutrients(self):
        self.write_csv(
            self.row(1, 'Mango Snack', Brand_Name='Brand'),
            self.row(2, 'MANGO\u00a0SNACK®', Brand_Name=' brand ', Calories_kcal='159'),
            self.row(3, 'Mango Nectar', Category='JUICE'),
            self.row(4, 'Badam Drink Mix'),
            self.row(5, 'Soy Sauce', Category='SAUCE'),
            self.row(6, 'Ice Cream 250 mL', Category='ICEREAMES'),
            self.row(7, 'Numeric unit suffix', Serving_Size_g='50ml'),
            self.row(8, 'Non-finite value', Proteins_g='nan'),
            self.row(9, 'Negative value', Total_Fat_g='-2'),
            self.row(10, 'Implausible calories', Calories_kcal='700'),
            self.row(11, 'Implausible mass', Carbohydrates_g='60'),
            self.row(12, 'Inconsistent energy', Calories_kcal='50'),
            self.row(13, 'Potato Chips', Proteins_g='0', Calories_kcal='136', Vitamin='Vitamin C 0 mg'),
        )

        foods, audit, coverage = load_foods(self.input)
        self.assertEqual(['india-packaged:13'], [food['id'] for food in foods])
        self.assertEqual({'duplicate_identity_rows': 2, 'ambiguous_serving_basis_rows': 4,
                          'invalid_core_or_serving_rows': 3, 'impossible_energy_rows': 1,
                          'macro_mass_mismatch_rows': 1, 'energy_macro_mismatch_rows': 1}, dict(audit))
        self.assertEqual(12, coverage['excludedRows'])
        self.assertEqual(0, coverage['withMicronutrients'])
        self.assertEqual(0, next(n['value'] for n in foods[0]['nutrients'] if n['code'] == 'protein'))

    def test_rejects_duplicate_source_ids(self):
        self.write_csv(self.row(7, 'Food A'), self.row(7, 'Food B'))
        with self.assertRaisesRegex(ValueError, 'unique positive source row IDs'):
            load_foods(self.input)

    def test_omits_known_mislabelled_biotin_without_imputing_a_value(self):
        self.write_csv(self.row(761, 'Kissan Mixed Fruit Jam', Vitamin='Vitamin B12 (mcg) 18.8, Vitamin B6 (mg) 1.2'))
        foods, audit, _ = load_foods(self.input)
        self.assertEqual({'energy', 'carbs', 'protein', 'fat', 'usda:415'},
                         {n['code'] for n in foods[0]['nutrients']})
        self.assertEqual(1, audit['known_mislabelled_vitamins_omitted'])

    def test_checks_source_digest_and_uses_shared_deterministic_pack_format(self):
        self.write_csv(self.row(1, 'Poha Mix'))
        with self.assertRaisesRegex(ValueError, 'SHA-256'):
            build(self.input, self.output)
        self.assertFalse(self.output.exists())
        second_output = self.output.with_name('second.forgefoods')
        with patch('import_india_packaged.SOURCE_SHA256', hashlib.sha256(self.input.read_bytes()).hexdigest()):
            with patch('builtins.print'):
                report = build(self.input, self.output)
                build(self.input, second_output)
        self.assertEqual(self.output.read_bytes(), second_output.read_bytes())
        with zipfile.ZipFile(self.output) as archive:
            self.assertEqual(['manifest.json', 'foods.jsonl'], archive.namelist())
            manifest = json.loads(archive.read('manifest.json'))
            payload = archive.read('foods.jsonl')
        self.assertEqual(('india-packaged', '1.0.0', 1),
                         (manifest['id'], manifest['version'], manifest['foodCount']))
        self.assertEqual(hashlib.sha256(payload).hexdigest(), manifest['sha256'])
        self.assertEqual({'instant food': 1}, report['coverage']['categories'])
        self.assertEqual('Poha Mix', json.loads(payload)['name'])
