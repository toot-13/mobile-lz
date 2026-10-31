package com.dylr.recorder

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        com.dylr.recorder.core.Locator.init(this)
    }
}
