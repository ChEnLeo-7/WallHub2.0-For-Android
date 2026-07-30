package com.wallhub.android.core.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.ide.common.rendering.api.SessionParams
import com.wallhub.android.core.model.AppLanguage
import com.wallhub.android.core.model.ThemePreference
import org.junit.Rule
import org.junit.Test

class WallHubThemeScreenshotTest {
    @get:Rule
    val paparazzi =
        Paparazzi(
            deviceConfig = DeviceConfig.PIXEL_5,
            renderingMode = SessionParams.RenderingMode.SHRINK,
            showSystemUi = false,
        )

    @Test
    fun lightChineseTheme() {
        snapshotTheme(ThemePreference.LIGHT, AppLanguage.ZH)
    }

    @Test
    fun darkEnglishTheme() {
        snapshotTheme(ThemePreference.DARK, AppLanguage.EN)
    }

    @Test
    fun dynamicChineseTheme() {
        snapshotTheme(
            preference = ThemePreference.LIGHT,
            language = AppLanguage.ZH,
            useSystemMonet = true,
        )
    }

    private fun snapshotTheme(
        preference: ThemePreference,
        language: AppLanguage,
        useSystemMonet: Boolean = false,
    ) {
        paparazzi.snapshot {
            WallHubTheme(
                preference = preference,
                language = language,
                useSystemMonet = useSystemMonet,
            ) {
                WallHubSurfaceCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(WallHubSpacing.md),
                        verticalArrangement = Arrangement.spacedBy(WallHubSpacing.sm),
                    ) {
                        Text(
                            text = language.text("设计系统", "Design system"),
                            style = MaterialTheme.typography.headlineSmall,
                        )
                        Text(
                            text = language.text("主题表面与操作控件", "Theme surfaces and actions"),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        WallHubPrimaryAction(
                            label = language.text("主要操作", "Primary action"),
                            onClick = {},
                        )
                    }
                }
            }
        }
    }
}
