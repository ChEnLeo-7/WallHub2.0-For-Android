package com.wallhub.android

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wallhub.android.core.designsystem.WallHubTheme
import com.wallhub.android.core.model.SteamSessionState
import com.wallhub.android.core.model.ThemePreference
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        setContent {
            val viewModel: ShellViewModel = hiltViewModel()
            val preferences by viewModel.preferences.collectAsStateWithLifecycle()
            val steamSession by viewModel.steamSession.collectAsStateWithLifecycle()
            val loadedPreferences = preferences
            if (loadedPreferences == null) {
                StartupLoadingState(steamSession)
            } else {
                FormalWallHubApp(
                    preferences = loadedPreferences,
                    steamSession = steamSession,
                    onSetupWizardCompleted = { viewModel.setSetupWizardCompleted(true) },
                    onRetrySteamRestore = viewModel::restoreSteamSession,
                )
            }
        }
    }
}

@Composable
private fun StartupLoadingState(session: SteamSessionState) {
    WallHubTheme(preference = ThemePreference.SYSTEM) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    CircularProgressIndicator()
                    Text(
                        text = session.message ?: stringResource(R.string.app_starting),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}
