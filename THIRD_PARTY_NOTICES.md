# Third-party notices

These notices cover third-party material included in or used by Forge. They do not assign a license to the project's original code; no overall project license has been selected.

## USDA FoodData Central

**Attribution:** U.S. Department of Agriculture, Agricultural Research Service. FoodData Central.

The bundled `app/src/main/assets/nutrition/usda-core.forgefoods`, its report, and `tools/catalog/source-samples/` contain public USDA FoodData Central records. USDA states that FoodData Central data are public domain and published under CC0 1.0. This includes the branded records selected from FoodData Central; no manufacturer images or packaging artwork are included.

- [USDA usage and licensing guidance](https://fdc.nal.usda.gov/api-guide/#bkmk-9)
- [CC0 1.0 dedication](https://creativecommons.org/publicdomain/zero/1.0/)
- [Official datasets](https://fdc.nal.usda.gov/download-datasets/)
- [Importer, source selection, counts and reproducibility](tools/catalog/README.md)
- [Bundled source/hash report](app/src/main/assets/nutrition/usda-core.forgefoods.report.json)

No Samsung Health, Open Food Facts or ICMR-NIN database is included. Additional packs require their own verified redistribution rights and attribution.

## Compendium of Physical Activities

The six workout activity MET values are attributed to the **2024 Adult Compendium of Physical Activities**. The official project permits commercial use, requests citation and asks that its published MET values be retained. Forge retains the six source MET values; its separately labeled active-fraction setting and subtraction of resting expenditure are application calculations, not changes to those source values.

- [Official project and usage guidance](https://pacompendium.com/)
- [Conditioning exercise table](https://pacompendium.com/conditioning-exercise/)
- [MET unit conversion](https://pacompendium.com/unite-conversions/)

## Gradle wrapper

The Gradle wrapper JAR and scripts are distributed under the Apache License, Version 2.0. The scripts retain their original license headers, and the JAR retains its `META-INF/LICENSE`. A copy of that license is provided in [licenses/Apache-2.0.txt](licenses/Apache-2.0.txt).

- [Gradle source and license](https://github.com/gradle/gradle/tree/v9.6.0)

## Material icons and build dependencies

Material icons are used through the declared AndroidX Compose dependency. Google distributes Material icons under the Apache License, Version 2.0; see the [official licensing guidance](https://developers.google.com/fonts/docs/material_icons#licensing) and the [included license copy](licenses/Apache-2.0.txt). Launcher artwork is a project vector; no external photos or font files are bundled, and typography uses system SansSerif.

AndroidX, Kotlin, Coroutines, Android build tools and test dependencies remain declared in Gradle rather than vendored into this repository. Their respective license notices continue to apply. Publishing this source does not transfer ownership of those dependencies or third-party trademarks.
