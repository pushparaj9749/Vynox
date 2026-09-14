package com.vynox.app.ui.editor

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Audiotrack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vynox.app.ui.common.EmptyState
import com.vynox.app.ui.theme.VynoxColors
import com.vynox.core.model.LayerType
import com.vynox.core.model.VynoxProject
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun LayerListPanel(
    viewModel: EditorViewModel,
    modifier: Modifier = Modifier
) {
    val project by viewModel.project.collectAsStateWithLifecycle()
    val selection by viewModel.selection.collectAsStateWithLifecycle()

    Column(modifier = modifier.fillMaxSize().background(VynoxColors.Surface)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                "Layers",
                style = MaterialTheme.typography.titleSmall,
                color = VynoxColors.TextPrimary,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                "${project.layers.size}",
                style = MaterialTheme.typography.labelSmall,
                color = VynoxColors.TextMuted
            )
        }

        if (project.layers.isEmpty()) {
            EmptyState(
                title = "No layers",
                message = "Add text, shapes or imported media to start building your composition.",
                modifier = Modifier.weight(1f)
            )
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                itemsIndexed(project.layers, key = { _, layer -> layer.id }) { index, layer ->
                    LayerRow(
                        project = project,
                        index = index,
                        layerId = layer.id,
                        selected = layer.id in selection,
                        onSelect = { viewModel.select(layer.id) },
                        onToggleVisibility = { viewModel.toggleVisibility(layer.id) },
                        onToggleLock = { viewModel.toggleLock(layer.id) },
                        onToggleMute = { viewModel.toggleMute(layer.id) },
                        onMoveUp = { viewModel.moveLayerInStack(layer.id, -1) },
                        onMoveDown = { viewModel.moveLayerInStack(layer.id, 1) },
                        isFirst = index == 0,
                        isLast = index == project.layers.lastIndex
                    )
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
private fun LayerRow(
    project: VynoxProject,
    index: Int,
    layerId: String,
    selected: Boolean,
    onSelect: () -> Unit,
    onToggleVisibility: () -> Unit,
    onToggleLock: () -> Unit,
    onToggleMute: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    isFirst: Boolean,
    isLast: Boolean
) {
    val layer = project.layer(layerId) ?: return
    val containerColor = when {
        !layer.enabled -> VynoxColors.Surface.copy(alpha = 0.5f)
        selected -> VynoxColors.Violet.copy(alpha = 0.20f)
        else -> Color.Transparent
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(containerColor)
            .clickable(onClick = onSelect)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = iconFor(layer.type),
            contentDescription = null,
            tint = if (layer.enabled) VynoxColors.Cyan else VynoxColors.TextMuted,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                layer.name,
                color = if (layer.enabled) VynoxColors.TextPrimary else VynoxColors.TextMuted,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                "${layer.type.name.lowercase()} · %.1fs → %.1fs".format(layer.startTime, layer.endTime),
                style = MaterialTheme.typography.labelSmall,
                color = VynoxColors.TextMuted,
                maxLines = 1
            )
        }
        if (layer.type == LayerType.AUDIO || layer.type == LayerType.VIDEO) {
            SmallIconButton(
                imageVector = if (layer.muted) Icons.Rounded.VolumeOff else Icons.Rounded.VolumeUp,
                contentDescription = "Mute",
                tint = if (layer.muted) VynoxColors.Rose else VynoxColors.TextMuted,
                onClick = onToggleMute
            )
        }
        SmallIconButton(
            imageVector = if (layer.locked) Icons.Rounded.Lock else Icons.Rounded.LockOpen,
            contentDescription = "Lock",
            tint = if (layer.locked) VynoxColors.Amber else VynoxColors.TextMuted,
            onClick = onToggleLock
        )
        SmallIconButton(
            imageVector = if (layer.enabled) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff,
            contentDescription = "Visibility",
            tint = if (layer.enabled) VynoxColors.TextSecondary else VynoxColors.TextMuted,
            onClick = onToggleVisibility
        )
        SmallIconButton(
            imageVector = Icons.Rounded.KeyboardArrowUp,
            contentDescription = "Move up",
            tint = if (isFirst) VynoxColors.Outline else VynoxColors.TextMuted,
            enabled = !isFirst,
            onClick = onMoveUp
        )
        SmallIconButton(
            imageVector = Icons.Rounded.KeyboardArrowDown,
            contentDescription = "Move down",
            tint = if (isLast) VynoxColors.Outline else VynoxColors.TextMuted,
            enabled = !isLast,
            onClick = onMoveDown
        )
    }
}

@Composable
private fun SmallIconButton(
    imageVector: ImageVector,
    contentDescription: String,
    tint: Color,
    onClick: () -> Unit,
    enabled: Boolean = true
) {
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(imageVector, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(16.dp))
    }
}

private fun iconFor(type: LayerType): ImageVector = when (type) {
    LayerType.VIDEO -> Icons.Rounded.Movie
    LayerType.IMAGE -> Icons.Rounded.Image
    LayerType.AUDIO -> Icons.Rounded.Audiotrack
    LayerType.TEXT -> Icons.Rounded.AutoAwesome
    LayerType.SHAPE -> Icons.Rounded.Layers
    LayerType.GROUP -> Icons.Rounded.Layers
}
