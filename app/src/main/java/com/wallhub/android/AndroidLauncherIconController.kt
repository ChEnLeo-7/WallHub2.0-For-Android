package com.wallhub.android

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.wallhub.android.core.model.LauncherIconController
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

private const val THEMED_ICON_ALIAS = "MainActivityThemedIcon"
private const val COLOR_ICON_ALIAS = "MainActivityColorIcon"

internal data class LauncherIconSelection(
    val enabledAlias: String,
    val disabledAlias: String,
)

internal fun launcherIconSelection(themedIconEnabled: Boolean): LauncherIconSelection =
    if (themedIconEnabled) {
        LauncherIconSelection(
            enabledAlias = THEMED_ICON_ALIAS,
            disabledAlias = COLOR_ICON_ALIAS,
        )
    } else {
        LauncherIconSelection(
            enabledAlias = COLOR_ICON_ALIAS,
            disabledAlias = THEMED_ICON_ALIAS,
        )
    }

@Singleton
class AndroidLauncherIconController
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : LauncherIconController {
        private val packageManager = context.packageManager

        override fun setThemedIconEnabled(enabled: Boolean) {
            val selection = launcherIconSelection(enabled)
            val enabledComponent = selection.enabledAlias.toComponentName()
            val disabledComponent = selection.disabledAlias.toComponentName()
            if (
                packageManager.isEnabled(enabledComponent, defaultEnabled = enabled) &&
                !packageManager.isEnabled(disabledComponent, defaultEnabled = !enabled)
            ) {
                return
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.setComponentEnabledSettings(
                    listOf(
                        PackageManager.ComponentEnabledSetting(
                            enabledComponent,
                            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                            PackageManager.DONT_KILL_APP,
                        ),
                        PackageManager.ComponentEnabledSetting(
                            disabledComponent,
                            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                            PackageManager.DONT_KILL_APP,
                        ),
                    ),
                )
            } else {
                // Enable first so launchers never observe a package with no launcher entry.
                packageManager.setComponentEnabledSetting(
                    enabledComponent,
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP,
                )
                packageManager.setComponentEnabledSetting(
                    disabledComponent,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP,
                )
            }
        }

        private fun String.toComponentName(): ComponentName = ComponentName(context, "${context.packageName}.$this")

        private fun PackageManager.isEnabled(
            componentName: ComponentName,
            defaultEnabled: Boolean,
        ): Boolean =
            when (getComponentEnabledSetting(componentName)) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
                PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> defaultEnabled
                else -> false
            }
    }
