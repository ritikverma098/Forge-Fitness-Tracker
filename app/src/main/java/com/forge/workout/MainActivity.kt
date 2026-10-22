package com.forge.workout

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.forge.workout.ui.navigation.ForgeApp

class MainActivity: ComponentActivity() {
    override fun onResume() {
        super.onResume()
        (application as ForgeApplication).restTimers.setForeground(true)
    }

    override fun onPause() {
        (application as ForgeApplication).restTimers.setForeground(false)
        super.onPause()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { ForgeApp(application as ForgeApplication) }
    }
}
