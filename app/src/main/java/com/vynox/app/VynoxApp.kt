package com.vynox.app

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
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
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.vynox.app.data.ProjectSummary
import com.vynox.app.ui.about.AboutScreen
import com.vynox.app.ui.editor.EditorScreen
import com.vynox.app.ui.editor.EditorViewModel
import com.vynox.app.ui.export.ExportScreen
import com.vynox.app.ui.home.HomeScreen
import com.vynox.app.ui.importv.ImportScreen
import com.vynox.app.ui.navigation.Screen
import com.vynox.app.ui.navigation.VynoxSession
import com.vynox.app.ui.newproject.NewProjectScreen
import com.vynox.app.ui.projects.ProjectsScreen
import com.vynox.app.ui.settings.SettingsScreen
import com.vynox.app.ui.splash.SplashScreen
import com.vynox.app.ui.theme.VynoxTheme
import com.vynox.core.model.VynoxProject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Root composition: theme + navigation graph.
 *
 * The editor keeps its [EditorViewModel] alive for the whole graph so the
 * document, undo history and playback state survive navigation to Export and
 * back. No screen performs network calls; the only optional connectivity is the
 * update check on Home.
 */
@Composable
fun VynoxApp() {
    val navController: NavHostController = rememberNavController()
    val editorViewModel: EditorViewModel = viewModel()
    val versionName = BuildConfig.VERSION_NAME

    var pendingImportUri by remember { mutableStateOf<android.net.Uri?>(null) }

    VynoxTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            NavHost(
                navController = navController,
                startDestination = Screen.Splash.route,
                enterTransition = { fadeIn(tween(160)) + slideInHorizontally(tween(220)) { it / 6 } },
                exitTransition = { fadeOut(tween(120)) },
                popEnterTransition = { fadeIn(tween(160)) },
                popExitTransition = { fadeOut(tween(120)) + slideOutHorizontally(tween(220)) { it / 6 } }
            ) {
                composable(Screen.Splash.route) {
                    SplashScreen(onFinished = {
                        navController.navigate(Screen.Home.route) {
                            popUpTo(Screen.Splash.route) { inclusive = true }
                        }
                    })
                }

                composable(Screen.Home.route) {
                    HomeScreen(
                        onNewProject = { navController.navigate(Screen.NewProject.route) },
                        onOpenProjects = { navController.navigate(Screen.Projects.route) },
                        onImport = {
                            pendingImportUri = null
                            navController.navigate(Screen.Import.route)
                        },
                        onSettings = { navController.navigate(Screen.Settings.route) },
                        onAbout = { navController.navigate(Screen.About.route) },
                        onOpenProject = { summary -> openProject(summary, editorViewModel, navController) },
                        onRecover = {
                            val recovered = VynoxServices.projectStore.loadAutosave()
                            if (recovered != null) {
                                VynoxSession.open(recovered)
                                editorViewModel.load(recovered)
                                navController.navigate(Screen.Editor.route)
                            }
                        },
                        versionName = versionName
                    )
                }

                composable(Screen.Projects.route) {
                    ProjectsScreen(
                        onBack = { navController.popBackStack() },
                        onOpenProject = { summary -> openProject(summary, editorViewModel, navController) },
                        onImportVnx = { uri ->
                            pendingImportUri = uri
                            navController.navigate(Screen.Import.route)
                        }
                    )
                }

                composable(Screen.NewProject.route) {
                    NewProjectScreen(
                        onBack = { navController.popBackStack() },
                        onCreate = { project ->
                            VynoxSession.open(project)
                            editorViewModel.load(project)
                            navController.navigate(Screen.Editor.route)
                        },
                        versionName = versionName
                    )
                }

                composable(Screen.Import.route) {
                    ImportScreen(
                        onBack = { navController.popBackStack() },
                        onOpen = { project ->
                            VynoxSession.open(project)
                            editorViewModel.load(project)
                            navController.navigate(Screen.Editor.route)
                        },
                        initialUri = pendingImportUri
                    )
                }

                composable(Screen.Editor.route) {
                    // Make sure the document loaded elsewhere (new / import / recovery)
                    // is the one the editor is showing.
                    LaunchedEffect(Unit) {
                        val session = VynoxSession.project
                        if (session != null && session !== editorViewModel.project.value) {
                            editorViewModel.load(session, VynoxSession.file, VynoxSession.warnings)
                        }
                    }
                    DisposableEffect(Unit) {
                        onDispose {
                            // Leaving the editor never loses work: a snapshot is written.
                            editorViewModel.pause()
                        }
                    }
                    EditorScreen(
                        viewModel = editorViewModel,
                        onBack = {
                            editorViewModel.pause()
                            navController.popBackStack()
                        },
                        onExport = { navController.navigate(Screen.Export.route) }
                    )
                }

                composable(Screen.Export.route) {
                    ExportScreen(
                        viewModel = editorViewModel,
                        onBack = { navController.popBackStack() }
                    )
                }

                composable(Screen.Settings.route) {
                    SettingsScreen(onBack = { navController.popBackStack() }, versionName = versionName)
                }

                composable(Screen.About.route) {
                    AboutScreen(onBack = { navController.popBackStack() }, versionName = versionName)
                }
            }
        }
    }
}

private fun openProject(
    summary: ProjectSummary,
    editorViewModel: EditorViewModel,
    navController: NavHostController
) {
    val file = java.io.File(summary.path)
    val result = runCatching { VynoxServices.projectStore.load(file) }.getOrNull() ?: return
    VynoxSession.open(result.project, file, result.warnings)
    editorViewModel.load(result.project, file, result.warnings)
    navController.navigate(Screen.Editor.route)
}
