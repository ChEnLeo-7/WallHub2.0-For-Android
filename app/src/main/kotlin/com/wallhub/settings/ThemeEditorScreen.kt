@file:Suppress("ktlint:standard:function-naming")
@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.wallhub.settings

import android.graphics.Color as AndroidColor
import android.content.res.Configuration
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Contrast
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuItemColors
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonColors
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.ToggleButtonShapes
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import com.wallhub.android.R
import com.wallhub.android.core.designsystem.LocalWallHubAdaptiveLayout
import com.wallhub.android.core.designsystem.WallHubSpacing
import com.wallhub.android.core.designsystem.WallHubWindowLayout
import com.wallhub.android.core.designsystem.defaultStaticColorScheme
import com.wallhub.android.core.designsystem.staticAccentColorScheme
import com.wallhub.android.core.designsystem.wallHubStyle
import com.wallhub.android.core.model.AccentPreference
import com.wallhub.android.core.model.AppPreferences
import com.wallhub.android.core.model.ThemeContrastLevel
import com.wallhub.android.core.model.ThemePreference
import com.wallhub.android.core.model.ThemeSchemeStyle
import com.wallhub.android.core.model.VisualStyle
import org.uwuaosp.compose.settingslib.PreferenceRow
import org.uwuaosp.compose.settingslib.SettingsPreferenceGroup
import org.uwuaosp.compose.settingslib.SwitchPreferenceRow

private val PRESET_COLORS =
    listOf(
        "#D32F2F" to R.string.theme_editor_color_red,
        "#F57C00" to R.string.theme_editor_color_orange,
        "#FBC02D" to R.string.theme_editor_color_yellow,
        "#388E3C" to R.string.theme_editor_color_green,
        "#00897B" to R.string.theme_editor_color_teal,
        "#2B6CB0" to R.string.theme_editor_color_blue,
        "#7B1FA2" to R.string.theme_editor_color_purple,
        "#C2185B" to R.string.theme_editor_color_pink,
    )

