package com.forge.workout.ui.components

import android.content.ActivityNotFoundException
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.forge.workout.R
import com.forge.workout.updates.AvailableUpdate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
internal fun UpdateAvailableDialog(
    release: AvailableUpdate,
    currentVersion: String,
    onLater: () -> Unit,
    onSkip: () -> Unit,
    onDownloaded: () -> Unit
) {
    val uriHandler = LocalUriHandler.current
    var linkFailed by remember(release.apkUrl) { mutableStateOf(false) }
    val date = release.publishedOn.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
    val notes = remember(release.changelog) {
        // Bound each text layout while allowing the full response to scroll.
        release.changelog.lineSequence().chunked(8) { it.joinToString("\n") }
            .flatMap { it.chunked(1024).ifEmpty { listOf("") }.asSequence() }
            .toList()
    }
    AlertDialog(
        onDismissRequest = onLater,
        title = { Text(stringResource(R.string.update_available)) },
        text = {
            Column {
                LazyColumn(
                    Modifier.weight(1f, fill = false).fillMaxWidth().heightIn(max = 270.dp).testTag("update_release_notes"),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (release.title.isNotBlank()) {
                                Text(release.title, style = MaterialTheme.typography.titleSmall,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            Text(stringResource(R.string.update_current_version, currentVersion))
                            Text(stringResource(R.string.update_new_version, release.version))
                            Text(stringResource(R.string.update_release_date, date))
                        }
                    }
                    item { Text(stringResource(R.string.update_whats_new), style = MaterialTheme.typography.titleSmall) }
                    if (release.changelog.isBlank()) {
                        item { Text(stringResource(R.string.update_no_notes)) }
                    } else {
                        items(notes) { Text(it) }
                    }
                }
                TextButton(onClick = onSkip) { Text(stringResource(R.string.update_skip)) }
                if (linkFailed) {
                    Text(stringResource(R.string.update_open_link_failed), color = MaterialTheme.colorScheme.error)
                }
            }
        },
        dismissButton = { TextButton(onClick = onLater) { Text(stringResource(R.string.update_later)) } },
        confirmButton = {
            Button(onClick = {
                linkFailed = !openUpdateLink(uriHandler, release.apkUrl)
                if (!linkFailed) onDownloaded()
            }) { Text(stringResource(R.string.update_download)) }
        }
    )
}

internal fun openUpdateLink(handler: UriHandler, url: String): Boolean = try {
    handler.openUri(url)
    true
} catch (_: ActivityNotFoundException) {
    false
} catch (_: IllegalArgumentException) {
    false
} catch (_: SecurityException) {
    false
}
