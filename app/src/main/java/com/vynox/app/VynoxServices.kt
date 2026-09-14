package com.vynox.app

import android.content.Context
import com.vynox.app.data.AssetStore
import com.vynox.app.data.MediaProbe
import com.vynox.app.data.ProjectStore
import com.vynox.app.data.SettingsStore
import com.vynox.app.data.UpdateRepository

/**
 * Minimal service locator for the app's local services.
 *
 * Everything here is device local: no network dependency is required for
 * editing, saving, rendering or export.
 */
object VynoxServices {
    lateinit var context: Context
        private set

    fun init(context: Context) {
        this.context = context.applicationContext
    }

    val projectStore: ProjectStore by lazy { ProjectStore(context) }
    val assetStore: AssetStore by lazy { AssetStore(context) }
    val mediaProbe: MediaProbe by lazy { MediaProbe(context) }
    val settingsStore: SettingsStore by lazy { SettingsStore(context) }
    val updateRepository: UpdateRepository by lazy { UpdateRepository() }
}
