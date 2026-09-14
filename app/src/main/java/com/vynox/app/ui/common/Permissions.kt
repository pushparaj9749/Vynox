package com.vynox.app.ui.common

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/** Media permissions needed to import local files. */
fun mediaPermissions(): List<String> = when {
    Build.VERSION.SDK_INT >= 33 -> listOf(
        Manifest.permission.READ_MEDIA_VIDEO,
        Manifest.permission.READ_MEDIA_IMAGES,
        Manifest.permission.READ_MEDIA_AUDIO
    )
    else -> listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
}

/**
 * Returns a launcher that runs [onGranted] once the required media permission
 * is available. Storage Access Framework pickers (used for .vnx import) do not
 * need this, but direct media queries do.
 */
@Composable
fun rememberMediaPermissionLauncher(onGranted: () -> Unit): () -> Unit {
    var retry by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val granted = results.values.all { it }
        if (granted) onGranted()
    }
    return {
        launcher.launch(mediaPermissions().toTypedArray())
        retry = !retry
    }
}
