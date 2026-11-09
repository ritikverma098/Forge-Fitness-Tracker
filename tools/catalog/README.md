# Offline food catalog

The bundled catalog contains **13,324 real USDA records**, **993,304 nutrient rows** and **36,331 source portions**. Sources are 7,793 SR Legacy foods, 5,332 FNDDS 2021–2023 foods, and 199 branded records. Exact-name/brand duplicates and repeated branded GTINs were excluded (101 source records). Eighteen portions without a positive source gram equivalent were excluded; missing nutrients were never replaced with zero.

USDA FoodData Central is public-domain/CC0 data. Attribution: **U.S. Department of Agriculture, Agricultural Research Service. FoodData Central.** See [licensing/API guide](https://fdc.nal.usda.gov/api-guide/), [downloads](https://fdc.nal.usda.gov/download-datasets/), [SR documentation](https://www.ars.usda.gov/Arsuserfiles/80400525/Data/SR-Legacy/SR-Legacy_Doc.Pdf) and [FNDDS documentation](https://www.ars.usda.gov/ARSUserFiles/80400530/pdf/fndds/2021_2023_FNDDS_Doc.pdf).

The Indian filter is a curated grouping of USDA-listed dishes and common kitchen ingredients (655 records), not a claim of Indian geographic origin. Roti, dosa, samosa and paneer are actual FNDDS records. Dahi/curd/yogurt, chana/chickpeas, brinjal/eggplant and roti/chapati aliases improve search. No Samsung Health, Open Food Facts or ICMR-NIN data is bundled; the latter two were not used under assumed redistribution rights.

## Reproduce

Download the official [SR Legacy CSV archive](https://fdc.nal.usda.gov/fdc-datasets/FoodData_Central_sr_legacy_food_csv_2018-04.zip) and [FNDDS JSON archive](https://fdc.nal.usda.gov/fdc-datasets/FoodData_Central_survey_food_json_2024-10-31.zip). Branded source samples are preserved under `source-samples/`; the API's public DEMO_KEY was used only during preprocessing and is not in the application. `branded-200-with-units.json` preserves the original abridged IDs, nutrients and brand fields, enriched with `servingSize`, `servingSizeUnit` and `householdServingFullText` from the full official records retrieved on 31 October 2026. All 200 exact IDs were checked in ten documented `/foods?fdcIds=...&format=full` batches of 20. The original abridged file is retained for provenance, but deliberately cannot be imported without unit metadata. Full official gram and mL examples are included as regression fixtures.

```text
python tools/catalog/import_usda.py --sr /path/to/sr.zip --survey /path/to/survey.zip --branded tools/catalog/source-samples/branded-200-with-units.json --branded-detail tools/catalog/source-samples/branded-demo-details.json --version 2026-10-31 --output app/src/main/assets/nutrition/usda-core.forgefoods
python -m unittest discover -s tools/catalog -p "test_*.py"
```

The standard-library pipeline keeps source FDC IDs, raw/cooked names, full available nutrient units, and source-defined portions. Generic `foodNutrients` are per 100 g; branded values use 100 g or 100 mL according to their submitted unit, as documented by [USDA](https://fdc.nal.usda.gov/GBFPD_Documentation/). Branded `labelNutrients` are per serving and are not mixed into that basis. Fourteen bundled foods are native mL foods with no invented gram conversion; 189 additional native label portions are now available. No nutrient values, existing names/brands or stable IDs changed in this correction. Published Atwater kcal are used only when the ordinary source kcal field is absent. Each generated report records input hashes, counts, exclusions, payload/ZIP hashes and size. The current asset is approximately 3.8 MB compressed and 77 MB of structured reference data before database indexing.

Initialization upgrades the specifically recognized old bundled payload to this correction. Arbitrary complete explicitly imported revisions retain the existing preservation policy. Catalog replacement does not rewrite frozen diary or recipe-ingredient snapshots; correcting an older logged entry remains an explicit user action.

## Pack format

A `.forgefoods` file is a ZIP containing **only** `manifest.json` and UTF-8 `foods.jsonl`. Manifest fields: `format="forge-food-catalog"`, `formatVersion=1`, stable pack `id`, `name`, `version`, `sourceUrl`, `license`, `citation`, `sha256` of uncompressed JSONL bytes, `foodCount`, `payloadBytes`, and a `sourceCounts` object whose counts sum to the food count. Records use stable food IDs and the keys shown in the importer; unknown nutrient values are JSON null. Each line is one complete food with nutrient and portion lists.

The Android installer streams and validates structure, checksums, IDs, quantities, units and record counts before a transaction; updates keep physical search row IDs. It rejects personal/other-pack ID conflicts, safely removes stale references from the same pack and leaves personal snapshots intact. Limits permit up to 250,000 foods, a 100 MB ZIP, 512 MB payload and a 512 KB record. Storage is checked before installation. Optional HTTPS download requires the publisher's **whole ZIP SHA-256**, distinct from the internal payload hash.

Packs can be supplied as local files or static HTTPS downloads. The expansion packs are independently versioned and distributed separately from the APK. There is no account, cloud sync, camera permission or barcode scanner.

## Source review for the optional packs

- **Indian Packaged Foods Nutritional Composition Dataset (2026), version 1.** The publisher identifies 852 India-market packaged foods and grants CC BY 4.0. Nutrition and serving fields are label transcriptions. Most rows lack a retailer or collection date, so Forge screens units, duplicates and consistency and retains the source attribution. Product labels may change after collection. [Publisher and DOI](https://doi.org/10.17632/rhswn9z9xr.1) · [License](https://creativecommons.org/licenses/by/4.0/)
- **USDA FoodData Central Global Branded Food Products Database, October 2026.** The official bulk archive is CC0. Labels identify the market and use a native 100 g or 100 mL basis. The separately distributed USDA expansion uses records from the source's US and New Zealand markets. India-related names and imported brands do not establish that a product is sold in India. [Official download](https://fdc.nal.usda.gov/download-datasets/) · [USDA branded data documentation](https://fdc.nal.usda.gov/GBFPD_Documentation/)
- **ICMR-NIN IFCT 2017 and INDB.** NIN's IFCT states that electronic reproduction for a product requires prior written permission. INDB relies in part on those tables and its data repository does not provide an explicit redistribution grant. Forge keeps these datasets out of its packs. [IFCT copyright notice](https://www.nin.res.in/ebooks/IFCT2017.pdf) · [INDB source documentation](https://github.com/lindsayjaacks/Indian-Nutrient-Databank-INDB-)
- **Open Food Facts.** Its official Parquet export is obtainable separately. The database is ODbL, individual contents use the Database Contents License, and images have separate terms. A future import would require a separately attributed and licensed derivative database with quality checks for units and incomplete labels. These packs contain no OFF data or images. [Official reuse terms](https://huggingface.co/datasets/openfoodfacts/product-database#conditions-for-reuse)

## Build the expansion packs

Use Python 3.11 or later from the repository root. Download the CSV from the India dataset's version 1 file listing and the USDA October 2026 Branded Foods JSON ZIP from FoodData Central. The pack importers use the Python standard library.

```text
python tools/catalog/import_india_packaged.py --input /path/to/india-packaged-v1.csv --output /path/to/forge-india-packaged-1.0.0.forgefoods
python tools/catalog/import_usda_branded.py --archive /path/to/FoodData_Central_branded_food_json_2026-10-29.zip --output /path/to/forge-usda-branded-1.0.0.forgefoods
python -m unittest discover -s tools/catalog -p "test_*.py"
```

| Pack | Imported foods | Coverage | Archive size |
| --- | ---: | --- | ---: |
| India packaged foods 1.0.0 | 631 of 852 | 187 distinct brand strings, 55 named source portions, 540 foods with reported micronutrients | 40,933 bytes |
| USDA branded expansion 1.0.0 | 13,041 of 434,001 | 12,000 US and 1,041 New Zealand products, one label serving each, 1,646 native mL foods | 1,425,301 bytes |

India's source CSV SHA-256 is `f64832743d4918c428aab31fb8a3681cd2dee89f3c5f305185ec0edfabe8f303`. Forge filters unclear liquid serving bases, conflicting duplicate labels and implausible source values. It omits a confirmed biotin value transcribed under the source vitamin B12 column. Food IDs are pinned to version 1 source serial numbers. Any future import of the dataset needs an explicit ID mapping. [Published source](https://doi.org/10.17632/rhswn9z9xr.1) · [Manufacturer nutrition table](https://www.hul.co.in/files/92ui5egz/production/a7fcadce2a878b34009fa829fa5bb90cb2746c78.pdf)

The USDA source ZIP SHA-256 is `149e0bce3477e90953d90b65b1af69b16407bfadc86926a9f4e16ba7dc227f4e`. Forge selects a deterministic cross-category subset, keeps the latest eligible label per product identity, excludes bundled duplicates and keeps valid gram/milliliter bases separate. The pack includes 966 India-related names or brands identified by source text and 15 source-explicit unbranded products. It contains no India-market USDA labels. [Official source](https://fdc.nal.usda.gov/download-datasets/)

The accompanying `.report.json` files contain the source and pack SHA-256 values, exact counts and screening exclusions. Both packs can be imported through Nutrition → Food Catalog → Import pack. To run the file-backed Room release check, set `FORGE_TEST_PACK_DIR` to the directory containing both archives and their reports and run `gradlew :app:testDebugUnitTest --tests '*CatalogArtifactHostTest'`.

## Distribute the packs

The 1.4.0 catalog cards pin the two archives by SHA-256. Their format and version are independent of the APK. To distribute a future revision, use a new versioned filename and checksum. [Forge 1.4.0 release assets](https://github.com/ritikverma098/Forge-Fitness-Tracker/releases/tag/v1.4.0)

| Asset | Archive SHA-256 |
| --- | --- |
| `forge-india-packaged-1.0.0.forgefoods` | `7fc1b291170f04b2ce9e2a59625b6152fb6c992dc0e3dc12dc4b19a039073aa3` |
| `forge-usda-branded-1.0.0.forgefoods` | `a99b80dd947ab91dae31f81b983295f655f629d75b3155b3fb3257242d01bcb1` |

Attach `SHA256SUMS.txt` and the two import reports alongside the archives. The India pack carries CC BY 4.0 attribution and its modifications in the manifest. Keep it separate from the CC0 USDA pack and provide the [third-party notices](../../THIRD_PARTY_NOTICES.md) with the release.
