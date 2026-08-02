@file:Suppress("ktlint:standard:function-naming")

package com.wallhub.android.feature.settings

import android.os.Build
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.wallhub.android.R
import com.wallhub.android.core.designsystem.WallHubAnimatedSelectionCheck
import com.wallhub.android.core.designsystem.WallHubColorTokens
import com.wallhub.android.core.designsystem.WallHubSizeTokens
import com.wallhub.android.core.designsystem.WallHubSpacing
import com.wallhub.android.core.designsystem.wallHubPreviewColor
import com.wallhub.android.core.model.AccentPreference
import com.wallhub.android.core.model.AppPreferences
import com.wallhub.android.core.model.HomeCardAction
import com.wallhub.android.core.model.HomePaginationMode
import com.wallhub.android.core.model.SteamAccessPhase
import com.wallhub.android.core.model.SteamAccessState
import com.wallhub.android.core.model.SteamSessionPhase
import com.wallhub.android.core.model.SteamSessionState
import com.wallhub.android.core.model.ThemePreference
import org.uwuaosp.compose.settingslib.PreferenceGroupSpacer
import org.uwuaosp.compose.settingslib.PreferencePosition
import org.uwuaosp.compose.settingslib.PreferenceRow
import org.uwuaosp.compose.settingslib.SwitchPreferenceRow
import org.uwuaosp.compose.settingslib.SettingsHomepageIcon
import org.uwuaosp.compose.settingslib.SettingsScaffold
import org.uwuaosp.compose.settingslib.rememberSettingsTypography
import org.uwuaosp.compose.settingslib.SettingsCategory as UwuSettingsCategory
import java.util.Locale
import android.graphics.Color as AndroidColor
import com.wallhub.android.core.designsystem.WallHubIcons as Icons

@Composable
internal fun AppearanceSettingsScreen(
    preferences: AppPreferences,
    availableAccents: List<AccentPreference>,
    customAccentColor: String,
    onBack: () -> Unit,
    onCustomAccentColorChanged: (String) -> Unit,
    onThemePreferenceChange: (ThemePreference) -> Unit,
    onAccentChange: (AccentPreference, String?) -> Unit,
    onSystemMonetEnabledChange: (Boolean) -> Unit,
    onThemedLauncherIconEnabledChange: (Boolean) -> Unit,
    onHomePreferencesChange: (Int, Int, Boolean, HomeCardAction, Boolean) -> Unit,
    onHomePaginationModeChange: (HomePaginationMode) -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    MaterialTheme(
        colorScheme =
            colorScheme.copy(
                surfaceContainer = colorScheme.surfaceContainerLowest,
                surfaceBright = colorScheme.surfaceContainerLow,
            ),
        typography = rememberSettingsTypography(),
    ) {
        SettingsScaffold(
            title = stringResource(SettingsCategory.APPEARANCE.labelRes),
            showBackButton = true,
            onNavigateUp = onBack,
        ) {
            Column(
                modifier =
                    Modifier
                        .align(Alignment.CenterHorizontally)
                        .widthIn(max = SETTINGS_CONTENT_MAX_WIDTH)
                        .fillMaxWidth(),
            ) {
                AppearanceSettingsContent(
                    preferences = preferences,
                    availableAccents = availableAccents,
                    customAccentColor = customAccentColor,
                    onCustomAccentColorChanged = onCustomAccentColorChanged,
                    onThemePreferenceChange = onThemePreferenceChange,
                    onAccentChange = onAccentChange,
                    onSystemMonetEnabledChange = onSystemMonetEnabledChange,
                    onThemedLauncherIconEnabledChange = onThemedLauncherIconEnabledChange,
                    onHomePreferencesChange = onHomePreferencesChange,
                    onHomePaginationModeChange = onHomePaginationModeChange,
                )
            }
        }
    }
}