@Composable
internal fun ThemeEditorScreen(
    preferences: AppPreferences,
    customAccentColor: String,
    onBack: () -> Unit,
    onThemePreferenceChange: (ThemePreference) -> Unit,
    onVisualStyleChange: (VisualStyle) -> Unit,
    onCustomAccentColorChanged: (String) -> Unit,
    onAccentChange: (AccentPreference, String?) -> Unit,
    onSystemMonetEnabledChange: (Boolean) -> Unit,
    onThemedLauncherIconEnabledChange: (Boolean) -> Unit,
    onSchemeStyleChange: (ThemeSchemeStyle) -> Unit = {},
    onContrastLevelChange: (ThemeContrastLevel) -> Unit = {},
    onPureBlackInDarkChange: (Boolean) -> Unit = {},
    previewContrastMenuOpen: Boolean = false,
) {
    BackHandler(onBack = onBack)
    val startsDefaultMonochrome =
        !preferences.useSystemMonet && preferences.accent == AccentPreference.DEFAULT
    var themeMode by rememberSaveable { mutableStateOf(preferences.theme) }
    var visualStyle by rememberSaveable { mutableStateOf(preferences.visualStyle) }
    var systemColor by rememberSaveable { mutableStateOf(preferences.useSystemMonet) }
    var themedIcon by rememberSaveable { mutableStateOf(preferences.useThemedLauncherIcon) }
    var seed by rememberSaveable { mutableStateOf(customAccentColor.ifBlank { "#2B6CB0" }) }
    var style by rememberSaveable {
        mutableStateOf(
            if (startsDefaultMonochrome) ThemeSchemeStyle.MONOCHROME else preferences.schemeStyle,
        )
    }
    var contrast by rememberSaveable { mutableStateOf(preferences.contrastLevel) }
    var pureBlack by rememberSaveable { mutableStateOf(preferences.pureBlackInDark) }
    var defaultMonochrome by rememberSaveable { mutableStateOf(startsDefaultMonochrome) }
    var resetDialogVisible by rememberSaveable { mutableStateOf(false) }
    var contrastAnchor by remember { mutableStateOf<Rect?>(null) }

    val dark =
        themeMode == ThemePreference.DARK ||
            (themeMode == ThemePreference.SYSTEM && androidx.compose.foundation.isSystemInDarkTheme())
    val currentScheme = MaterialTheme.colorScheme
    val previewScheme =
        remember(seed, dark, style, contrast, pureBlack, systemColor, defaultMonochrome, currentScheme) {
            when {
                systemColor -> currentScheme
                defaultMonochrome -> defaultStaticColorScheme(dark, contrast, pureBlack)
                else ->
                    buildPreviewScheme(
                        seed = seed,
                        dark = dark,
                        style = style,
                        contrast = contrast,
                        pureBlack = pureBlack,
                    )
            }
        }
    val themedIconsSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    val applySeed: (String) -> Unit = { value ->
        systemColor = false
        defaultMonochrome = false
        seed = value
        onSystemMonetEnabledChange(false)
        onCustomAccentColorChanged(value)
        onAccentChange(AccentPreference.CUSTOM, value)
    }
    val selectDefaultMonochrome: () -> Unit = {
        systemColor = false
        defaultMonochrome = true
        seed = "#5B5B5B"
        style = ThemeSchemeStyle.MONOCHROME
        contrast = ThemeContrastLevel.STANDARD
        onSystemMonetEnabledChange(false)
        onAccentChange(AccentPreference.DEFAULT, null)
        onSchemeStyleChange(ThemeSchemeStyle.MONOCHROME)
        onContrastLevelChange(ThemeContrastLevel.STANDARD)
    }
    val updateStyle: (ThemeSchemeStyle) -> Unit = { newStyle ->
        style = newStyle
        defaultMonochrome = newStyle == ThemeSchemeStyle.MONOCHROME &&
            (preferences.accent == AccentPreference.DEFAULT || preferences.accent == AccentPreference.MONET)
        if (!defaultMonochrome &&
            (preferences.accent == AccentPreference.DEFAULT || preferences.accent == AccentPreference.MONET)
        ) {
            onAccentChange(AccentPreference.CUSTOM, seed)
        }
        onSchemeStyleChange(newStyle)
    }
    val updateContrast: (ThemeContrastLevel) -> Unit = {
        contrast = it
        onContrastLevelChange(it)
    }
    val updatePureBlack: (Boolean) -> Unit = {
        pureBlack = it
        onPureBlackInDarkChange(it)
    }
    val updateSystemColor: (Boolean) -> Unit = { enabled ->
        systemColor = enabled
        if (enabled) {
            defaultMonochrome = false
            onSystemMonetEnabledChange(true)
        } else {
            if (defaultMonochrome ||
                preferences.accent == AccentPreference.MONET ||
                preferences.accent == AccentPreference.DEFAULT
            ) {
                defaultMonochrome = true
                seed = "#5B5B5B"
                style = ThemeSchemeStyle.MONOCHROME
                onAccentChange(AccentPreference.DEFAULT, null)
                onSchemeStyleChange(ThemeSchemeStyle.MONOCHROME)
            }
            onSystemMonetEnabledChange(false)
        }
    }
    val resetColors: () -> Unit = {
        systemColor = false
        defaultMonochrome = true
        seed = "#5B5B5B"
        style = ThemeSchemeStyle.MONOCHROME
        contrast = ThemeContrastLevel.STANDARD
        pureBlack = false
        onSystemMonetEnabledChange(false)
        onAccentChange(AccentPreference.DEFAULT, null)
        onSchemeStyleChange(ThemeSchemeStyle.MONOCHROME)
        onContrastLevelChange(ThemeContrastLevel.STANDARD)
        onPureBlackInDarkChange(false)
        resetDialogVisible = false
    }

    val windowLayout = LocalWallHubAdaptiveLayout.current.width
    val isPad = windowLayout != WallHubWindowLayout.COMPACT
    val isLandscape =
        LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val contentMaxWidth =
        when {
            !isPad -> SETTINGS_CONTENT_MAX_WIDTH
            isLandscape -> 1120.dp
            else -> 680.dp
        }
    Box(Modifier.fillMaxSize()) {
    WallHubSettingsScaffold(
        title = stringResource(R.string.theme_editor_title),
        onNavigateUp = onBack,
        containerColor = wallHubStyle().surfaces.page,
        contentMaxWidth = contentMaxWidth,
        fillViewport = isPad,
        actions = {
            IconButton(onClick = { resetDialogVisible = true }) {
                Icon(
                    imageVector = Icons.Outlined.RestartAlt,
                    contentDescription = stringResource(R.string.settings_action_reset),
                )
            }
        },
    ) {
        when {
            isPad && isLandscape -> {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(28.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        verticalArrangement = Arrangement.Center,
                    ) {
                        PreviewWithTransition(scheme = previewScheme)
                        Spacer(Modifier.height(20.dp))
                        ColorPresetRow(
                            seed = seed,
                            defaultSelected = defaultMonochrome,
                            dynamicColor = systemColor,
                            onSeedSelected = applySeed,
                            onDefaultSelected = selectDefaultMonochrome,
                        )
                        Spacer(Modifier.height(20.dp))
                        ThemeModeStyleSection(
                            themeMode = themeMode,
                            onThemeSelected = {
                                themeMode = it
                                onThemePreferenceChange(it)
                            },
                            visualStyle = visualStyle,
                            onVisualStyleSelected = {
                                visualStyle = it
                                onVisualStyleChange(it)
                            },
                        )
                    }
                    Column(
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        verticalArrangement = Arrangement.Center,
                    ) {
                        ColorChoicesSection(
                            style = style,
                            onStyleSelected = updateStyle,
                            contrast = contrast,
                            onContrastSelected = updateContrast,
                            onAnchorChanged = { contrastAnchor = it },
                            selectedContainerColor = previewScheme.primary,
                            selectedContentColor = previewScheme.onPrimary,
                            enabled = !systemColor,
                        )
                        Spacer(Modifier.height(20.dp))
                        DisplaySection(
                            dark = dark,
                            pureBlack = pureBlack,
                            themedIconsSupported = themedIconsSupported,
                            themedIcon = themedIcon,
                            onPureBlackChange = updatePureBlack,
                            onThemedIconChange = {
                                themedIcon = it
                                onThemedLauncherIconEnabledChange(it)
                            },
                        )
                        Spacer(Modifier.height(20.dp))
                        SystemDynamicSection(
                            systemColor = systemColor,
                            onSystemColorChange = updateSystemColor,
                        )
                    }
                }
            }

            isPad -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                        PreviewWithTransition(scheme = previewScheme)
                        ColorPresetRow(
                            seed = seed,
                            defaultSelected = defaultMonochrome,
                            dynamicColor = systemColor,
                            onSeedSelected = applySeed,
                            onDefaultSelected = selectDefaultMonochrome,
                        )
                        ThemeModeStyleSection(
                            themeMode = themeMode,
                            onThemeSelected = {
                                themeMode = it
                                onThemePreferenceChange(it)
                            },
                            visualStyle = visualStyle,
                            onVisualStyleSelected = {
                                visualStyle = it
                                onVisualStyleChange(it)
                            },
                        )
                        ColorChoicesSection(
                            style = style,
                            onStyleSelected = updateStyle,
                            contrast = contrast,
                            onContrastSelected = updateContrast,
                            onAnchorChanged = { contrastAnchor = it },
                            selectedContainerColor = previewScheme.primary,
                            selectedContentColor = previewScheme.onPrimary,
                            enabled = !systemColor,
                        )
                        DisplaySection(
                            dark = dark,
                            pureBlack = pureBlack,
                            themedIconsSupported = themedIconsSupported,
                            themedIcon = themedIcon,
                            onPureBlackChange = updatePureBlack,
                            onThemedIconChange = {
                                themedIcon = it
                                onThemedLauncherIconEnabledChange(it)
                            },
                        )
                        SystemDynamicSection(
                            systemColor = systemColor,
                            onSystemColorChange = updateSystemColor,
                        )
                    }
                }
            }

            else -> {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(WallHubSpacing.md),
                ) {
                    PreviewWithTransition(scheme = previewScheme)
                    ColorPresetRow(
                        seed = seed,
                        defaultSelected = defaultMonochrome,
                        dynamicColor = systemColor,
                        onSeedSelected = applySeed,
                        onDefaultSelected = selectDefaultMonochrome,
                    )
                    ThemeModeStyleSection(
                        themeMode = themeMode,
                        onThemeSelected = {
                            themeMode = it
                            onThemePreferenceChange(it)
                        },
                        visualStyle = visualStyle,
                        onVisualStyleSelected = {
                            visualStyle = it
                            onVisualStyleChange(it)
                        },
                    )
                    ColorChoicesSection(
                        style = style,
                        onStyleSelected = updateStyle,
                        contrast = contrast,
                        onContrastSelected = updateContrast,
                        onAnchorChanged = { contrastAnchor = it },
                        selectedContainerColor = previewScheme.primary,
                        selectedContentColor = previewScheme.onPrimary,
                        enabled = !systemColor,
                    )
                    DisplaySection(
                        dark = dark,
                        pureBlack = pureBlack,
                        themedIconsSupported = themedIconsSupported,
                        themedIcon = themedIcon,
                        onPureBlackChange = updatePureBlack,
                        onThemedIconChange = {
                            themedIcon = it
                            onThemedLauncherIconEnabledChange(it)
                        },
                    )
                    SystemDynamicSection(
                        systemColor = systemColor,
                        onSystemColorChange = updateSystemColor,
                    )
                }
            }
        }
    }

        if (previewContrastMenuOpen) {
            contrastAnchor?.let { anchor ->
                ContrastMenuOverlay(
                    anchor = anchor,
                    selected = contrast,
                    onSelected = updateContrast,
                    selectedContainerColor = previewScheme.primary,
                    selectedContentColor = previewScheme.onPrimary,
                )
            }
        }
    }

    if (resetDialogVisible) {
        AlertDialog(
            onDismissRequest = { resetDialogVisible = false },
            title = { Text(stringResource(R.string.theme_editor_reset_title)) },
            text = { Text(stringResource(R.string.theme_editor_reset_message)) },
            confirmButton = {
                TextButton(onClick = resetColors) {
                    Text(stringResource(R.string.theme_editor_reset_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { resetDialogVisible = false }) {
                    Text(stringResource(R.string.settings_action_cancel))
                }
            },
        )
    }
}

@Composable
private fun ThemeModeStyleSection(
    themeMode: ThemePreference,
    onThemeSelected: (ThemePreference) -> Unit,
    visualStyle: VisualStyle,
    onVisualStyleSelected: (VisualStyle) -> Unit,
) {
    SettingsPreferenceGroup(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))) {
        item {
            ThemeModeRow(selected = themeMode, onSelected = onThemeSelected)
        }
        item {
            VisualStyleRow(selected = visualStyle, onSelected = onVisualStyleSelected)
        }
    }
}

