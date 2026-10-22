package com.forge.workout.ui.screens.nutrition

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Restaurant
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.forge.workout.R
import com.forge.workout.nutrition.catalog.CatalogIssue
import com.forge.workout.nutrition.model.CatalogPackEntity
import com.forge.workout.nutrition.model.FoodEntity
import com.forge.workout.nutrition.model.FoodUnit
import com.forge.workout.ui.components.EmptyState
import com.forge.workout.ui.components.ScreenHeader
import com.forge.workout.ui.components.numberFormat
import com.forge.workout.viewmodel.CatalogFeedback
import com.forge.workout.viewmodel.CatalogUiState
import com.forge.workout.viewmodel.CatalogViewModel
import kotlinx.coroutines.launch

@Composable
fun FoodCatalogScreen(
    viewModel: CatalogViewModel,
    onBack: () -> Unit,
    onFood: ((String) -> Unit)? = null,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showDownload by rememberSaveable { mutableStateOf(false) }
    val document = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::installDocument)
    }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    val linkFailed = stringResource(R.string.catalog_link_failed)
    val openLink: (String) -> Unit = { address ->
        try {
            uriHandler.openUri(address)
        } catch (_: Exception) {
            scope.launch { snackbar.showSnackbar(linkFailed) }
        }
    }
    LaunchedEffect(viewModel) { viewModel.ensureBundled() }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            ScreenHeader(stringResource(R.string.catalog_title), stringResource(R.string.catalog_subtitle), onBack)
            LazyColumn(
                Modifier.weight(1f).testTag("catalog_list"),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item(key = "manage", contentType = "controls") {
                    CatalogCard {
                        Text(stringResource(R.string.catalog_manage_title), style = MaterialTheme.typography.titleLarge)
                        Text(stringResource(R.string.catalog_manage_description), style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(pluralStringResource(R.plurals.catalog_total_foods, state.totalFoods, state.totalFoods),
                            Modifier.testTag("catalog_food_count"), style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.primary)
                        Button(
                            onClick = { document.launch(arrayOf("application/zip", "application/octet-stream", "application/x-zip-compressed")) },
                            enabled = !state.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("catalog_import"),
                        ) {
                            Icon(Icons.Rounded.FolderOpen, null)
                            Spacer(Modifier.size(8.dp))
                            Text(stringResource(R.string.catalog_import))
                        }
                        OutlinedButton(onClick = { showDownload = true }, enabled = !state.busy,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("catalog_download")) {
                            Icon(Icons.Rounded.CloudDownload, null)
                            Spacer(Modifier.size(8.dp))
                            Text(stringResource(R.string.catalog_download))
                        }
                    }
                }
                if (state.busy) item(key = "progress", contentType = "status") {
                    CatalogCard {
                        Text(stringResource(R.string.catalog_preparing), style = MaterialTheme.typography.titleMedium)
                        val progress = state.operation.progress.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
                        if (progress > 0f) {
                            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().testTag("catalog_progress"))
                            Text(stringResource(R.string.catalog_progress_percent, (progress * 100).toInt()),
                                style = MaterialTheme.typography.bodySmall)
                        } else LinearProgressIndicator(Modifier.fillMaxWidth().testTag("catalog_progress"))
                        if (state.operation.foodCount > 0) Text(
                            pluralStringResource(R.plurals.catalog_working_foods, state.operation.foodCount, state.operation.foodCount),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        TextButton(onClick = viewModel::cancel, modifier = Modifier.testTag("catalog_cancel")) {
                            Text(stringResource(R.string.catalog_cancel))
                        }
                    }
                }
                feedbackResource(state)?.let { message ->
                    item(key = "feedback", contentType = "status") {
                        CatalogCard {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(message), Modifier.weight(1f).testTag("catalog_feedback"),
                                    color = if (state.operation.error != null || state.feedback == CatalogFeedback.FAILED || state.readFailed)
                                        MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                                    style = MaterialTheme.typography.bodyMedium)
                                if (!state.readFailed) IconButton(onClick = viewModel::clearFeedback) {
                                    Icon(Icons.Rounded.Close, stringResource(R.string.catalog_dismiss_feedback))
                                }
                            }
                            if (state.operation.error != null && !state.busy) Text(
                                stringResource(R.string.catalog_failed_feedback), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (state.readFailed) TextButton(onClick = viewModel::retryRead) {
                                Text(stringResource(R.string.catalog_retry_loading))
                            }
                            if (state.packs.isEmpty() && !state.busy && !state.readFailed) TextButton(onClick = viewModel::retryBundled,
                                modifier = Modifier.testTag("catalog_retry_bundled")) { Text(stringResource(R.string.catalog_retry_bundled)) }
                        }
                    }
                }
                if (state.packs.isEmpty() && !state.busy && !state.readFailed) {
                    item(key = "empty", contentType = "empty") {
                        EmptyState(stringResource(R.string.catalog_empty_title), stringResource(R.string.catalog_empty_description),
                            Icons.Rounded.Restaurant, viewModel::retryBundled, stringResource(R.string.catalog_retry_bundled))
                    }
                }
                if (state.packs.isNotEmpty()) item(key = "installed_heading", contentType = "heading") {
                    Text(stringResource(R.string.catalog_installed_title), style = MaterialTheme.typography.titleLarge)
                }
                items(state.packs, key = { "pack_${it.id}" }, contentType = { "pack" }) { pack ->
                    InstalledCatalogCard(pack, openLink)
                }
                if (state.foods.isNotEmpty()) item(key = "preview_heading", contentType = "heading") {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(stringResource(R.string.catalog_preview_title), style = MaterialTheme.typography.titleLarge)
                        Text(stringResource(R.string.catalog_preview_description), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                items(state.foods, key = { "food_${it.id}" }, contentType = { "food" }) { food ->
                    CatalogFoodRow(food, onFood)
                }
            }
        }
    }
    if (showDownload) DownloadCatalogDialog(
        onDismiss = { showDownload = false },
        onDownload = { url, hash -> showDownload = false; viewModel.installUrl(url, hash) },
    )
}