@Composable
internal fun AppearanceSettingsContent(
    preferences: AppPreferences,
    availableAccents: List<AccentPreference>,
    customAccentColor: String,
    onCustomAccentColorChanged: (String) -> Unit,
    onThemePreferenceChange: (ThemePreference) -> Unit,
    onAccentChange: (AccentPreference, String?) -> Unit,
    onSystemMonetEnabledChange: (Boolean) -> Unit,
    onThemedLauncherIconEnabledChange: (Boolean) -> Unit,
    onHomePreferencesChange: (Int, Int, Boolean, HomeCardAction, Boolean) -> Unit,
    onHomePaginationModeChange: (HomePaginationMode) -> Unit,
) {
    fun saveHomePreferences(
        pageSize: Int = preferences.homePageSize,
        columns: Int = preferences.homeColumns,
        multiSelect: Boolean = preferences.homeFilterMultiSelect,
        cardAction: HomeCardAction = preferences.homeCardAction,
    ) {
        onHomePreferencesChange(
            pageSize,
            columns,
            multiSelect,
            cardAction,
            preferences.matureContentEnabled,
        )
    }

    Column(modifier = Modifier.fillMaxWidth()) {
            UwuSettingsCategory(title = stringResource(R.string.settings_theme_title))
            SettingChoiceRow(
                title = stringResource(R.string.settings_theme_mode),
                selectedValue = preferences.theme,
                values = ThemePreference.entries,
                label = { theme -> theme.label() },
                onSelected = onThemePreferenceChange,
                position = PreferencePosition.Single,
                icon = Icons.Outlined.DarkMode,
            )

            UwuSettingsCategory(title = stringResource(R.string.settings_personalized_color_title))
            SwitchPreferenceRow(
                title = stringResource(R.string.settings_system_dynamic_color),
                summary =
                    when {
                        preferences.useSystemMonet && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                            stringResource(R.string.settings_dynamic_color_wallpaper)
                        preferences.useSystemMonet -> stringResource(R.string.settings_dynamic_color_fallback)
                        else -> stringResource(R.string.settings_dynamic_color_disabled)
                    },
                checked = preferences.useSystemMonet,
                onCheckedChange = onSystemMonetEnabledChange,
                iconContent = { AppearancePreferenceIcon(Icons.Outlined.Palette) },
                position = PreferencePosition.Top,
            )
            PreferenceGroupSpacer()
            val themedIconsSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
            SwitchPreferenceRow(
                title = stringResource(R.string.settings_themed_app_icon),
                summary =
                    if (themedIconsSupported) {
                        stringResource(R.string.settings_themed_app_icon_description)
                    } else {
                        stringResource(R.string.settings_requires_android_13)
                    },
                checked = themedIconsSupported && preferences.useThemedLauncherIcon,
                enabled = themedIconsSupported,
                onCheckedChange = onThemedLauncherIconEnabledChange,
                iconContent = { AppearancePreferenceIcon(Icons.Outlined.PhoneAndroid) },
                position = PreferencePosition.Middle,
            )
            PreferenceGroupSpacer()
            AccentPreferenceChoiceRow(
                title = stringResource(R.string.settings_static_palette),
                selectedValue =
                    preferences.accent.takeUnless {
                        it == AccentPreference.MONET
                    } ?: AccentPreference.DEFAULT,
                values = availableAccents,
                customColor = customAccentColor,
                systemMonetColor = MaterialTheme.colorScheme.primary,
                onSelected = { accent ->
                    onAccentChange(
                        accent,
                        if (accent == AccentPreference.CUSTOM) customAccentColor else null,
                    )
                },
                onCustomColorChanged = onCustomAccentColorChanged,
                onApplyCustom = {
                    onAccentChange(AccentPreference.CUSTOM, customAccentColor)
                },
                position = PreferencePosition.Bottom,
            )

            UwuSettingsCategory(title = stringResource(R.string.settings_discover_title))
            SettingChoiceRow(
                title = stringResource(R.string.settings_items_per_page),
                selectedValue = preferences.homePageSize,
                values = listOf(10, 15, 24, 30, 50),
                label = { "$it" },
                onSelected = { value -> saveHomePreferences(pageSize = value) },
                position = PreferencePosition.Top,
                icon = Icons.Outlined.ViewList,
            )
            PreferenceGroupSpacer()
            SettingChoiceRow(
                title = stringResource(R.string.settings_pagination),
                selectedValue = preferences.homePaginationMode,
                values = HomePaginationMode.entries,
                label = { mode -> mode.label() },
                onSelected = onHomePaginationModeChange,
                position = PreferencePosition.Middle,
                icon = Icons.Outlined.VerticalAlignTop,
            )
            PreferenceGroupSpacer()
            SettingChoiceRow(
                title = stringResource(R.string.settings_phone_columns),
                selectedValue = preferences.homeColumns,
                values = listOf(1, 2, 3, 4),
                label = { pluralStringResource(R.plurals.settings_column_count, it, it) },
                onSelected = { value -> saveHomePreferences(columns = value) },
                position = PreferencePosition.Middle,
                icon = Icons.Outlined.GridView,
            )
            PreferenceGroupSpacer()
            SwitchPreferenceRow(
                title = stringResource(R.string.settings_multi_select_filters),
                summary = stringResource(R.string.settings_multi_select_filters_description),
                checked = preferences.homeFilterMultiSelect,
                onCheckedChange = { enabled -> saveHomePreferences(multiSelect = enabled) },
                position = PreferencePosition.Middle,
                iconContent = { AppearancePreferenceIcon(Icons.Outlined.FilterList) },
            )
            PreferenceGroupSpacer()
            SettingChoiceRow(
                title = stringResource(R.string.settings_default_card_action),
                selectedValue = preferences.homeCardAction,
                values = HomeCardAction.entries,
                label = { action -> action.label() },
                onSelected = { action -> saveHomePreferences(cardAction = action) },
                position = PreferencePosition.Bottom,
                icon = Icons.Outlined.Tune,
            )
    }
}

