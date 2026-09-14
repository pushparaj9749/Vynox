package com.vynox.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vynox.app.data.ProjectSummary
import com.vynox.app.ui.about.AboutScreen
import com.vynox.app.ui.editor.EditorScreen
import com.vynox.app.ui.editor.EditorViewModel
import com.vynox.app.ui.export.ExportScreen
import com.vynox.app.ui.home.HomeScreen
import com.vynox.app.ui.importv.ImportScreen
import com.vynox.app.ui.navigation.NavHost
import com.vynox.app.ui.navigation.Navigator
import com.vynox.app.ui.navigation.Screen
import com.vynox.app.ui.navigation.VynoxSession
import com.vynox.app.ui.newproject.NewProjectScreen
import com.vynox.app.ui.projects.ProjectsScreen
import com.vynox.app.ui.settings.SettingsScreen
import com.vynox.app.ui.splash.SplashScreen
import com.vynox.app.ui.theme.VynoxTheme

/**
 * Root composition: theme plus the screen graph.
 *
 * The editor's [EditorViewModel] is created once for the whole graph, so the
 * document, undo history and playback survive navigation to Export and back.
 * No screen performs network calls; the only optional connectivity is the
 * update check on Home.
 */
@Composable
fun VynoxApp() {
    val navigator = remember { Navigator(Screen.Splash) }
    val editorViewModel: EditorViewModel = viewModel()
    val versionName = BuildConfig.VERSION_NAME

    var pendingImportUri by remember { mutableStateOf<android.net.Uri?>(null) }

    VynoxTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            NavHost(navigator = navigator) { screen ->
                when (screen) {
                    Screen.Splash -> SplashScreen(onFinished = {
                        navigator.navigate(Screen.Home)
                        navigator.popUpTo(Screen.Splash, inclusive = true)
                    })

                    Screen.Home -> HomeScreen(
                        onNewProject = { navigator.navigate(Screen.NewProject) },
                        onOpenProjects = { navigator.navigate(Screen.Projects) },
                        onImport = {
                            pendingImportUri = null
                            navigator.navigate(Screen.Import)
                        },
                        onSettings = { navigator.navigate(Screen.Settings) },
                        onAbout = { navigator.navigate(Screen.About) },
                        onOpenProject = { summary -> openProject(summary, editorViewModel, navigator) },
                        onRecover = {
                            val recovered = VynoxServices.projectStore.loadAutosave()
                            if (recovered != null) {
                                VynoxSession.open(recovered)
                                editorViewModel.load(recovered)
                                navigator.navigate(Screen.Editor)
                            }
                        },
                        versionName = versionName
                    )

                    Screen.Projects -> ProjectsScreen(
                        onBack = { navigator.pop() },
                        onOpenProject = { summary -> openProject(summary, editorViewModel, navigator) },
                        onImportVnx = { uri ->
                            pendingImportUri = uri
                            navigator.navigate(Screen.Import)
                        }
                    )

                    Screen.NewProject -> NewProjectScreen(
                        onBack = { navigator.pop() },
                        onCreate = { project ->
                            VynoxSession.open(project)
                            editorViewModel.load(project)
                            navigator.navigate(Screen.Editor)
                        },
                        versionName = versionName
                    )

                    Screen.Import -> ImportScreen(
                        onBack = { navigator.pop() },
                        onOpen = { project ->
                            VynoxSession.open(project)
                            editorViewModel.load(project)
                            navigator.navigate(Screen.Editor)
                        },
                        initialUri = pendingImportUri
                    )

                    Screen.Editor -> {
                        // A document loaded elsewhere (new / import / recovery) becomes
                        // the one the editor is showing.
                        LaunchedEffect(Unit) {
                            val session = VynoxSession.project
                            if (session != null && session !== editorViewModel.project.value) {
                                editorViewModel.load(session, VynoxSession.file, VynoxSession.warnings)
                            }
                        }
                        DisposableEffect(Unit) {
                            onDispose { editorViewModel.pause() }
                        }
                        EditorScreen(
                            viewModel = editorViewModel,
                            onBack = {
                                editorViewModel.pause()
                                editorViewModel.save()
                                navigator.pop()
                            },
                            onExport = { navigator.navigate(Screen.Export) }
                        )
                    }

                    Screen.Export -> ExportScreen(
                        viewModel = editorViewModel,
                        onBack = { navigator.pop() }
                    )

                    Screen.Settings -> SettingsScreen(
                        onBack = { navigator.pop() },
                        versionName = versionName
                    )

                    Screen.About -> AboutScreen(
                        onBack = { navigator.pop() },
                        versionName = versionName
                    )
                }
            }
        }
    }
}

private fun openProject(
    summary: ProjectSummary,
    editorViewModel: EditorViewModel,
    navigator: Navigator
) {
    val file = java.io.File(summary.path)
    val result = runCatching { VynoxServices.projectStore.load(file) }.getOrNull() ?: return
    VynoxSession.open(result.project, file, result.warnings)
    editorViewModel.load(result.project, file, result.warnings)
    navigator.navigate(Screen.Editor)
}
