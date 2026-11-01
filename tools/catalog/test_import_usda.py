import json
import pathlib
import unittest
import import_usda as importer

SAMPLES = pathlib.Path(__file__).with_name('source-samples')

class SourceConversionTest(unittest.TestCase):
    def test_missing_and_explicit_zero_stay_distinct(self):
        self.assertIsNone(importer.amount(''))
        self.assertIsNone(importer.amount(None))
        self.assertEqual(0, importer.amount('0'))
        self.assertIsNone(importer.amount('-1'))
        self.assertIsNone(importer.amount('nan'))

    def test_kj_is_never_mislabeled_as_kcal(self):
        rows = importer.canonical_nutrients([importer.nutrient('268', 'Energy', 'KJ', 418.4)])
        self.assertFalse(any(x['code'] == 'energy' for x in rows))
        self.assertEqual('kj', rows[0]['unit'])

    def test_published_atwater_is_fallback_only(self):
        atwater = importer.nutrient('958', 'Energy (Atwater Specific Factors)', 'KCAL', 90)
        primary = importer.nutrient('208', 'Energy', 'KCAL', 89)
        self.assertEqual(90, next(x for x in importer.canonical_nutrients([atwater]) if x['code'] == 'energy')['value'])
        self.assertEqual(89, next(x for x in importer.canonical_nutrients([primary, atwater]) if x['code'] == 'energy')['value'])

    def test_branded_label_serving_and_reference_basis_are_separate(self):
        food = importer.full_food(dict(fdcId=1, description='Source test', brandOwner='Source brand', servingSize=85,
            servingSizeUnit='g', householdServingFullText='4 pieces', labelNutrients={'calories': {'value': 165}},
            foodNutrients=[dict(number='208', name='Energy', unitName='KCAL', amount=194)]), 'USDA branded label')
        self.assertEqual(100, food['baseAmount'])
        self.assertEqual(194, food['nutrients'][0]['value'])
        self.assertEqual(85, food['portions'][0]['baseQuantity'])
        self.assertAlmostEqual(164.9, food['nutrients'][0]['value'] * food['portions'][0]['baseQuantity'] / food['baseAmount'])

    def test_official_volume_branded_reference_preserves_milliliters(self):
        raw = json.loads((SAMPLES / 'branded-2598460.json').read_text(encoding='utf-8-sig'))
        food = importer.full_food(raw, 'USDA branded label')
        self.assertEqual('MILLILITER', food['baseUnit'])
        self.assertEqual(100, food['millilitersPerBase'])
        self.assertIsNone(food['gramsPerBase'])
        calories = next(row['value'] for row in food['nutrients'] if row['code'] == 'energy')
        self.assertEqual(40, calories)
        serving = next(row for row in food['portions'] if row['id'] == 'label')
        self.assertEqual(250, serving['milliliters'])
        self.assertIsNone(serving['grams'])
        self.assertEqual(250, serving['baseQuantity'])
        self.assertEqual(100, calories * serving['baseQuantity'] / food['baseAmount'])
        self.assertEqual(raw['labelNutrients']['calories']['value'],
                         calories * serving['baseQuantity'] / food['baseAmount'])

    def test_official_gram_branded_reference_remains_in_grams(self):
        raw = json.loads((SAMPLES / 'branded-demo-details.json').read_text(encoding='utf-8-sig'))
        food = importer.full_food(raw, 'USDA branded label')
        self.assertEqual('GRAM', food['baseUnit'])
        self.assertEqual(100, food['gramsPerBase'])
        self.assertIsNone(food['millilitersPerBase'])
        serving = next(row for row in food['portions'] if row['id'] == 'label')
        self.assertEqual(raw['servingSize'], serving['grams'])
        self.assertIsNone(serving['milliliters'])

    def test_unknown_branded_reference_basis_is_rejected(self):
        for identifying_fields in ({'dataType': 'Branded'}, {'foodClass': 'Branded'}, {'brandOwner': 'Example'}):
            for unit in (None, '', 'oz', 'unknown'):
                with self.subTest(identifying_fields=identifying_fields, unit=unit):
                    raw = dict(fdcId=1, description='Abridged source', **identifying_fields)
                    if unit is not None:
                        raw['servingSizeUnit'] = unit
                    with self.assertRaisesRegex(ValueError, 'reference unit'):
                        importer.full_food(raw, 'USDA')
        with self.assertRaisesRegex(ValueError, 'reference unit'):
            importer.full_food(dict(fdcId=2, description='Abridged source'), 'USDA branded label')

    def test_nonbranded_reference_stays_per_100_grams(self):
        for data_type in ('Foundation', 'Survey (FNDDS)', 'SR Legacy'):
            with self.subTest(data_type=data_type):
                food = importer.full_food(dict(fdcId=1, description='Generic food', dataType=data_type), 'USDA')
                self.assertEqual('GRAM', food['baseUnit'])
                self.assertEqual(100, food['gramsPerBase'])
                self.assertIsNone(food['millilitersPerBase'])

    def test_volume_food_does_not_gain_unsupported_mass_portions(self):
        food = importer.full_food(dict(fdcId=1, description='Source beverage', dataType='Branded',
            servingSize=200, servingSizeUnit='ml', foodPortions=[dict(id=2, gramWeight=220,
                portionDescription='One glass')]), 'USDA')
        self.assertIsNone(food['gramsPerBase'])
        self.assertEqual(['label'], [row['id'] for row in food['portions']])
        self.assertEqual(200, food['portions'][0]['milliliters'])

    def test_synonyms_do_not_confuse_roti_with_rotisserie(self):
        self.assertIn('chapati', importer.make_food(1, 'Bread, chappatti or roti', 'USDA')['aliases'])
        self.assertNotIn('chapati', importer.make_food(2, 'Chicken, rotisserie', 'USDA')['aliases'])
        self.assertIn('dahi', importer.make_food(3, 'Yogurt, plain', 'USDA')['aliases'])

if __name__ == '__main__':
    unittest.main()
