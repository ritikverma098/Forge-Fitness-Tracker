package com.forge.workout

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner

class ForgeTestRunner : AndroidJUnitRunner() {
    override fun newApplication(loader: ClassLoader, className: String, context: Context): Application =
        super.newApplication(loader, ForgeTestApplication::class.java.name, context)
}
