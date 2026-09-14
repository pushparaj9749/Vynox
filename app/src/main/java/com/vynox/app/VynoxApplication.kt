package com.vynox.app

import android.app.Application
import android.os.StrictMode
import com.vynox.core.effects.BuiltInEffects
import com.vynox.core.effects.EffectRegistry

/**
 * Application entry point.
 *
 * Registers the effect catalogue and the engine services. Everything here is
 * local: Vynox starts fully functional with no network access.
 */
class VynoxApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            StrictMode.setVmPolicy(
                StrictMode.VmPolicy.Builder()
                    .detectLeakedClosableObjects()
                    .penaltyLog()
                    .build()
            )
        }
        EffectRegistry.registerAll(BuiltInEffects.definitions())
    }
}