@Composable
internal fun SettingsSwitchRow(
    title: String,
    supportingText: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    SettingsListItem(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.medium)
                .toggleable(
                    value = checked,
                    enabled = enabled,
                    role = Role.Switch,
                    onValueChange = onCheckedChange,
                ),
        headlineContent = { Text(title) },
        supportingContent = { Text(supportingText) },
        trailingContent = {
            Switch(
                checked = checked,
                enabled = enabled,
                onCheckedChange = null,
            )
        },
    )
}

@Composable
internal fun SettingsItemDivider() {
    Spacer(modifier = Modifier.height(WallHubSpacing.xxxs))
}

@Composable
internal fun SettingsLeadingIcon(
    icon: ImageVector,
    prominent: Boolean = false,
) {
    val containerColor =
        if (prominent) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.secondaryContainer
        }
    val contentColor =
        if (prominent) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSecondaryContainer
        }
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = containerColor,
        contentColor = contentColor,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.padding(WallHubSpacing.compact).size(WallHubSizeTokens.smallIcon),
        )
    }
}

@Composable
internal fun SettingsCategoryIndex(
    title: String,
    onBack: () -> Unit,
    onOpenCategory: (SettingsCategory) -> Unit,
    onOpenAbout: () -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    MaterialTheme(
        colorScheme =
            colorScheme.copy(
                surfaceContainer = colorScheme.surfaceContainerLowest,
                surfaceBright = colorScheme.surfaceContainerLow,
            ),
        typography = rememberSettingsTypography(),
    ) {
        SettingsScaffold(
            title = title,
            showBackButton = true,
            onNavigateUp = onBack,
        ) {
            Column(
                modifier =
                    Modifier
                        .align(Alignment.CenterHorizontally)
                        .widthIn(max = SETTINGS_CONTENT_MAX_WIDTH)
                        .fillMaxWidth(),
            ) {
                SettingsCategory.entries.forEachIndexed { index, category ->
                    PreferenceRow(
                        title = stringResource(category.labelRes),
                        summary = stringResource(category.descriptionRes),
                        position =
                            when (index) {
                                0 -> PreferencePosition.Top
                                SettingsCategory.entries.lastIndex -> PreferencePosition.Bottom
                                else -> PreferencePosition.Middle
                            },
                        iconContent = {
                            SettingsHomepageIcon(
                                imageVector = category.icon,
                                backgroundColor = MaterialTheme.colorScheme.primary,
                                foregroundColor = MaterialTheme.colorScheme.onPrimary,
                            )
                        },
                        onClick = { onOpenCategory(category) },
                    )
                    if (index != SettingsCategory.entries.lastIndex) PreferenceGroupSpacer()
                }
                Spacer(modifier = Modifier.height(WallHubSpacing.sm))
                PreferenceRow(
                    title = stringResource(R.string.settings_about_wallhub_for_android),
                    summary = stringResource(R.string.settings_about_entry_description),
                    position = PreferencePosition.Single,
                    iconContent = {
                        SettingsHomepageIcon(
                            imageVector = Icons.Outlined.Info,
                            backgroundColor = MaterialTheme.colorScheme.primary,
                            foregroundColor = MaterialTheme.colorScheme.onPrimary,
                        )
                    },
                    onClick = onOpenAbout,
                )
            }
        }
    }
}

