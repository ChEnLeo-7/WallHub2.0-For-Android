package com.wallhub.android.feature.settings

import kotlin.test.Test
import kotlin.test.assertEquals

class SettingsEffectTest {
    @Test
    fun `system actions preserve their payload when mapped to effects`() {
        assertEquals(
            SettingsEffect.SelectOutputDirectory,
            SettingsAction.SelectOutputDirectory.toEffect(),
        )
        assertEquals(
            SettingsEffect.ExportDiagnostics,
            SettingsAction.ExportDiagnostics.toEffect(),
        )
        assertEquals(
            SettingsEffect.RequestNotifications,
            SettingsAction.RequestNotifications.toEffect(),
        )
        assertEquals(
            SettingsEffect.OpenSteamLogin,
            SettingsAction.OpenSteamLogin.toEffect(),
        )
        assertEquals(
            SettingsEffect.InstallDownloadedRelease("release.apk"),
            SettingsAction.InstallDownloadedRelease("release.apk").toEffect(),
        )
        assertEquals(
            SettingsEffect.OpenExternalUri("https://example.com", "failed"),
            SettingsAction.OpenExternalUri("https://example.com", "failed").toEffect(),
        )
    }
}
