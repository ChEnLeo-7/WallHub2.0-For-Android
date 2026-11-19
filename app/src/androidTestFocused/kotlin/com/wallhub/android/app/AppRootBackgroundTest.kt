package com.wallhub.android

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wallhub.android.core.designsystem.WallHubAppSurface
import com.wallhub.android.core.designsystem.WallHubTheme
import com.wallhub.android.core.designsystem.wallHubStyle
import com.wallhub.android.core.model.AccentPreference
import com.wallhub.android.core.model.SteamSessionPhase
import com.wallhub.android.core.model.SteamSessionState
import com.wallhub.android.core.model.ThemePreference
import com.wallhub.android.core.model.VisualStyle
import com.wallhub.android.testhost.WallHubTestActivity
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppRootBackgroundTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<WallHubTestActivity>()

    @Test
    fun visibleProgressBannerSitsOnTheSemanticPageBackground() {
        var pageColor = 0
        composeRule.setContent {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                WallHubTheme(
                    preference = ThemePreference.LIGHT,
                    accent = AccentPreference.MONET,
                    useSystemMonet = false,
                    visualStyle = VisualStyle.MATERIAL3_EXPRESSIVE,
                ) {
                    pageColor = wallHubStyle().surfaces.page.toArgb()
                    WallHubAppSurface(modifier = Modifier.testTag("app-root")) {
                        SteamSessionRestoreBanner(
                            session =
                                SteamSessionState(
                                    phase = SteamSessionPhase.WAITING_FOR_DEVICE_CONFIRMATION,
                                    hasStoredSession = true,
                                    message = "Signing in",
                                ),
                            onRetry = {},
                            onOpenSignIn = {},
                            modifier = Modifier.testTag("steam-banner"),
                        )
                    }
                }
            }
        }

        composeRule.onNodeWithText("Signing in").assertIsDisplayed()
        composeRule.onNodeWithTag("steam-banner").assertIsDisplayed()
        val image = composeRule.onNodeWithTag("app-root").captureToImage().toPixelMap()
        assertEquals(pageColor, image[image.width / 2, 1].toArgb())
    }

    @Test
    fun hiddenBannerLeavesNoReservedTopSlot() {
        var pageColor = 0
        composeRule.setContent {
            WallHubTheme(
                preference = ThemePreference.LIGHT,
                accent = AccentPreference.MONET,
                useSystemMonet = false,
                visualStyle = VisualStyle.MATERIAL3_EXPRESSIVE,
            ) {
                pageColor = wallHubStyle().surfaces.page.toArgb()
                WallHubAppSurface(modifier = Modifier.testTag("app-root")) {
                    SteamSessionRestoreBanner(
                        session =
                            SteamSessionState(
                                phase = SteamSessionPhase.SIGNED_IN,
                                hasStoredSession = true,
                                message = "Signing in",
                            ),
                        onRetry = {},
                        onOpenSignIn = {},
                        modifier = Modifier.testTag("steam-banner"),
                    )
                }
            }
        }

        composeRule.onNodeWithTag("steam-banner").assertDoesNotExist()
        composeRule.onNodeWithText("Signing in").assertDoesNotExist()
        val image = composeRule.onNodeWithTag("app-root").captureToImage().toPixelMap()
        assertEquals(pageColor, image[image.width / 2, 1].toArgb())
    }
}