@Composable
private fun CatalogCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
private fun InstalledCatalogCard(pack: CatalogPackEntity, openLink: (String) -> Unit) {
    CatalogCard(Modifier.testTag("catalog_pack_${pack.id}")) {
        Text(pack.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Rounded.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            Text(stringResource(R.string.catalog_installed_status), color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelLarge)
        }
        Text(pluralStringResource(R.plurals.catalog_pack_foods, pack.foodCount, pack.foodCount), style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(R.string.catalog_pack_version, pack.version), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.catalog_pack_source, pack.citation), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(R.string.catalog_pack_license, pack.license), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (CatalogViewModel.validUrl(pack.sourceUrl)) TextButton(onClick = { openLink(pack.sourceUrl) }) {
            Text(stringResource(R.string.catalog_view_source))
            Spacer(Modifier.size(8.dp))
            Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.size(18.dp))
        }
        if (pack.license.contains("CC0", ignoreCase = true)) TextButton(onClick = {
            openLink("https://creativecommons.org/publicdomain/zero/1.0/")
        }) { Text(stringResource(R.string.catalog_view_license)) }
    }
}

@Composable
private fun CatalogFoodRow(food: FoodEntity, onFood: ((String) -> Unit)?) {
    val content: @Composable () -> Unit = {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(food.name, style = MaterialTheme.typography.titleSmall)
            if (food.brand.isNotBlank()) Text(food.brand, style = MaterialTheme.typography.bodySmall)
            Text(stringResource(when (food.baseUnit) {
                FoodUnit.GRAM -> R.string.catalog_base_grams
                FoodUnit.MILLILITER -> R.string.catalog_base_milliliters
                FoodUnit.SERVING -> R.string.catalog_base_servings
            }, numberFormat(food.baseAmount)), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (food.source.isNotBlank()) Text(food.source, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    val modifier = Modifier.fillMaxWidth().testTag("catalog_food_${food.id}")
    if (onFood == null) Surface(modifier, shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow, content = content)
    else Surface(onClick = { onFood(food.id) }, modifier = modifier, shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow, content = content)
}

@Composable
private fun DownloadCatalogDialog(onDismiss: () -> Unit, onDownload: (String, String) -> Unit) {
    var url by rememberSaveable { mutableStateOf("") }
    var hash by rememberSaveable { mutableStateOf("") }
    var attempted by rememberSaveable { mutableStateOf(false) }
    val validUrl = CatalogViewModel.validUrl(url.trim())
    val validHash = CatalogViewModel.validSha256(hash.trim())
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.catalog_download_title)) },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(stringResource(R.string.catalog_download_description), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth().testTag("catalog_download_url"),
                    label = { Text(stringResource(R.string.catalog_download_url)) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                    isError = attempted && !validUrl,
                    supportingText = { if (attempted && !validUrl) Text(stringResource(R.string.catalog_invalid_url)) })
                OutlinedTextField(hash, { hash = it }, Modifier.fillMaxWidth().testTag("catalog_download_hash"),
                    label = { Text(stringResource(R.string.catalog_download_hash)) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), isError = attempted && !validHash,
                    supportingText = { if (attempted && !validHash) Text(stringResource(R.string.catalog_invalid_hash)) })
            }
        }, confirmButton = {
            TextButton(onClick = { attempted = true; if (validUrl && validHash) onDownload(url.trim(), hash.trim()) },
                modifier = Modifier.testTag("catalog_download_confirm")) { Text(stringResource(R.string.catalog_download_confirm)) }
        }, dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}

private fun feedbackResource(state: CatalogUiState): Int? {
    state.operation.error?.let { failure -> return when (failure.issue) {
        CatalogIssue.INVALID_PACK -> R.string.catalog_invalid_pack
        CatalogIssue.UNSUPPORTED_VERSION -> R.string.catalog_unsupported_version
        CatalogIssue.HASH_MISMATCH -> R.string.catalog_hash_mismatch
        CatalogIssue.ID_CONFLICT -> R.string.catalog_id_conflict
        CatalogIssue.TOO_LARGE -> R.string.catalog_too_large
        CatalogIssue.INSUFFICIENT_STORAGE -> R.string.catalog_insufficient_storage
        CatalogIssue.IO_ERROR -> R.string.catalog_io_error
    } }
    if (state.readFailed) return R.string.catalog_read_failed
    return when (state.feedback) {
        CatalogFeedback.INSTALLED -> R.string.catalog_installed_feedback
        CatalogFeedback.ALREADY_INSTALLED -> R.string.catalog_current_feedback
        CatalogFeedback.CANCELLED -> R.string.catalog_cancelled_feedback
        CatalogFeedback.FAILED -> R.string.catalog_failed_feedback
        CatalogFeedback.INVALID_URL -> R.string.catalog_invalid_url
        CatalogFeedback.INVALID_HASH -> R.string.catalog_invalid_hash
        null -> null
    }
}
