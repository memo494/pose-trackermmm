package com.memo494.posetracker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/**
 * M0a placeholder screen: proves the Compose + build pipeline and shows the build identity
 * (version + git SHA) the way the real debug HUD will (ARCHITECTURE §1). Edge-to-edge per §14.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    HudScreen(
                        versionName = BuildConfig.VERSION_NAME,
                        gitSha = BuildConfig.GIT_SHA,
                    )
                }
            }
        }
    }
}

@Composable
fun HudScreen(
    versionName: String,
    gitSha: String,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Pose Tracker", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(8.dp))
            Text(BuildLabel.line(versionName, gitSha), style = MaterialTheme.typography.bodyMedium)
            Text("M0a — bootstrap", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Preview
@Composable
private fun HudScreenPreview() {
    MaterialTheme {
        HudScreen(versionName = "0.1.1", gitSha = "local")
    }
}
