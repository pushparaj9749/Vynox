package com.vynox.app.ui.navigation

import com.vynox.core.model.VynoxProject
import com.vynox.core.vnx.VnxWarning
import java.io.File

/** Navigation destinations. */
sealed class Screen(val route: String) {
    object Splash : Screen("splash")
    object Home : Screen("home")
    object Projects : Screen("projects")
    object NewProject : Screen("new_project")
    object Import : Screen("import")
    object Editor : Screen("editor")
    object Export : Screen("export")
    object Settings : Screen("settings")
    object About : Screen("about")
}

/**
 * In-memory document holder.
 *
 * The project being edited lives here for the lifetime of the process so
 * screens can navigate without serializing the document on every hop. All
 * persistence goes through ProjectStore (.vnx files + autosave).
 */
object VynoxSession {
    var project: VynoxProject? = null
    var file: File? = null
    var warnings: List<VnxWarning> = emptyList()
    var missingAssets: List<com.vynox.core.model.Asset> = emptyList()
    var pendingRecovery: VynoxProject? = null

    fun open(project: VynoxProject, file: File? = null, warnings: List<VnxWarning> = emptyList()) {
        this.project = project
        this.file = file
        this.warnings = warnings
        this.missingAssets = emptyList()
    }

    fun clear() {
        project = null
        file = null
        warnings = emptyList()
        missingAssets = emptyList()
        pendingRecovery = null
    }
}