@Composable
private fun PreviewWithTransition(scheme: androidx.compose.material3.ColorScheme) {
    Crossfade(
        targetState = scheme,
        animationSpec = tween(durationMillis = 220),
        label = "theme-preview",
    ) { target ->
        DevicePreview(scheme = target)
    }
}

@Composable
private fun ColorChoicesSection(
    style: ThemeSchemeStyle,
    onStyleSelected: (ThemeSchemeStyle) -> Unit,
    contrast: ThemeContrastLevel,
    onContrastSelected: (ThemeContrastLevel) -> Unit,
    onAnchorChanged: (Rect) -> Unit,
    selectedContainerColor: Color,
    selectedContentColor: Color,
    enabled: Boolean,
) {
    SettingsPreferenceGroup(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))) {
        item {
            SchemeStyleDropdown(
                selected = style,
                onSelected = onStyleSelected,
                selectedContainerColor = selectedContainerColor,
                selectedContentColor = selectedContentColor,
                enabled = enabled,
            )
        }
        item {
            ContrastDropdown(
                selected = contrast,
                onSelected = onContrastSelected,
                onAnchorChanged = onAnchorChanged,
                selectedContainerColor = selectedContainerColor,
                selectedContentColor = selectedContentColor,
                enabled = enabled,
            )
        }
    }
}