@Composable
internal fun SteamSessionState.settingsSummary(): String =
    when (phase) {
        SteamSessionPhase.SIGNED_IN -> stringResource(R.string.settings_steam_signed_in_as, accountName.orEmpty())
        SteamSessionPhase.RESTORABLE -> stringResource(R.string.settings_steam_saved_sign_in)
        SteamSessionPhase.SIGNING_IN,
        SteamSessionPhase.WAITING_FOR_DEVICE_CONFIRMATION,
        SteamSessionPhase.WAITING_FOR_CODE,
        -> message ?: stringResource(R.string.settings_steam_signing_in)

        SteamSessionPhase.EXPIRED,
        SteamSessionPhase.FAILED,
        -> message ?: stringResource(R.string.settings_steam_sign_in_needs_verification)

        SteamSessionPhase.SIGNED_OUT -> stringResource(R.string.settings_steam_not_signed_in)
    }

@Composable
internal fun SteamAccessState.summary(): String {
    val phaseLabel =
        when (phase) {
            SteamAccessPhase.DISABLED -> stringResource(R.string.settings_disabled)
            SteamAccessPhase.RESOLVING -> stringResource(R.string.settings_steam_routes_checking)
            SteamAccessPhase.READY -> stringResource(R.string.settings_steam_route_available)
            SteamAccessPhase.DEGRADED -> stringResource(R.string.settings_steam_route_unstable)
            SteamAccessPhase.FAILED -> stringResource(R.string.settings_steam_no_route)
        }
    return phaseLabel + message?.let { "\n$it" }.orEmpty()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun <T> SettingChoiceRow(
    title: String,
    selectedValue: T,
    values: List<T>,
    label: @Composable (T) -> String,
    onSelected: (T) -> Unit,
    position: PreferencePosition = PreferencePosition.Single,
    icon: ImageVector? = null,
) {
    var menuVisible by rememberSaveable { mutableStateOf(false) }
    val selectedLabel = label(selectedValue)
    PreferenceRow(
        title = title,
        position = position,
        iconContent = icon?.let { { AppearancePreferenceIcon(it) } },
        trailingContent = {
            ExposedPreferenceDropdown(
                value = selectedLabel,
                expanded = menuVisible,
                onExpandedChange = { menuVisible = it },
            ) {
                DropdownMenuGroup(shapes = MenuDefaults.groupShape(index = 0, count = 1)) {
                    values.forEachIndexed { index, value ->
                        DropdownMenuItem(
                            selected = value == selectedValue,
                            onClick = {
                                onSelected(value)
                                menuVisible = false
                            },
                            text = { Text(label(value)) },
                            shapes = MenuDefaults.itemShape(index = index, count = values.size),
                            selectedLeadingIcon = {
                                WallHubAnimatedSelectionCheck(selected = true)
                            },
                        )
                    }
                }
            }
        },
        onClick = { menuVisible = true },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExposedPreferenceDropdown(
    value: String,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = onExpandedChange,
    ) {
        TextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            trailingIcon = {
                ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
            },
            modifier =
                Modifier
                    .width(SETTINGS_DROPDOWN_FIELD_WIDTH)
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) },
            containerColor = Color.Transparent,
            tonalElevation = WallHubSpacing.none,
            shadowElevation = WallHubSpacing.none,
            content = content,
        )
    }
}

