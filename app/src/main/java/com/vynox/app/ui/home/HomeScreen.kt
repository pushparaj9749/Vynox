package com.vynox.app.ui.home

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Settings
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vynox.app.VynoxServices
import com.vynox.app.data.ProjectSummary
import com.vynox.app.data.UpdateInfo
import com.vynox.app.ui.common.VynoxGradient
import com.vynox.app.ui.theme.VynoxColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun HomeScreen(
    onNewProject: () -> Unit,
    onOpenProjects: () -> Unit,
    onImport: () -> Unit,
    onSettings: () -> Unit,
    onAbout: () -> Unit,
    onOpenProject: (ProjectSummary) -> Unit,
    onRecover: () -> Unit,
    versionName: String
) {
    var projects by remember { mutableStateOf<List<ProjectSummary>>(emptyList()) }
    var recovery by remember { mutableStateOf<Pair<String, Long>?>(null) }
    var update by remember { mutableStateOf<UpdateInfo?>(null) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            projects = VynoxServices.projectStore.list().take(4)
            recovery = VynoxServices.projectStore.autosaveInfo()
            update = VynoxServices.updateRepository.fetchLatest(versionName)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(VynoxColors.Ink)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
    ) {
        Spacer(Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    "Vynox",
                    style = MaterialTheme.typography.titleLarge.copy(
                        brush = VynoxGradient,
                        fontWeight = FontWeight.ExtraBold
                    )
                )
                Text(
                    "Offline video & motion editor",
                    style = MaterialTheme.typography.bodySmall,
                    color = VynoxColors.TextMuted
                )
            }
            Row {
                IconButton(onClick = onSettings) {
                    Icon(Icons.Rounded.Settings, contentDescription = "Settings", tint = VynoxColors.TextSecondary)
                }
                IconButton(onClick = onAbout) {
                    Icon(Icons.Rounded.Info, contentDescription = "About", tint = VynoxColors.TextSecondary)
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        if (recovery != null) {
            RecoveryCard(name = recovery!!.first) { onRecover() }
            Spacer(Modifier.height(14.dp))
        }

        if (update != null) {
            UpdateCard(update!!)
            Spacer(Modifier.height(14.dp))
        }

        // Primary action
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(132.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(
                    Brush.linearGradient(
                        listOf(VynoxColors.Violet, VynoxColors.Violet.copy(alpha = 0.75f), VynoxColors.Cyan.copy(alpha = 0.65f))
                    )
                )
                .clickable(onClick = onNewProject)
                .padding(20.dp)
        ) {
            Column(modifier = Modifier.align(Alignment.CenterStart)) {
                Icon(Icons.Rounded.Add, contentDescription = null, tint = androidx.compose.ui.graphics.Color.White, modifier = Modifier.size(26.dp))
                Spacer(Modifier.height(10.dp))
                Text(
                    "New project",
                    style = MaterialTheme.typography.titleMedium,
                    color = androidx.compose.ui.graphics.Color.White,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "Timeline, keyframes, effects, export",
                    style = MaterialTheme.typography.bodySmall,
                    color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.8f)
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        Row(modifier = Modifier.fillMaxWidth()) {
            HomeActionCard(
                modifier = Modifier.weight(1f),
                icon = Icons.Rounded.FolderOpen,
                title = "Projects",
                subtitle = "Open .vnx",
                onClick = onOpenProjects
            )
            Spacer(Modifier.width(14.dp))
            HomeActionCard(
                modifier = Modifier.weight(1f),
                icon = Icons.Rounded.UploadFile,
                title = "Import",
                subtitle = "Media or .vnx",
                onClick = onImport
            )
        }

        Spacer(Modifier.height(24.dp))

        if (projects.isNotEmpty()) {
            Text(
                "Recent",
                style = MaterialTheme.typography.titleSmall,
                color = VynoxColors.TextPrimary,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(10.dp))
            projects.forEach { summary ->
                RecentProjectRow(summary) { onOpenProject(summary) }
                Spacer(Modifier.height(10.dp))
            }
        }

        Spacer(Modifier.height(24.dp))
        Text(
            "Vynox v$versionName · works fully offline",
            style = MaterialTheme.typography.labelSmall,
            color = VynoxColors.TextMuted,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun HomeActionCard(
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier
            .height(96.dp)
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick),
        color = VynoxColors.Surface
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Icon(icon, contentDescription = null, tint = VynoxColors.Cyan)
            Column {
                Text(title, style = MaterialTheme.typography.bodyLarge, color = VynoxColors.TextPrimary, fontWeight = FontWeight.SemiBold)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = VynoxColors.TextMuted)
            }
        }
    }
}

@Composable
private fun RecentProjectRow(summary: ProjectSummary, onClick: () -> Unit) {
    var bitmap by remember(summary.id) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(summary.id) {
        withContext(Dispatchers.IO) {
            bitmap = summary.thumbnailPath?.let { VynoxServices.projectStore.loadThumbnail(summary.id) }
        }
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
        color = VynoxColors.Surface
    ) {
        Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(62.dp, 36.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(VynoxColors.SurfaceHighest)
            ) {
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap!!.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(summary.name, color = VynoxColors.TextPrimary, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                Text(
                    "${summary.width}×${summary.height} · ${summary.fps}fps · ${summary.layerCount} layers",
                    color = VynoxColors.TextMuted,
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}

@Composable
private fun RecoveryCard(name: String, onRecover: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .border(1.dp, VynoxColors.Amber.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
            .clickable(onClick = onRecover),
        color = VynoxColors.Amber.copy(alpha = 0.1f)
    ) {
        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Info, contentDescription = null, tint = VynoxColors.Amber)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("Recover autosave", color = VynoxColors.TextPrimary, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text("\"$name\" was autosaved locally", color = VynoxColors.TextMuted, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun UpdateCard(info: UpdateInfo) {
    Surface(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)),
        color = VynoxColors.Cyan.copy(alpha = 0.1f)
    ) {
        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Info, contentDescription = null, tint = VynoxColors.Cyan)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("Vynox ${info.version} available", color = VynoxColors.TextPrimary, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text("Download from the Vynox website", color = VynoxColors.TextMuted, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
