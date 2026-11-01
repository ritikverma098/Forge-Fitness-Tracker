package com.forge.workout.ui.screens.workout

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable

/** Same-writer drafts survive navigation; a new writer supplies acknowledged/recovered values. */
@Composable
internal fun rememberWorkoutEditorText(id: Long, initial: String, owner: String, onStateReset: () -> Unit): MutableState<String> {
    val restoration = remember(id, owner) { TextRestoration() }
    val saver = remember(id, owner) {
        Saver<MutableState<String>, Any>(
            save = { arrayListOf(owner, id, it.value) },
            restore = { saved ->
                val payload = if (saved is State<*>) saved.value else saved
                val fields = payload as? List<*>
                val text = fields?.getOrNull(2) as? String
                if (fields?.getOrNull(0) == owner && fields.getOrNull(1) == id && text != null) mutableStateOf(text)
                else { restoration.rejected = true; null }
            },
        )
    }
    val value = rememberSaveable(id, owner, saver = saver) { mutableStateOf(initial) }
    LaunchedEffect(restoration.rejected) { if (restoration.rejected) onStateReset() }
    return value
}

private class TextRestoration(var rejected: Boolean = false)
