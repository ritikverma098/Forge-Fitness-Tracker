import unittest
import import_usda as importer

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

    def test_volume_is_not_assumed_to_equal_mass(self):
        food = importer.full_food(dict(fdcId=1, description='Source beverage', servingSize=200, servingSizeUnit='ml'), 'Source')
        self.assertIsNone(food['millilitersPerBase'])
        self.assertEqual([], food['portions'])

    def test_synonyms_do_not_confuse_roti_with_rotisserie(self):
        self.assertIn('chapati', importer.make_food(1, 'Bread, chappatti or roti', 'USDA')['aliases'])
        self.assertNotIn('chapati', importer.make_food(2, 'Chicken, rotisserie', 'USDA')['aliases'])
        self.assertIn('dahi', importer.make_food(3, 'Yogurt, plain', 'USDA')['aliases'])

if __name__ == '__main__':
    unittest.main()
