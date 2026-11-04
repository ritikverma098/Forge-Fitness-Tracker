package com.forge.workout.ui.screens.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.forge.workout.BuildConfig
import com.forge.workout.R
import com.forge.workout.ui.components.openUpdateLink
import com.forge.workout.updates.GitHubReleaseSource
import com.forge.workout.updates.UpdateCheckResult
import com.forge.workout.viewmodel.UpdateViewModel

@Composable
internal fun UpdateSettings(updates: UpdateViewModel, enabled: Boolean, onCheck: () -> Unit) {
    val state by updates.state.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current
    var linkFailed by remember { mutableStateOf(false) }
    fun open(url: String): Boolean {
        linkFailed = !openUpdateLink(uriHandler, url)
        return !linkFailed
    }

    Text(stringResource(R.string.update_installed_version, BuildConfig.VERSION_NAME),
        style = MaterialTheme.typography.titleSmall)
    Text(stringResource(R.string.about_description), style = MaterialTheme.typography.bodyMedium)
    OutlinedButton(onClick = onCheck, enabled = enabled && !state.checking, modifier = Modifier.fillMaxWidth()) {
        if (state.checking) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
        }
        Text(stringResource(if (state.checking) R.string.update_checking else R.string.update_check))
    }
    val result = state.manualResult
    if (result != null && !state.checking) {
        val message = when (result) {
            is UpdateCheckResult.Available -> stringResource(R.string.update_found, result.release.version)
            UpdateCheckResult.UpToDate -> stringResource(R.string.update_current)
            UpdateCheckResult.Unavailable -> stringResource(R.string.update_unavailable)
            UpdateCheckResult.NoValidRelease -> stringResource(R.string.update_no_valid_release)
        }
        Text(message, Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            style = MaterialTheme.typography.bodyMedium)
        if (result is UpdateCheckResult.Available) {
            TextButton(onClick = {
                if (open(result.release.apkUrl)) updates.downloaded(result.release)
            }, enabled = enabled) { Text(stringResource(R.string.update_download)) }
        }
    }
    TextButton(onClick = { open(GitHubReleaseSource.REPOSITORY_URL) }, enabled = enabled) {
        Text(stringResource(R.string.update_view_repository))
    }
    TextButton(onClick = { open(GitHubReleaseSource.LATEST_RELEASE_URL) }, enabled = enabled) {
        Text(stringResource(R.string.update_view_release))
    }
    if (linkFailed) {
        Text(stringResource(R.string.update_open_link_failed),
            Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            color = MaterialTheme.colorScheme.error)
    }
}
