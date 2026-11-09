import json
from collections import Counter
from pathlib import Path
import tempfile
import unittest
import zipfile

import import_usda_branded as importer


def barcode(number):
    body = f"890600{number:06d}"
    total = sum(int(digit) * (3 if index % 2 == 0 else 1)
                for index, digit in enumerate(reversed(body)))
    return body + str((-total) % 10)


def product(fdc_id=1, **fields):
    result = dict(
        fdcId=fdc_id, gtinUpc=barcode(fdc_id), description="Basmati rice, dry",
        brandName="Fixture Foods", foodClass="Branded", dataType="Branded",
        marketCountry="United States", brandedFoodCategory="Rice",
        publicationDate="9/23/2025", modifiedDate="9/22/2025",
        servingSize=50, servingSizeUnit="GRM", householdServingFullText="1/4 cup",
        foodNutrients=[
            dict(number="208", name="Energy", unitName="kcal", amount=360),
            dict(number="203", name="Protein", unitName="g", amount=8),
            dict(number="204", name="Fat", unitName="g", amount=0),
            dict(number="205", name="Carbohydrate", unitName="g", amount=82),
            dict(number="307", name="Sodium", unitName="mg", amount=2),
        ],
        labelNutrients={"calories": {"value": 180}, "protein": {"value": 4}},
    )
    result.update(fields)
    return result


def archive(path, rows, inline_footer=True):
    body = ',\n'.join(json.dumps(row, ensure_ascii=False) for row in rows)
    footer = "]}" if inline_footer else "\n]}"
    with zipfile.ZipFile(path, "w") as output:
        output.writestr("source.json", '{"BrandedFoods": [\n' + body + footer + '\n')


class BrandedExpansionTest(unittest.TestCase):
    def test_gtin_prefixes_and_real_calendar_order(self):
        source = barcode(1)
        self.assertEqual("0" + source, importer.gtin(source))
        self.assertEqual(importer.gtin(source), importer.gtin("0" + source))
        self.assertIsNone(importer.gtin(source[:-1] + str((int(source[-1]) + 1) % 10)))
        self.assertIsNone(importer.gtin("00000000000000"))
        self.assertLess(importer.calendar_key("9/27/2025"), importer.calendar_key("10/1/2025"))
        self.assertEqual(importer.calendar_key("10/1/2025"), importer.calendar_key("2025-10-01"))

    def test_streams_usda_wrapped_array_with_either_footer_layout(self):
        rows = [product(), product(2, description="Plain dal, cooked")]
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "source.zip"
            for inline in (False, True):
                archive(source, rows, inline)
                self.assertEqual(rows, list(importer.source_rows(source)))

    def test_valid_volume_label_does_not_gain_grams(self):
        row = product(description="Lassi", servingSize=250, servingSizeUnit="MLT",
                      householdServingFullText="1 bottle", labelNutrients={})
        food, category, relevant = importer.convert(row, "fixture-id", "NZ", Counter())
        self.assertEqual("fixture-id", food["id"])
        self.assertEqual("1", food["sourceId"])
        self.assertEqual("NZ", food["region"])
        self.assertNotEqual("IN", food["region"])
        self.assertTrue(relevant)
        self.assertEqual("MILLILITER", food["baseUnit"])
        self.assertIsNone(food["gramsPerBase"])
        self.assertEqual(100, food["millilitersPerBase"])
        self.assertEqual(250, food["portions"][0]["milliliters"])
        self.assertIsNone(food["portions"][0]["grams"])
        self.assertEqual("Rice", category)

    def test_quality_requires_source_serving_and_kcal_gram_values(self):
        cases = (
            (product(servingSizeUnit="oz"), "unsupported_serving_basis"),
            (product(servingSize=0), "invalid_native_serving_quantity"),
            (product(labelNutrients={"calories": {"value": 30}}), "conflicting_label_and_reference_values"),
            (product(foodNutrients=product()["foodNutrients"][1:]), "missing_core_nutrients"),
        )
        bad_energy = product()
        bad_energy["foodNutrients"][0]["unitName"] = "kJ"
        for row, reason in (*cases, (bad_energy, "invalid_core_value_or_unit")):
            with self.subTest(reason=reason):
                with self.assertRaisesRegex(importer.Rejected, reason):
                    importer.convert(row, "fixture", "US", Counter())

    def test_newest_revision_wins_and_discontinued_cannot_fall_back(self):
        original = product(1)
        newest = product(2, gtinUpc=original["gtinUpc"], publicationDate="10/2/2025",
                         marketCountry="US", description="Basmati rice, cooked")
        old_lassi = product(3, description="Lassi")
        discontinued = product(4, gtinUpc=old_lassi["gtinUpc"], publicationDate="10/3/2025",
                               discontinuedDate="10/3/2025", description="Lassi")
        nz = product(5, marketCountry="New Zealand", description="Milk")
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "source.zip"
            archive(path, [discontinued, original, newest, old_lassi, nz])
            foods, coverage, audit = importer.select_foods(path, set(), set())
        self.assertEqual({"2", "5"}, {food["sourceId"] for food in foods})
        cooked = next(food for food in foods if food["sourceId"] == "2")
        self.assertEqual("usda:branded:gtin:" + importer.gtin(original["gtinUpc"]), cooked["id"])
        self.assertEqual({"US": 1, "NZ": 1}, coverage["selectedMarketFoods"])
        self.assertEqual(1, coverage["indiaRelevantNameOrBrandFoods"])
        self.assertEqual(2, audit["older_gtin_or_fdc_revisions_excluded"])
        self.assertEqual(1, audit["discontinued_latest_records"])

    def test_core_exclusions_exact_duplicates_and_distinct_pack_variants(self):
        excluded_id = product(1, description="Core ID")
        excluded_gtin = product(2, description="Core barcode")
        duplicate_a = product(3, packageWeight="500 g")
        duplicate_b = product(4, packageWeight="500 g")
        variant = product(5, packageWeight="1 kg")
        unrecognized_code = product(6, description="Bread", gtinUpc="")
        records = [excluded_id, excluded_gtin, duplicate_a, duplicate_b, variant, unrecognized_code]
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "source.zip"
            archive(path, records)
            core_ids = {"usda:fdc:1"}
            core_barcodes = {importer.gtin(excluded_gtin["gtinUpc"])}
            foods, coverage, audit = importer.select_foods(path, core_ids, core_barcodes)
            archive(path, reversed(records))
            self.assertEqual((foods, coverage, audit), importer.select_foods(path, core_ids, core_barcodes))
        source_ids = {food["sourceId"] for food in foods}
        self.assertEqual(1, len(source_ids & {"3", "4"}))
        self.assertTrue({"5", "6"} <= source_ids)
        self.assertTrue(any(food["id"] == "usda:fdc:6" for food in foods))
        self.assertEqual(1, audit["exact_product_duplicates_excluded"])
        self.assertEqual(2, audit["bundled_fdc_or_gtin_duplicates_excluded"])


if __name__ == "__main__":
    unittest.main()
