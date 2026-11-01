package com.forge.workout

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import com.forge.workout.backup.RestoreStartupState
import com.forge.workout.ui.navigation.ForgeApp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MainActivity: ComponentActivity() {
    private var foregroundObservation: Job? = null
    private var foreground = false

    override fun onResume() {
        super.onResume()
        val forge = application as ForgeApplication
        foregroundObservation = lifecycleScope.launch {
            forge.startupState.collect { state ->
                if (state == RestoreStartupState.READY) {
                    forge.restTimers.setForeground(true)
                    foreground = true
                } else if (foreground) {
                    forge.restTimers.setForeground(false)
                    foreground = false
                }
            }
        }
    }

    override fun onPause() {
        foregroundObservation?.cancel()
        foregroundObservation = null
        if (foreground) {
            (application as ForgeApplication).restTimers.setForeground(false)
            foreground = false
        }
        super.onPause()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { ForgeApp(application as ForgeApplication) }
    }
}