@Composable
private fun DisplaySection(
    dark: Boolean,
    pureBlack: Boolean,
    themedIconsSupported: Boolean,
    themedIcon: Boolean,
    onPureBlackChange: (Boolean) -> Unit,
    onThemedIconChange: (Boolean) -> Unit,
) {
    SettingsPreferenceGroup(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))) {
        item {
            SwitchPreferenceRow(
                title = stringResource(R.string.theme_editor_pure_black),
                summary =
                    stringResource(
                        if (dark) {
                            R.string.theme_editor_pure_black_dark_summary
                        } else {
                            R.string.theme_editor_pure_black_light_summary
                        },
                    ),
                checked = pureBlack,
                onCheckedChange = onPureBlackChange,
                iconContent = { SettingsPreferenceIcon(Icons.Outlined.Contrast) },
            )
        }
        item {
            SwitchPreferenceRow(
                title = stringResource(R.string.settings_themed_app_icon),
                summary =
                    stringResource(
                        if (themedIconsSupported) {
                            R.string.settings_themed_app_icon_description
                        } else {
                            R.string.theme_editor_themed_icon_unavailable
                        },
                    ),
                checked = themedIconsSupported && themedIcon,
                enabled = themedIconsSupported,
                onCheckedChange = onThemedIconChange,
                iconContent = { SettingsPreferenceIcon(Icons.Outlined.PhoneAndroid) },
            )
        }
    }
}

