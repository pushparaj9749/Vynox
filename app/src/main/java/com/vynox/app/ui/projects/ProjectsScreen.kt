package com.vynox.app.ui.projects

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.vynox.app.VynoxServices
import com.vynox.app.data.ProjectSummary
import com.vynox.app.ui.common.EmptyState
import com.vynox.app.ui.common.VynoxTopBar
import com.vynox.app.ui.theme.VynoxColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.rememberCoroutineScope

@Composable
fun ProjectsScreen(
    onBack: () -> Unit,
    onOpenProject: (ProjectSummary) -> Unit,
    onImportVnx: (android.net.Uri) -> Unit
) {
    var projects by remember { mutableStateOf<List<ProjectSummary>>(emptyList()) }
    val scope = rememberCoroutineScope()

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) onImportVnx(uri)
    }

    suspend fun reload() {
        projects = withContext(Dispatchers.IO) { VynoxServices.projectStore.list() }
    }

    LaunchedEffect(Unit) { reload() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(VynoxColors.Ink)
    ) {
        VynoxTopBar(
            title = "Projects",
            onBack = onBack,
            actions = {
                IconButton(onClick = { importLauncher.launch("*/*") }) {
                    Icon(Icons.Rounded.UploadFile, contentDescription = "Import .vnx", tint = VynoxColors.TextPrimary)
                }
            }
        )

        if (projects.isEmpty()) {
            EmptyState(
                title = "No projects yet",
                message = "Create a project or import a .vnx file shared from another Vynox install.",
                modifier = Modifier.weight(1f)
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(projects, key = { it.id }) { summary ->
                    ProjectRow(
                        summary = summary,
                        onOpen = { onOpenProject(summary) },
                        onDelete = {
                            scope.launch {
                                withContext(Dispatchers.IO) { VynoxServices.projectStore.delete(summary) }
                                reload()
                            }
                        }
                    )
                }
                item { Spacer(Modifier.height(32.dp)) }
            }
        }
    }
}

@Composable
private fun ProjectRow(
    summary: ProjectSummary,
    onOpen: () -> Unit,
    onDelete: () -> Unit
) {
    var bitmap by remember(summary.id) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(summary.id) {
        bitmap = withContext(Dispatchers.IO) { VynoxServices.projectStore.loadThumbnail(summary.id) }
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onOpen),
        color = VynoxColors.Surface
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(84.dp, 48.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(VynoxColors.SurfaceHighest)
            ) {
                if (bitmap != null) {
                    Image(bitmap!!.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize())
                } else {
                    Text(
                        "${summary.width}×${summary.height}",
                        modifier = Modifier.align(Alignment.Center),
                        style = MaterialTheme.typography.labelSmall,
                        color = VynoxColors.TextMuted,
                        textAlign = TextAlign.Center
                    )
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    summary.name,
                    color = VynoxColors.TextPrimary,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
                Text(
                    "${summary.width}×${summary.height} · ${summary.fps} fps · ${"%.1f".format(summary.duration)}s · ${summary.layerCount} layers",
                    color = VynoxColors.TextMuted,
                    style = MaterialTheme.typography.labelSmall
                )
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Rounded.DeleteOutline, contentDescription = "Delete", tint = VynoxColors.TextMuted)
            }
        }
    }
}