@Composable
private fun AppearancePreferenceIcon(icon: ImageVector) {
    SettingsHomepageIcon(
        imageVector = icon,
        backgroundColor = MaterialTheme.colorScheme.primary,
        foregroundColor = MaterialTheme.colorScheme.onPrimary,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SteamStreamCacheSetting(
    cacheLimitMb: Int,
    onCacheLimitChange: (Int) -> Unit,
) {
    val context = LocalContext.current
    var customSheetVisible by rememberSaveable { mutableStateOf(false) }
    var customLimitText by remember(cacheLimitMb) { mutableStateOf(cacheLimitMb.toString()) }
    var customLimitError by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedPreset =
        SteamStreamCachePreset.entries.firstOrNull { preset ->
            preset.limitMb == cacheLimitMb
        } ?: SteamStreamCachePreset.CUSTOM

    SettingChoiceRow(
        title = stringResource(R.string.settings_streaming_cache),
        selectedValue = selectedPreset,
        values = SteamStreamCachePreset.entries.toList(),
        label = { preset ->
            preset.limitMb?.let(::formatSteamStreamCacheLimit)
                ?: stringResource(R.string.settings_custom_value, formatSteamStreamCacheLimit(cacheLimitMb))
        },
        onSelected = { preset ->
            preset.limitMb?.let(onCacheLimitChange) ?: run {
                customLimitText = cacheLimitMb.toString()
                customLimitError = null
                customSheetVisible = true
            }
        },
    )

    if (customSheetVisible) {
        ModalBottomSheet(onDismissRequest = { customSheetVisible = false }) {
            SettingsSheetContent {
                Text(
                    text = stringResource(R.string.settings_custom_streaming_cache),
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    text =
                        stringResource(R.string.settings_custom_streaming_cache_description),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SettingsFilledTextField(
                    value = customLimitText,
                    onValueChange = { value ->
                        customLimitText = value.filter(Char::isDigit)
                        customLimitError = null
                    },
                    label = { Text(stringResource(R.string.settings_cache_size_mb)) },
                    singleLine = true,
                    isError = customLimitError != null,
                    supportingText = customLimitError?.let { error -> { Text(error) } },
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = {
                        val limitMb = customLimitText.toIntOrNull()
                        if (limitMb == null || limitMb < 128) {
                            customLimitError =
                                context.getString(R.string.settings_error_cache_size)
                        } else {
                            onCacheLimitChange(limitMb)
                            customSheetVisible = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.settings_action_apply))
                }
                Spacer(modifier = Modifier.height(WallHubSpacing.xs))
            }
        }
    }
}

internal fun formatSteamStreamCacheLimit(limitMb: Int): String =
    if (limitMb >= 1024 && limitMb % 1024 == 0) "${limitMb / 1024} GB" else "$limitMb MB"

@Composable
internal fun SettingsSheetContent(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(max = SETTINGS_SHEET_CONTENT_MAX_HEIGHT)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = WallHubSpacing.content, vertical = WallHubSpacing.xs),
        verticalArrangement = Arrangement.spacedBy(WallHubSpacing.xs),
        content = content,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AccentPreferenceChoiceRow(
    title: String,
    selectedValue: AccentPreference,
    values: List<AccentPreference>,
    customColor: String,
    systemMonetColor: Color,
    onSelected: (AccentPreference) -> Unit,
    onCustomColorChanged: (String) -> Unit,
    onApplyCustom: () -> Unit,
    position: PreferencePosition = PreferencePosition.Single,
) {
    var menuVisible by rememberSaveable { mutableStateOf(false) }
    var customSheetVisible by rememberSaveable { mutableStateOf(false) }
    PreferenceRow(
        title = title,
        position = position,
        iconContent = { AppearancePreferenceIcon(Icons.Outlined.Palette) },
        trailingContent = {
            ExposedPreferenceDropdown(
                value = selectedValue.label(),
                expanded = menuVisible,
                onExpandedChange = { menuVisible = it },
            ) {
                DropdownMenuGroup(shapes = MenuDefaults.groupShape(index = 0, count = 1)) {
                    values.forEachIndexed { index, accent ->
                        DropdownMenuItem(
                            selected = accent == selectedValue,
                            onClick = {
                                menuVisible = false
                                if (accent == AccentPreference.CUSTOM) {
                                    customSheetVisible = true
                                } else {
                                    onSelected(accent)
                                }
                            },
                            text = { Text(accent.label()) },
                            shapes = MenuDefaults.itemShape(index = index, count = values.size),
                            leadingIcon = {
                                AccentColorDot(
                                    color =
                                        accent.wallHubPreviewColor(
                                            customColor = customColor,
                                            systemMonetColor = systemMonetColor,
                                        ),
                                )
                            },
                            selectedLeadingIcon = {
                                AccentColorDot(
                                    color =
                                        accent.wallHubPreviewColor(
                                            customColor = customColor,
                                            systemMonetColor = systemMonetColor,
                                        ),
                                )
                            },
                            trailingContent = {
                                WallHubAnimatedSelectionCheck(selected = accent == selectedValue)
                            },
                        )
                    }
                }
            }
        },
        onClick = { menuVisible = true },
    )
    if (customSheetVisible) {
        ModalBottomSheet(onDismissRequest = { customSheetVisible = false }) {
            SettingsSheetContent {
                Text(title, style = MaterialTheme.typography.titleLarge)
                MonetColorPicker(
                    colorHex = customColor,
                    onColorChanged = onCustomColorChanged,
                )
                Button(
                    onClick = {
                        onApplyCustom()
                        customSheetVisible = false
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.settings_action_apply_monet_color))
                }
                Spacer(modifier = Modifier.height(WallHubSpacing.sm))
            }
        }
    }
}

@Composable
internal fun AccentColorDot(color: Color) {
    Box(
        modifier =
            Modifier
                .size(WallHubSpacing.md)
                .clip(CircleShape)
                .background(color)
                .border(
                    width = WallHubSpacing.hairline,
                    color = MaterialTheme.colorScheme.outlineVariant,
                    shape = CircleShape,
                ),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MonetColorPicker(
    colorHex: String,
    onColorChanged: (String) -> Unit,
) {
    val hsv = colorHex.toMonetHsv()
    val previewColor = hsv.toComposeColor()
    val previewContentColor =
        if (previewColor.luminance() > 0.45f) {
            WallHubColorTokens.customAccentPreviewOnLight
        } else {
            WallHubColorTokens.customAccentPreviewOnDark
        }
    Column(verticalArrangement = Arrangement.spacedBy(WallHubSpacing.compact)) {
        Text(
            text = stringResource(R.string.settings_custom_monet_seed_color),
            style = MaterialTheme.typography.labelLarge,
        )
        Surface(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(WallHubSpacing.xxl),
            shape = MaterialTheme.shapes.medium,
            color = previewColor,
            contentColor = previewContentColor,
        ) {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(horizontal = WallHubSpacing.controlInset),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    text = hsv.toHex(),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(WallHubSpacing.compact),
            verticalArrangement = Arrangement.spacedBy(WallHubSpacing.compact),
        ) {
            MONET_HUE_PRESETS.forEach { hue ->
                val swatchColor = MonetHsv(hue, 0.82f, 0.82f).toComposeColor()
                val selected = circularHueDistance(hsv.hue, hue) < 10f
                Box(
                    modifier =
                        Modifier
                            .size(30.dp)
                            .clip(CircleShape)
                            .background(swatchColor)
                            .clickable {
                                onColorChanged(hsv.copy(hue = hue).toHex())
                            },
                    contentAlignment = Alignment.Center,
                ) {
                    CompositionLocalProvider(
                        LocalContentColor provides
                            if (swatchColor.luminance() > 0.45f) {
                                WallHubColorTokens.customAccentPreviewOnLight
                            } else {
                                WallHubColorTokens.customAccentPreviewOnDark
                            },
                    ) {
                        WallHubAnimatedSelectionCheck(
                            selected = selected,
                            size = WallHubSizeTokens.compactIcon,
                        )
                    }
                }
            }
        }
        MonetColorSlider(
            label = stringResource(R.string.settings_hue_value, hsv.hue.toInt()),
            value = hsv.hue,
            valueRange = 0f..360f,
            color = previewColor,
            onValueChange = { hue -> onColorChanged(hsv.copy(hue = hue).toHex()) },
        )
        MonetColorSlider(
            label = stringResource(R.string.settings_saturation_value, (hsv.saturation * 100).toInt()),
            value = hsv.saturation,
            valueRange = 0f..1f,
            color = previewColor,
            onValueChange = { saturation -> onColorChanged(hsv.copy(saturation = saturation).toHex()) },
        )
        MonetColorSlider(
            label = stringResource(R.string.settings_brightness_value, (hsv.brightness * 100).toInt()),
            value = hsv.brightness,
            valueRange = 0f..1f,
            color = previewColor,
            onValueChange = { brightness -> onColorChanged(hsv.copy(brightness = brightness).toHex()) },
        )
    }
}

@Composable
internal fun MonetColorSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    color: Color,
    onValueChange: (Float) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(WallHubSpacing.xxxs)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            colors =
                SliderDefaults.colors(
                    thumbColor = color,
                    activeTrackColor = color,
                ),
        )
    }
}

internal data class MonetHsv(
    val hue: Float,
    val saturation: Float,
    val brightness: Float,
) {
    fun toComposeColor(): Color =
        Color(
            AndroidColor.HSVToColor(floatArrayOf(hue, saturation, brightness)),
        )

    fun toHex(): String =
        String.format(
            Locale.US,
            "#%06X",
            AndroidColor.HSVToColor(floatArrayOf(hue, saturation, brightness)) and 0x00FFFFFF,
        )
}

internal fun String.toMonetHsv(): MonetHsv {
    val parsed =
        runCatching {
            AndroidColor.parseColor(if (startsWith("#")) this else "#$this")
        }.getOrElse { AndroidColor.parseColor(DEFAULT_CUSTOM_MONET_HEX) }
    val values = FloatArray(3)
    AndroidColor.colorToHSV(parsed, values)
    return MonetHsv(
        hue = values[0],
        saturation = values[1],
        brightness = values[2],
    )
}

internal fun circularHueDistance(
    first: Float,
    second: Float,
): Float {
    val difference = kotlin.math.abs(first - second) % 360f
    return minOf(difference, 360f - difference)
}

@Composable
internal fun ThemePreference.label(): String =
    when (this) {
        ThemePreference.SYSTEM -> stringResource(R.string.settings_theme_system)
        ThemePreference.LIGHT -> stringResource(R.string.settings_theme_light)
        ThemePreference.DARK -> stringResource(R.string.settings_theme_dark)
    }

@Composable
internal fun AccentPreference.label(): String =
    when (this) {
        AccentPreference.DEFAULT -> stringResource(R.string.settings_accent_default)
        AccentPreference.MONET -> stringResource(R.string.settings_accent_system_monet)
        AccentPreference.BLUE -> stringResource(R.string.settings_accent_blue)
        AccentPreference.GREEN -> stringResource(R.string.settings_accent_green)
        AccentPreference.ROSE -> stringResource(R.string.settings_accent_red)
        AccentPreference.VIOLET -> stringResource(R.string.settings_accent_purple)
        AccentPreference.CUSTOM -> stringResource(R.string.settings_accent_custom)
    }

@Composable
internal fun HomeCardAction.label(): String =
    when (this) {
        HomeCardAction.DOWNLOAD -> stringResource(R.string.settings_action_download)
        HomeCardAction.PLAY_VIDEO -> stringResource(R.string.settings_action_play)
        HomeCardAction.OPEN_STEAM -> stringResource(R.string.settings_action_open_steam)
    }

@Composable
internal fun HomePaginationMode.label(): String =
    when (this) {
        HomePaginationMode.INFINITE_SCROLL -> stringResource(R.string.settings_pagination_infinite)
        HomePaginationMode.PAGED -> stringResource(R.string.settings_pagination_pages)
    }

internal const val DEFAULT_CUSTOM_MONET_HEX = "#5B7AA0"
internal const val APK_MIME_TYPE = "application/vnd.android.package-archive"
internal const val STEAM_API_KEY_URL = "https://steamcommunity.com/dev/apikey"
internal const val SETTINGS_CATEGORY_INDEX_KEY = "settings-index"
internal const val SETTINGS_PAGE_ENTER_DURATION_MS = 340
internal const val SETTINGS_PAGE_EXIT_DURATION_MS = 230
internal const val SETTINGS_PAGE_ENTER_OFFSET_DIVISOR = 9
internal const val SETTINGS_PAGE_EXIT_OFFSET_DIVISOR = 18
internal val SETTINGS_MEDIUM_WIDTH = 600.dp
internal val SETTINGS_CONTENT_MAX_WIDTH = 760.dp
internal val SETTINGS_SHEET_CONTENT_MAX_HEIGHT = 560.dp
internal val SETTINGS_ITEM_MIN_HEIGHT = 64.dp
internal val SETTINGS_DROPDOWN_FIELD_WIDTH = 160.dp
internal val STEAM_DOH_ITEM_HEIGHT = 84.dp
internal val STEAM_DOH_ITEM_SPACING = WallHubSpacing.xs
internal val SETTINGS_PAGE_EASING = CubicBezierEasing(0.2f, 0f, 0f, 1f)
internal val MONET_HUE_PRESETS =
    listOf(
        0f,
        24f,
        48f,
        78f,
        120f,
        158f,
        194f,
        220f,
        254f,
        286f,
        318f,
        342f,
    )
