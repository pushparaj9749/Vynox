package com.vynox.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import com.vynox.app.ui.theme.VynoxTheme

/** Single activity host; every screen is a Compose destination. */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            VynoxTheme {
                VynoxApp()
            }
        }
    }
}
