package com.wallhub.android.feature.profile

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.Paparazzi
import com.wallhub.android.core.designsystem.WallHubTheme
import com.wallhub.android.core.model.SteamSessionPhase
import com.wallhub.android.core.model.SteamSessionState
import com.wallhub.android.core.model.ThemePreference
import com.wallhub.android.core.model.VisualStyle
import com.wallhub.android.testing.PadLandscapeAdaptiveContent
import com.wallhub.android.testing.PadLandscapeDevice
import com.wallhub.android.testing.PadTopLevelPreview
import org.junit.Rule
import org.junit.Test

class ProfileTabletScreenshotTest {
    @get:Rule
    val paparazzi =
        Paparazzi(
            deviceConfig = PadLandscapeDevice,
            showSystemUi = false,
            useDeviceResolution = true,
        )

    @Test
    fun expandedTwoPaneDark() {
        paparazzi.snapshot(name = "wallhub-profile-tablet") {
            WallHubTheme(
                preference = ThemePreference.DARK,
                useSystemMonet = false,
                visualStyle = VisualStyle.MATERIAL3_EXPRESSIVE,
            ) {
                PadLandscapeAdaptiveContent {
                    PadTopLevelPreview(selectedLabel = "我的") {
                        Surface(modifier = Modifier.fillMaxSize()) {
                            Column {
                                Text(
                                    text = "我的",
                                    style = MaterialTheme.typography.headlineMedium,
                                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                                )
                                BettboxProfileContent(
                                    session =
                                        SteamSessionState(
                                            phase = SteamSessionPhase.SIGNED_IN,
                                            accountName = "wallhub_pad",
                                            personaName = "Pad 用户",
                                            isConnected = true,
                                        ),
                                    runtimeHours = 386,
                                    counts =
                                        ProfileCounts(
                                            subscriptions = ProfileCountState.Loaded(128),
                                            favorites = ProfileCountState.Loaded(46),
                                        ),
                                    isSignedIn = true,
                                    enabled = true,
                                    onOpenSettings = {},
                                    onOpenSubscriptions = {},
                                    onOpenFavorites = {},
                                    onOpenLocal = {},
                                    onOpenLogin = {},
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

