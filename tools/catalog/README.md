# Offline food catalog

The bundled catalog contains **13,324 real USDA records**, **993,304 nutrient rows** and **36,142 source portions**. Sources are 7,793 SR Legacy foods, 5,332 FNDDS 2021–2023 foods, and 199 branded records. Exact-name/brand duplicates and repeated branded GTINs were excluded (101 source records). Eighteen portions without a positive source gram equivalent were excluded; missing nutrients were never replaced with zero.

USDA FoodData Central is public-domain/CC0 data. Attribution: **U.S. Department of Agriculture, Agricultural Research Service. FoodData Central.** See [licensing/API guide](https://fdc.nal.usda.gov/api-guide/), [downloads](https://fdc.nal.usda.gov/download-datasets/), [SR documentation](https://www.ars.usda.gov/Arsuserfiles/80400525/Data/SR-Legacy/SR-Legacy_Doc.Pdf) and [FNDDS documentation](https://www.ars.usda.gov/ARSUserFiles/80400530/pdf/fndds/2021_2023_FNDDS_Doc.pdf).

The Indian filter is a curated grouping of USDA-listed dishes and common kitchen ingredients (655 records), not a claim of Indian geographic origin. Roti, dosa, samosa and paneer are actual FNDDS records. Dahi/curd/yogurt, chana/chickpeas, brinjal/eggplant and roti/chapati aliases improve search. No Samsung Health, Open Food Facts or ICMR-NIN data is bundled; the latter two were not used under assumed redistribution rights.

## Reproduce

Download the official [SR Legacy CSV archive](https://fdc.nal.usda.gov/fdc-datasets/FoodData_Central_sr_legacy_food_csv_2018-04.zip) and [FNDDS JSON archive](https://fdc.nal.usda.gov/fdc-datasets/FoodData_Central_survey_food_json_2024-10-31.zip). Branded source samples are preserved under `source-samples/`; the API's public DEMO_KEY was used only during preprocessing and is not in the application.

```text
python tools/catalog/import_usda.py --sr /path/to/sr.zip --survey /path/to/survey.zip --branded tools/catalog/source-samples/branded-200.json --branded-detail tools/catalog/source-samples/branded-demo-details.json --output app/src/main/assets/nutrition/usda-core.forgefoods
python -m unittest discover -s tools/catalog -p "test_*.py"
```

The standard-library pipeline keeps source FDC IDs, raw/cooked names, full available nutrient units, and source-defined gram portions. Standardized `foodNutrients` are per 100g; branded `labelNutrients` are per serving and are not mixed into that basis. No density is invented for mL servings. Published Atwater kcal are used only when the ordinary source kcal field is absent. Each generated report records input hashes, counts, exclusions, payload/ZIP hashes and size. The current asset is approximately 3.8 MB compressed and 77 MB of structured reference data before database indexing.

## Pack format

A `.forgefoods` file is a ZIP containing **only** `manifest.json` and UTF-8 `foods.jsonl`. Manifest fields: `format="forge-food-catalog"`, `formatVersion=1`, stable pack `id`, `name`, `version`, `sourceUrl`, `license`, `citation`, `sha256` of uncompressed JSONL bytes, `foodCount`, `payloadBytes`, and a `sourceCounts` object whose counts sum to the food count. Records use stable food IDs and the keys shown in the importer; unknown nutrient values are JSON null. Each line is one complete food with nutrient and portion lists.

The Android installer streams and validates structure, checksums, IDs, quantities, units and record counts before a transaction; updates keep physical search row IDs. It rejects personal/other-pack ID conflicts, safely removes stale references from the same pack and leaves personal snapshots intact. Limits permit up to 250,000 foods, a 100 MB ZIP, 512 MB payload and a 512 KB record. Storage is checked before installation. Optional HTTPS download requires the publisher's **whole ZIP SHA-256**, distinct from the internal payload hash.

Packs can be supplied as local files or static HTTPS downloads. Additional publicly hosted regional packs are not supplied by Forge; the installer/pipeline provide the format for compatible, appropriately licensed packs. There is no account, cloud sync, camera permission or barcode scanner.
