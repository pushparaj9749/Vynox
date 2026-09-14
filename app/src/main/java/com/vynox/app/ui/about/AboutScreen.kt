package com.vynox.app.ui.about

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vynox.app.ui.common.PanelCard
import com.vynox.app.ui.common.SectionTitle
import com.vynox.app.ui.common.VynoxTopBar
import com.vynox.core.effects.EffectRegistry
import com.vynox.app.ui.theme.VynoxColors

@Composable
fun AboutScreen(onBack: () -> Unit, versionName: String) {
    Column(modifier = Modifier.fillMaxSize().background(VynoxColors.Ink)) {
        VynoxTopBar(title = "About", onBack = onBack)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp)
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)),
                color = VynoxColors.Surface
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        "Vynox",
                        style = MaterialTheme.typography.headlineMedium.copy(
                            brush = com.vynox.app.ui.common.VynoxGradient,
                            fontWeight = FontWeight.ExtraBold
                        )
                    )
                    Text("Version $versionName", color = VynoxColors.TextMuted, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "An offline-first video and motion graphics editor for Android. Multi-layer timeline, " +
                            "keyframe animation with a real easing graph, text, shapes, masks, GPU effects, " +
                            "blending, audio and MP4 export - all on device, no account, no cloud.",
                        color = VynoxColors.TextSecondary,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            Spacer(Modifier.height(18.dp))
            SectionTitle("Engine")
            PanelCard {
                Bullet("${EffectRegistry.all().size} GPU accelerated effects")
                Bullet("Timeline: split, trim, move, snap, reorder, ripple-free edits")
                Bullet("Keyframes: linear, hold, bezier easing with graph editor")
                Bullet("Compositing: 10 blend modes, masks with feather and invert")
                Bullet("Export: MP4 (H.264 + AAC), resolution, fps and bitrate control")
                Bullet("Project format: .vnx, versioned, with optional bundled media")
            }

            Spacer(Modifier.height(18.dp))
            SectionTitle("Offline")
            PanelCard {
                Text(
                    "Every editing feature works without a network connection. The only network call Vynox " +
                        "ever makes is the optional update check on the home screen, which can be disabled in Settings.",
                    color = VynoxColors.TextSecondary,
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            Spacer(Modifier.height(18.dp))
            SectionTitle("Links")
            PanelCard {
                Bullet("Download: pushparaj9749.github.io/Vynox")
                Bullet("Source: github.com/pushparaj9749/Vynox")
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun Bullet(text: String) {
    Text(
        "•  $text",
        color = VynoxColors.TextSecondary,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(vertical = 3.dp)
    )
}
