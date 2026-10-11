package dev.omalauncher

import android.app.Application

interface RuntimeOwner {
    val runtime: Runtime
}

class App : Application(), RuntimeOwner {
    override val runtime by lazy { Runtime(this) }
}