@Composable
private fun SystemDynamicSection(
    systemColor: Boolean,
    onSystemColorChange: (Boolean) -> Unit,
) {
    SettingsPreferenceGroup(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))) {
        item {
            SwitchPreferenceRow(
                title = stringResource(R.string.settings_system_dynamic_color),
                summary =
                    stringResource(
                        if (systemColor) {
                            R.string.theme_editor_dynamic_follow
                        } else {
                            R.string.theme_editor_dynamic_custom
                        },
                    ),
                checked = systemColor,
                onCheckedChange = onSystemColorChange,
                iconContent = { SettingsPreferenceIcon(Icons.Outlined.Palette) },
            )
        }
    }
}

@Composable
private fun SchemeStyleDropdown(
    selected: ThemeSchemeStyle,
    onSelected: (ThemeSchemeStyle) -> Unit,
    selectedContainerColor: Color,
    selectedContentColor: Color,
    enabled: Boolean,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val selectedLabel = selected.label()
    BoxWithConstraints {
        val menuWidth = 220.dp
        val menuOffset = DpOffset(x = (maxWidth - menuWidth).coerceAtLeast(0.dp), y = 0.dp)
        PreferenceRow(
            title = stringResource(R.string.theme_editor_color_style),
            summary =
                if (enabled) {
                    null
                } else {
                    stringResource(R.string.theme_editor_dynamic_disabled)
                },
            enabled = enabled,
            modifier =
                Modifier.semantics {
                    role = Role.Button
                    stateDescription = selectedLabel
                },
            iconContent = { SettingsPreferenceIcon(Icons.Outlined.Palette) },
            trailingContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = selectedLabel,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Icon(
                        imageVector = Icons.Outlined.ArrowDropDown,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            onClick = { if (enabled) expanded = true },
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.width(menuWidth),
            offset = menuOffset,
            shape = RoundedCornerShape(20.dp),
        ) {
            ThemeSchemeStyle.entries.forEach { option ->
                val isSelected = selected == option
                DropdownMenuItem(
                    modifier =
                        if (isSelected) {
                            Modifier.background(selectedContainerColor)
                        } else {
                            Modifier
                        },
                    text = { Text(option.label()) },
                    onClick = {
                        onSelected(option)
                        expanded = false
                    },
                    colors =
                        contrastMenuItemColors(
                            selected = isSelected,
                            selectedContainerColor = selectedContainerColor,
                            selectedContentColor = selectedContentColor,
                        ),
                    trailingIcon =
                        if (isSelected) {
                            {
                                Icon(
                                    imageVector = Icons.Outlined.Check,
                                    contentDescription = null,
                                )
                            }
                        } else {
                            null
                        },
                )
            }
        }
    }
}

@Composable
private fun ThemeModeRow(
    selected: ThemePreference,
    onSelected: (ThemePreference) -> Unit,
) {
    val toggleColors = themeEditorToggleColors()
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
    ) {
        ThemePreference.entries.forEachIndexed { index, option ->
            ToggleButton(
                checked = selected == option,
                onCheckedChange = { onSelected(option) },
                shapes = connectedShapes(index, ThemePreference.entries.size),
                colors = toggleColors,
                modifier = Modifier.weight(1f).semantics { role = Role.RadioButton },
            ) {
                Icon(
                    imageVector =
                        when (option) {
                            ThemePreference.SYSTEM -> Icons.Outlined.Settings
                            ThemePreference.LIGHT -> Icons.Outlined.LightMode
                            ThemePreference.DARK -> Icons.Outlined.DarkMode
                        },
                    contentDescription = option.label(),
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun VisualStyleRow(
    selected: VisualStyle,
    onSelected: (VisualStyle) -> Unit,
) {
    val toggleColors = themeEditorToggleColors()
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
    ) {
        VisualStyle.entries.forEachIndexed { index, option ->
            ToggleButton(
                checked = selected == option,
                onCheckedChange = { onSelected(option) },
                shapes = connectedShapes(index, VisualStyle.entries.size),
                colors = toggleColors,
                modifier = Modifier.weight(1f).semantics { role = Role.RadioButton },
            ) {
                Text(option.editorLabel(), maxLines = 1)
            }
        }
    }
}

@Composable
private fun ContrastDropdown(
    selected: ThemeContrastLevel,
    onSelected: (ThemeContrastLevel) -> Unit,
    onAnchorChanged: (Rect) -> Unit,
    selectedContainerColor: Color,
    selectedContentColor: Color,
    enabled: Boolean,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val selectedLabel = selected.label()
    BoxWithConstraints {
        val menuWidth = 200.dp
        val menuOffset = DpOffset(x = (maxWidth - menuWidth).coerceAtLeast(0.dp), y = 0.dp)
        PreferenceRow(
            title = stringResource(R.string.theme_editor_color_standard),
            summary =
                if (enabled) {
                    null
                } else {
                    stringResource(R.string.theme_editor_dynamic_disabled)
                },
            enabled = enabled,
            modifier =
                Modifier
                    .onGloballyPositioned { onAnchorChanged(it.boundsInRoot()) }
                    .semantics {
                        role = Role.Button
                        stateDescription = selectedLabel
                    },
            iconContent = { SettingsPreferenceIcon(Icons.Outlined.Contrast) },
            trailingContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = selectedLabel,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Icon(
                        imageVector = Icons.Outlined.ArrowDropDown,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            onClick = { if (enabled) expanded = true },
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.width(menuWidth),
            offset = menuOffset,
            shape = RoundedCornerShape(20.dp),
        ) {
            ContrastMenuItems(
                selected = selected,
                onSelected = {
                    onSelected(it)
                    expanded = false
                },
                selectedContainerColor = selectedContainerColor,
                selectedContentColor = selectedContentColor,
            )
        }
    }
}

@Composable
private fun ContrastMenuItems(
    selected: ThemeContrastLevel,
    onSelected: (ThemeContrastLevel) -> Unit,
    selectedContainerColor: Color,
    selectedContentColor: Color,
) {
    ThemeContrastLevel.entries.forEach { option ->
        val isSelected = selected == option
        DropdownMenuItem(
            modifier =
                if (isSelected) {
                    Modifier.background(selectedContainerColor)
                } else {
                    Modifier
                },
            text = { Text(option.label()) },
            onClick = { onSelected(option) },
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            colors =
                contrastMenuItemColors(
                    selected = isSelected,
                    selectedContainerColor = selectedContainerColor,
                    selectedContentColor = selectedContentColor,
                ),
            trailingIcon =
                if (isSelected) {
                    {
                        Icon(
                            imageVector = Icons.Outlined.Check,
                            contentDescription = null,
                        )
                    }
                } else {
                    null
                },
        )
    }
}

@Composable
private fun contrastMenuItemColors(
    selected: Boolean,
    selectedContainerColor: Color,
    selectedContentColor: Color,
): MenuItemColors {
    val scheme = MaterialTheme.colorScheme
    val disabled = scheme.onSurface.copy(alpha = 0.38f)
    return if (selected) {
        MenuItemColors(
            textColor = selectedContentColor,
            leadingIconColor = selectedContentColor,
            trailingIconColor = selectedContentColor,
            disabledTextColor = disabled,
            disabledLeadingIconColor = disabled,
            disabledTrailingIconColor = disabled,
            containerColor = selectedContainerColor,
            disabledContainerColor = Color.Transparent,
            selectedTextColor = selectedContentColor,
            selectedLeadingIconColor = selectedContentColor,
            selectedTrailingIconColor = selectedContentColor,
            selectedContainerColor = selectedContainerColor,
        )
    } else {
        MenuItemColors(
            textColor = scheme.onSurface,
            leadingIconColor = scheme.onSurfaceVariant,
            trailingIconColor = scheme.onSurfaceVariant,
            disabledTextColor = disabled,
            disabledLeadingIconColor = disabled,
            disabledTrailingIconColor = disabled,
            containerColor = Color.Transparent,
            disabledContainerColor = Color.Transparent,
            selectedTextColor = scheme.onSurface,
            selectedLeadingIconColor = scheme.onSurfaceVariant,
            selectedTrailingIconColor = scheme.onSurfaceVariant,
            selectedContainerColor = Color.Transparent,
        )
    }
}

@Composable
private fun ContrastMenuOverlay(
    anchor: Rect,
    selected: ThemeContrastLevel,
    onSelected: (ThemeContrastLevel) -> Unit,
    selectedContainerColor: Color,
    selectedContentColor: Color,
) {
    val menuWidth = 200.dp
    val menuWidthPx = with(LocalDensity.current) { menuWidth.roundToPx() }
    Surface(
        modifier =
            Modifier
                .offset {
                    IntOffset(
                        x = (anchor.right.roundToInt() - menuWidthPx).coerceAtLeast(0),
                        y = anchor.bottom.roundToInt(),
                    )
                }
                .width(menuWidth),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        tonalElevation = 3.dp,
        shadowElevation = 8.dp,
    ) {
        Column {
            ContrastMenuItems(
                selected = selected,
                onSelected = onSelected,
                selectedContainerColor = selectedContainerColor,
                selectedContentColor = selectedContentColor,
            )
        }
    }
}

@Composable
private fun themeEditorToggleColors(): ToggleButtonColors {
    val expressive = wallHubStyle().capabilities.bettboxSurfaces
    val scheme = MaterialTheme.colorScheme
    return if (expressive) {
        ToggleButtonDefaults.toggleButtonColors(
            containerColor = scheme.surfaceContainerHigh,
            contentColor = scheme.onSurface,
            checkedContainerColor = scheme.primary,
            checkedContentColor = scheme.onPrimary,
        )
    } else {
        ToggleButtonDefaults.toggleButtonColors()
    }
}

@Composable
private fun connectedShapes(index: Int, count: Int): ToggleButtonShapes =
    when (index) {
        0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
        count - 1 -> ButtonGroupDefaults.connectedTrailingButtonShapes()
        else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
    }

@Composable
private fun ThemeSchemeStyle.label(): String =
    stringResource(
        when (this) {
            ThemeSchemeStyle.TONAL_SPOT -> R.string.theme_editor_style_default
            ThemeSchemeStyle.CONTENT -> R.string.theme_editor_style_brand
            ThemeSchemeStyle.VIBRANT -> R.string.theme_editor_style_vibrant
            ThemeSchemeStyle.EXPRESSIVE -> R.string.theme_editor_style_expressive
            ThemeSchemeStyle.MONOCHROME -> R.string.theme_editor_style_monochrome
        },
    )

@Composable
private fun ThemeContrastLevel.label(): String =
    stringResource(
        when (this) {
            ThemeContrastLevel.STANDARD -> R.string.theme_editor_contrast_standard
            ThemeContrastLevel.MEDIUM -> R.string.theme_editor_contrast_medium
            ThemeContrastLevel.HIGH -> R.string.theme_editor_contrast_high
        },
    )

@Composable
private fun VisualStyle.editorLabel(): String =
    stringResource(
        when (this) {
            VisualStyle.MATERIAL3 -> R.string.theme_editor_visual_material3
            VisualStyle.MATERIAL3_EXPRESSIVE -> R.string.theme_editor_visual_expressive
        },
    )

@Composable
private fun DevicePreview(
    scheme: androidx.compose.material3.ColorScheme,
) {
    val layout = LocalWallHubAdaptiveLayout.current.width
    val isPad = layout != WallHubWindowLayout.COMPACT
    val isLandscape =
        LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val shellWidth =
        when {
            !isPad -> 210.dp
            isLandscape -> 380.dp
            else -> 340.dp
        }
    val shellHeight =
        when {
            !isPad -> 390.dp
            isLandscape -> 260.dp
            else -> 480.dp
        }
    val shellRadius =
        when {
            !isPad -> 24.dp
            isLandscape -> 24.dp
            else -> 28.dp
        }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            modifier = Modifier.width(shellWidth).height(shellHeight),
            shape = RoundedCornerShape(shellRadius),
            border = BorderStroke(2.dp, MaterialTheme.colorScheme.outlineVariant),
            color = MaterialTheme.colorScheme.surfaceContainerLowest,
        ) {
            MaterialTheme(
                colorScheme = scheme,
                typography = MaterialTheme.typography,
                shapes = MaterialTheme.shapes,
            ) {
                HomeSkeleton(isPad = isPad)
            }
        }
    }
}

@Composable
private fun HomeSkeleton(isPad: Boolean) {
    Column(
        modifier = Modifier.fillMaxSize().padding(if (isPad) 14.dp else 10.dp),
        verticalArrangement = Arrangement.spacedBy(if (isPad) 10.dp else 8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                modifier = Modifier.size(width = 54.dp, height = 10.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
            ) {}
            Spacer(Modifier.weight(1f))
            Surface(
                modifier = Modifier.size(22.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
            ) {}
        }
        if (isPad) {
            Column(
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                repeat(2) { rowIndex ->
                    Row(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        repeat(3) { index ->
                            SkeletonCard(
                                modifier = Modifier.weight(1f).fillMaxSize(),
                                accent =
                                    if (rowIndex == 0 && index == 0) {
                                        MaterialTheme.colorScheme.primaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.surfaceContainerHighest
                                    },
                            )
                        }
                    }
                }
            }
        } else {
            Column(
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                repeat(2) {
                    Row(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        repeat(2) { index ->
                            SkeletonCard(
                                modifier = Modifier.weight(1f).fillMaxSize(),
                                accent =
                                    if (it == 0 && index == 0) {
                                        MaterialTheme.colorScheme.primaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.surfaceContainerHighest
                                    },
                            )
                        }
                    }
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            repeat(4) { index ->
                Surface(
                    modifier = Modifier.size(if (index == 0) 24.dp else 18.dp),
                    shape = CircleShape,
                    color =
                        if (index == 0) {
                            MaterialTheme.colorScheme.secondaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHighest
                        },
                ) {}
            }
        }
    }
}

@Composable
private fun SkeletonCard(
    modifier: Modifier,
    accent: Color,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.fillMaxSize()) {
            Surface(
                modifier = Modifier.fillMaxWidth().weight(0.62f),
                color = accent,
            ) {}
            Column(
                modifier = Modifier.fillMaxWidth().weight(0.38f).padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Surface(
                    modifier = Modifier.fillMaxWidth(0.78f).height(7.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.28f),
                ) {}
                Surface(
                    modifier = Modifier.fillMaxWidth(0.48f).height(6.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                ) {}
            }
        }
    }
}

@Composable
private fun ColorPresetRow(
    seed: String,
    defaultSelected: Boolean,
    dynamicColor: Boolean,
    onSeedSelected: (String) -> Unit,
    onDefaultSelected: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        DefaultColorSwatch(
            selected = !dynamicColor && defaultSelected,
            onClick = onDefaultSelected,
        )
        PRESET_COLORS.forEach { (hex, labelRes) ->
            ColorSwatch(
                seedHex = hex,
                selected = !dynamicColor && !defaultSelected && seed.equals(hex, ignoreCase = true),
                contentDescription = stringResource(labelRes),
                onClick = { onSeedSelected(hex) },
            )
        }
    }
}

@Composable
private fun DefaultColorSwatch(
    selected: Boolean,
    onClick: () -> Unit,
) {
    val description = stringResource(R.string.theme_editor_default_colors)
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = Color.Transparent,
        border =
            BorderStroke(
                width = if (selected) 3.dp else 1.dp,
                color =
                    if (selected) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.outlineVariant
                    },
            ),
        modifier =
            Modifier
                .size(48.dp)
                .semantics {
                    role = Role.RadioButton
                    this.selected = selected
                    contentDescription = description
                },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                drawArc(Color(0xFF17191C), -90f, 180f, true)
                drawArc(Color(0xFFF5F5F5), 90f, 180f, true)
            }
            if (selected) {
                Icon(
                    imageVector = Icons.Outlined.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun ColorSwatch(
    seedHex: String,
    selected: Boolean,
    contentDescription: String,
    onClick: () -> Unit,
) {
    val scheme = remember(seedHex) { staticAccentColorScheme(seedHex.toColor(), dark = false) }
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = Color.Transparent,
        border =
            BorderStroke(
                width = if (selected) 3.dp else 1.dp,
                color =
                    if (selected) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.outlineVariant
                    },
            ),
        modifier =
            Modifier
                .size(48.dp)
                .semantics {
                    role = Role.RadioButton
                    this.selected = selected
                    this.contentDescription = contentDescription
                },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                drawArc(scheme.primary, -90f, 90f, true)
                drawArc(scheme.primaryContainer, 0f, 90f, true)
                drawArc(scheme.secondaryContainer, 90f, 90f, true)
                drawArc(scheme.surfaceVariant, 180f, 90f, true)
                drawCircle(
                    color = scheme.primary,
                    radius = size.minDimension * 0.17f,
                    center = Offset(size.width / 2f, size.height / 2f),
                )
            }
            if (selected) {
                Icon(
                    imageVector = Icons.Outlined.Check,
                    contentDescription = null,
                    tint = scheme.onPrimary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

private fun buildPreviewScheme(
    seed: String,
    dark: Boolean,
    style: ThemeSchemeStyle,
    contrast: ThemeContrastLevel,
    pureBlack: Boolean,
): androidx.compose.material3.ColorScheme {
    val scheme =
        staticAccentColorScheme(
            seedColor = seed.toColor(),
            dark = dark,
            schemeStyle = style,
            contrastLevel = contrast.contrast,
        )
    if (!pureBlack || !dark) return scheme
    return scheme.copy(
        background = Color.Black,
        surface = Color.Black,
        surfaceContainerLowest = Color.Black,
        surfaceContainerLow = Color(0xFF070707),
        surfaceContainer = Color(0xFF0D0D0D),
        surfaceContainerHigh = Color(0xFF141414),
        surfaceContainerHighest = Color(0xFF1B1B1B),
        surfaceDim = Color.Black,
        surfaceBright = Color(0xFF1F1F1F),
    )
}

private fun String.toColor(): Color =
    runCatching { Color(AndroidColor.parseColor(this)) }.getOrDefault(Color(0xFF2B6CB0))
