package com.forge.workout.nutrition.catalog

import androidx.annotation.StringRes
import com.forge.workout.R
import com.forge.workout.nutrition.model.CatalogPackEntity

internal enum class OfficialCatalogPackStatus(val canInstall: Boolean) {
    DOWNLOAD(true), UPDATE(true), INSTALLED(false), DIFFERENT_VERSION(false)
}

internal data class OfficialCatalogPack(
    val id: String,
    val name: String,
    @StringRes val descriptionRes: Int,
    @StringRes val regionRes: Int,
    val version: String,
    val archiveBytes: Long,
    val foodCount: Int,
    val citation: String,
    val sourceUrl: String,
    val license: String,
    val licenseUrl: String,
    val downloadUrl: String,
    val archiveSha256: String,
    val payloadSha256: String,
) {
    fun status(installed: CatalogPackEntity?): OfficialCatalogPackStatus {
        if (installed == null) return OfficialCatalogPackStatus.DOWNLOAD
        if (installed.id != id) return OfficialCatalogPackStatus.DIFFERENT_VERSION
        if (installed.version == version && installed.foodCount == foodCount &&
            installed.sha256.equals(payloadSha256, ignoreCase = true)
        ) return OfficialCatalogPackStatus.INSTALLED

        val local = stableVersion(installed.version) ?: return OfficialCatalogPackStatus.DIFFERENT_VERSION
        val offered = stableVersion(version) ?: return OfficialCatalogPackStatus.DIFFERENT_VERSION
        return if (compareValuesBy(local, offered, { it.first }, { it.second }, { it.third }) < 0) {
            OfficialCatalogPackStatus.UPDATE
        } else {
            OfficialCatalogPackStatus.DIFFERENT_VERSION
        }
    }
}

private val stableVersionPattern = Regex("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)")

private fun stableVersion(value: String): Triple<Int, Int, Int>? {
    val (major, minor, patch) = stableVersionPattern.matchEntire(value)?.destructured ?: return null
    return Triple(major.toIntOrNull() ?: return null, minor.toIntOrNull() ?: return null, patch.toIntOrNull() ?: return null)
}

internal object OfficialCatalogPacks {
    private const val RELEASE = "https://github.com/ritikverma098/Forge-Fitness-Tracker/releases/download/v1.4.0/"

    val available: List<OfficialCatalogPack> = listOf(
        OfficialCatalogPack(
            id = "india-packaged",
            name = "India packaged foods",
            descriptionRes = R.string.catalog_india_description,
            regionRes = R.string.catalog_india_region,
            version = "1.0.0",
            archiveBytes = 40_933,
            foodCount = 631,
            citation = "Duddela Sai Prashanth, Ananya Devadiga, Saathwik Saathwik, Priya R Kamath and Rakshith Bhandary " +
                "(2026). Indian Packaged Foods Nutritional Composition Dataset (2026), v1. Mendeley Data. " +
                "https://doi.org/10.17632/rhswn9z9xr.1. Filtered and converted for Forge.",
            sourceUrl = "https://data.mendeley.com/datasets/rhswn9z9xr/1",
            license = "CC BY 4.0",
            licenseUrl = "https://creativecommons.org/licenses/by/4.0/",
            downloadUrl = "${RELEASE}forge-india-packaged-1.0.0.forgefoods",
            archiveSha256 = "7fc1b291170f04b2ce9e2a59625b6152fb6c992dc0e3dc12dc4b19a039073aa3",
            payloadSha256 = "67a0ea2cb0512a0ae202acef1f348435c70c29784b47cea227ad482528054c19",
        ),
        OfficialCatalogPack(
            id = "usda-branded-expansion",
            name = "USDA branded expansion",
            descriptionRes = R.string.catalog_usda_description,
            regionRes = R.string.catalog_usda_region,
            version = "1.0.0",
            archiveBytes = 1_425_301,
            foodCount = 13_041,
            citation = "U.S. Department of Agriculture, Agricultural Research Service. FoodData Central.",
            sourceUrl = "https://fdc.nal.usda.gov/download-datasets/",
            license = "CC0 1.0 / public domain",
            licenseUrl = "https://creativecommons.org/publicdomain/zero/1.0/",
            downloadUrl = "${RELEASE}forge-usda-branded-1.0.0.forgefoods",
            archiveSha256 = "a99b80dd947ab91dae31f81b983295f655f629d75b3155b3fb3257242d01bcb1",
            payloadSha256 = "22ce6c7542ec9343cdecb763c67f50423507815055c3b1919a745035b1b339b3",
        ),
    )
}
