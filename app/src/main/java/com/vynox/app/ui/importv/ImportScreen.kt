package com.vynox.app.ui.importv

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vynox.app.VynoxServices
import com.vynox.app.ui.common.EmptyState
import com.vynox.app.ui.common.VynoxPrimaryButton
import com.vynox.app.ui.common.VynoxSecondaryButton
import com.vynox.app.ui.common.VynoxTopBar
import com.vynox.app.ui.theme.VynoxColors
import com.vynox.core.model.Asset
import com.vynox.core.model.VynoxProject
import com.vynox.core.vnx.AssetResolution
import com.vynox.core.vnx.VnxWarning
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.rememberCoroutineScope

/**
 * Import / open a .vnx project.
 *
 * Missing media is surfaced explicitly with a relink action - Vynox never
 * silently drops assets, because a project that opens half loaded is worse than
 * one that tells you what it needs.
 */
@Composable
fun ImportScreen(
    onBack: () -> Unit,
    onOpen: (VynoxProject) -> Unit,
    initialUri: Uri? = null
) {
    var project by remember { mutableStateOf<VynoxProject?>(null) }
    var warnings by remember { mutableStateOf<List<VnxWarning>>(emptyList()) }
    var missing by remember { mutableStateOf<List<Asset>>(emptyList()) }
    var fileName by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var relinkingId by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun apply(uri: Uri) = load(
        uri,
        onResult = { result ->
            project = result.first
            warnings = result.second
            fileName = result.third
            missing = emptyList()
            error = null
            evaluateMissing(result.first) { missing = it }
        },
        onError = { message ->
            error = message
            project = null
        }
    )

    val pickLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) apply(uri)
    }

    LaunchedEffect(initialUri) {
        if (initialUri != null) apply(initialUri)
    }

    Column(modifier = Modifier.fillMaxSize().background(VynoxColors.Ink)) {
        VynoxTopBar(title = "Import project", onBack = onBack)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp)
        ) {
            if (project == null && error == null) {
                EmptyState(
                    title = "Choose a .vnx file",
                    message = "Open a project you created, or one shared from another Vynox installation. Bundled .vnx files carry their media with them."
                )
                Spacer(Modifier.height(16.dp))
                VynoxPrimaryButton(
                    text = "Select .vnx file",
                    onClick = { pickLauncher.launch("*/*") },
                    modifier = Modifier.fillMaxWidth(),
                    icon = Icons.Rounded.UploadFile
                )
            }

            error?.let {
                Surface(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)),
                    color = VynoxColors.Rose.copy(alpha = 0.12f)
                ) {
                    Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
                        Icon(Icons.Rounded.Warning, contentDescription = null, tint = VynoxColors.Rose)
                        Spacer(Modifier.width(10.dp))
                        Text(it, color = VynoxColors.TextPrimary, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Spacer(Modifier.height(16.dp))
                VynoxSecondaryButton(text = "Choose another file", onClick = { pickLauncher.launch("*/*") }, modifier = Modifier.fillMaxWidth())
            }

            project?.let { loaded ->
                val current = loaded
                Surface(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)),
                    color = VynoxColors.Surface
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = VynoxColors.Emerald)
                            Spacer(Modifier.width(10.dp))
                            Text("Project loaded", color = VynoxColors.TextPrimary, fontWeight = FontWeight.SemiBold)
                        }
                        Spacer(Modifier.height(12.dp))
                        MetaRow("Name", current.name)
                        MetaRow("Canvas", "${current.canvas.width}×${current.canvas.height} @ ${current.canvas.fps}fps")
                        MetaRow("Duration", "%.1fs".format(current.duration))
                        MetaRow("Layers", current.layers.size.toString())
                        MetaRow("Assets", current.assets.size.toString())
                        MetaRow("Schema", "v${current.schemaVersion} · ${current.appVersion}")
                        fileName?.let { MetaRow("File", it) }
                    }
                }

                if (warnings.isNotEmpty()) {
                    Spacer(Modifier.height(16.dp))
                    Text("Warnings", style = MaterialTheme.typography.titleSmall, color = VynoxColors.Amber)
                    warnings.forEach { warning ->
                        Text(
                            "• ${warning.message}",
                            style = MaterialTheme.typography.bodySmall,
                            color = VynoxColors.TextMuted,
                            modifier = Modifier.padding(vertical = 2.dp)
                        )
                    }
                }

                if (missing.isNotEmpty()) {
                    Spacer(Modifier.height(20.dp))
                    Surface(
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)),
                        color = VynoxColors.Amber.copy(alpha = 0.1f)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.Warning, contentDescription = null, tint = VynoxColors.Amber)
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    "${missing.size} missing asset(s)",
                                    color = VynoxColors.TextPrimary,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            Spacer(Modifier.height(10.dp))
                            missing.forEach { asset ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(asset.name, color = VynoxColors.TextPrimary, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                                        Text(asset.id, color = VynoxColors.TextMuted, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                                    }
                                    RelinkButton(
                                        onPicked = { uri ->
                                            scope.launch {
                                                val updated = withContext(Dispatchers.IO) {
                                                    VynoxServices.projectStore.relinkAsset(current, asset.id, uri)
                                                }
                                                project = updated
                                                evaluateMissing(updated) { missing = it }
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(24.dp))
                VynoxPrimaryButton(
                    text = "Open in editor",
                    onClick = { onOpen(project!!) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = missing.isEmpty()
                )
                if (missing.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Relink every missing asset to continue editing.",
                        style = MaterialTheme.typography.labelSmall,
                        color = VynoxColors.TextMuted
                    )
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun MetaRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, color = VynoxColors.TextMuted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        Text(value, color = VynoxColors.TextPrimary, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun RelinkButton(onPicked: (Uri) -> Unit) {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) onPicked(uri)
    }
    VynoxSecondaryButton(
        text = "Relink",
        onClick = { launcher.launch("*/*") },
        icon = Icons.Rounded.Link
    )
}

private fun load(
    uri: Uri,
    onResult: (Triple<VynoxProject, List<VnxWarning>, String>) -> Unit,
    onError: (String) -> Unit
) {
    try {
        val result = VynoxServices.projectStore.loadFromUri(uri)
        val name = uri.lastPathSegment ?: "project.vnx"
        onResult(Triple(result.project, result.warnings, name))
    } catch (e: Exception) {
        onError(e.message ?: "This file could not be opened as a Vynox project")
    }
}

private fun evaluateMissing(project: VynoxProject, onResult: (List<Asset>) -> Unit) {
    onResult(
        AssetResolution.missing(project) { asset ->
            VynoxServices.assetStore.exists(asset)
        }
    )
}
