package com.wallhub.android.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImagePainter
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import com.wallhub.android.R
import com.wallhub.android.core.model.SteamSessionPhase
import com.wallhub.android.core.model.SteamSessionState
import com.wallhub.android.core.designsystem.WallHubIcons as Icons

@Composable
@Suppress("CyclomaticComplexMethod")
internal fun SteamAccountCard(
    session: SteamSessionState,
    onLogin: (String, String) -> Unit,
    onSubmitCode: (String) -> Unit,
    onUseManualCodeFallback: () -> Unit,
    onRestore: () -> Unit,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainer,
) {
    var accountName by rememberSaveable(session.accountName) { mutableStateOf(session.accountName.orEmpty()) }
    var password by remember { mutableStateOf("") }
    var guardCode by remember { mutableStateOf("") }
    var credentialLoginInProgress by remember { mutableStateOf(false) }
    var loginSubmissionPending by remember { mutableStateOf(false) }
    val isBusy =
        session.phase == SteamSessionPhase.SIGNING_IN ||
            session.phase == SteamSessionPhase.WAITING_FOR_DEVICE_CONFIRMATION ||
            session.phase == SteamSessionPhase.WAITING_FOR_CODE
    val hasSavedProfile =
        session.hasStoredSession &&
            !session.accountName.isNullOrBlank() &&
            session.phase != SteamSessionPhase.SIGNED_OUT
    val profileCard = session.phase == SteamSessionPhase.SIGNED_IN || hasSavedProfile
    val showCredentials =
        session.phase != SteamSessionPhase.SIGNED_IN &&
            session.phase != SteamSessionPhase.RESTORABLE &&
            (!profileCard ||
                credentialLoginInProgress ||
                session.phase == SteamSessionPhase.FAILED ||
                session.phase == SteamSessionPhase.EXPIRED)

    LaunchedEffect(session.phase) {
        loginSubmissionPending = false
        when (session.phase) {
            SteamSessionPhase.SIGNED_IN, SteamSessionPhase.SIGNED_OUT -> {
                password = ""
                guardCode = ""
                credentialLoginInProgress = false
            }

            SteamSessionPhase.RESTORABLE -> credentialLoginInProgress = false
            else -> Unit
        }
    }

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val scale = maxWidth.value / STEAM_CARD_DESIGN_WIDTH
        val shape = RoundedCornerShape((STEAM_CARD_CORNER * scale).dp)
        val horizontalPadding = (STEAM_CARD_HORIZONTAL_PADDING * scale).dp
        val topPadding = (STEAM_CARD_TOP_PADDING * scale).dp
        val bottomPadding = (STEAM_CARD_BOTTOM_PADDING * scale).dp
        val avatarSize = (STEAM_CARD_AVATAR_SIZE * scale).dp
        val contentGap = (STEAM_CARD_CONTENT_GAP * scale).dp.coerceAtLeast(8.dp)
        val buttonHeight = (STEAM_CARD_BUTTON_HEIGHT * scale).dp.coerceAtLeast(48.dp)

        Surface(
            modifier = Modifier.fillMaxWidth().heightIn(min = (STEAM_CARD_DESIGN_HEIGHT * scale).dp),
            shape = shape,
            color = containerColor,
        ) {
            Box(modifier = Modifier.clip(shape)) {
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(
                                start = horizontalPadding,
                                top = topPadding,
                                end = horizontalPadding,
                                bottom = bottomPadding,
                            ),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(contentGap),
                ) {
                    if (profileCard) {
                        SteamProfileHeader(
                            session = session,
                            avatarSize = avatarSize,
                            contentGap = contentGap,
                        )
                    } else {
                        Text(
                            text = stringResource(R.string.settings_steam_inline_sign_in_title),
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.align(Alignment.CenterHorizontally),
                        )
                    }

                    val showMessage =
                        session.message != null && session.phase != SteamSessionPhase.SIGNED_OUT
                    if (showMessage) {
                        Text(
                            text = session.message.orEmpty(),
                            style = MaterialTheme.typography.bodyMedium,
                            color =
                                if (
                                    session.phase == SteamSessionPhase.FAILED ||
                                    session.phase == SteamSessionPhase.EXPIRED
                                ) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                        )
                    }

                    if (showCredentials) {
                        TextField(
                            value = accountName,
                            onValueChange = { accountName = it },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            enabled = !isBusy,
                            singleLine = true,
                            label = { Text(stringResource(R.string.settings_steam_username)) },
                            leadingIcon = { Icon(Icons.Outlined.PersonOutline, contentDescription = null) },
                        )
                        TextField(
                            value = password,
                            onValueChange = { password = it },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            enabled = !isBusy,
                            singleLine = true,
                            label = { Text(stringResource(R.string.settings_password)) },
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            leadingIcon = { Icon(Icons.Outlined.Lock, contentDescription = null) },
                        )
                    }

                    if (
                        session.phase == SteamSessionPhase.WAITING_FOR_DEVICE_CONFIRMATION &&
                        session.awaitingDeviceConfirmation
                    ) {
                        Button(
                            onClick = onUseManualCodeFallback,
                            modifier = Modifier.fillMaxWidth().heightIn(min = buttonHeight),
                        ) {
                            Icon(Icons.Outlined.PhoneAndroid, contentDescription = null)
                            Text(
                                text = stringResource(R.string.settings_action_use_steam_guard_code),
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                    }

                    if (session.phase == SteamSessionPhase.WAITING_FOR_CODE && session.requiresCode) {
                        TextField(
                            value = guardCode,
                            onValueChange = { guardCode = it },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            singleLine = true,
                            label = { Text(stringResource(R.string.settings_steam_guard_code)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        )
                        Button(
                            onClick = {
                                onSubmitCode(guardCode)
                                guardCode = ""
                            },
                            enabled = guardCode.isNotBlank(),
                            modifier = Modifier.fillMaxWidth().heightIn(min = buttonHeight),
                        ) {
                            Text(stringResource(R.string.settings_action_submit_code))
                        }
                    }

                    when {
                        session.phase == SteamSessionPhase.SIGNED_IN -> Unit
                        session.phase == SteamSessionPhase.RESTORABLE -> {
                            SteamPrimaryActionButton(
                                text = stringResource(R.string.settings_action_reconnect_steam),
                                icon = { Icon(Icons.Outlined.Refresh, contentDescription = null) },
                                onClick = {
                                    credentialLoginInProgress = false
                                    onRestore()
                                },
                                height = buttonHeight,
                            )
                        }

                        showCredentials && !isBusy -> {
                            SteamPrimaryActionButton(
                                text =
                                    stringResource(
                                        if (profileCard) {
                                            R.string.settings_action_sign_in_again_steam
                                        } else {
                                            R.string.settings_action_sign_in_steam
                                        },
                                    ),
                                icon = { Icon(Icons.Outlined.PersonOutline, contentDescription = null) },
                                enabled =
                                    !loginSubmissionPending &&
                                        accountName.isNotBlank() &&
                                        password.isNotBlank(),
                                onClick = {
                                    loginSubmissionPending = true
                                    credentialLoginInProgress = true
                                    guardCode = ""
                                    onLogin(accountName, password)
                                },
                                height = buttonHeight,
                            )
                        }
                    }

                    if (profileCard) {
                        Button(
                            onClick = {
                                password = ""
                                guardCode = ""
                                onLogout()
                            },
                            modifier = Modifier.fillMaxWidth().heightIn(min = buttonHeight),
                            colors =
                                ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.errorContainer,
                                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                                ),
                        ) {
                            Icon(Icons.AutoMirrored.Outlined.Logout, contentDescription = null)
                            Text(
                                text = stringResource(R.string.settings_action_sign_out_steam),
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                    }
                }

                when (session.phase) {
                    SteamSessionPhase.SIGNING_IN ->
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth().height(STEAM_CARD_PROGRESS_HEIGHT.dp),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant,
                        )

                    SteamSessionPhase.WAITING_FOR_DEVICE_CONFIRMATION,
                    SteamSessionPhase.WAITING_FOR_CODE,
                    ->
                        Spacer(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .height(STEAM_CARD_PROGRESS_HEIGHT.dp)
                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                        )

                    else -> Unit
                }
            }
        }
    }
}

@Composable
private fun SteamProfileHeader(
    session: SteamSessionState,
    avatarSize: androidx.compose.ui.unit.Dp,
    contentGap: androidx.compose.ui.unit.Dp,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(contentGap),
    ) {
        SteamAccountAvatar(session.avatarUrl, avatarSize)
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = session.personaName.orEmpty().ifBlank { session.accountName.orEmpty() },
                style = MaterialTheme.typography.titleLarge,
            )
            session.accountName?.takeIf(String::isNotBlank)?.let { accountName ->
                Text(
                    text = accountName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (session.phase == SteamSessionPhase.RESTORABLE) {
                Text(
                    text = stringResource(R.string.settings_steam_disconnected),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun SteamAccountAvatar(
    avatarUrl: String?,
    size: androidx.compose.ui.unit.Dp,
) {
    Surface(
        modifier = Modifier.size(size),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primary,
    ) {
        if (avatarUrl.isNullOrBlank()) {
            SteamAvatarFallback()
        } else {
            SubcomposeAsyncImage(
                model = avatarUrl,
                contentDescription = stringResource(R.string.settings_steam_avatar),
                contentScale = ContentScale.Crop,
                modifier = Modifier.clip(CircleShape),
            ) {
                if (painter.state is AsyncImagePainter.State.Success) {
                    SubcomposeAsyncImageContent()
                } else {
                    SteamAvatarFallback()
                }
            }
        }
    }
}

@Composable
private fun SteamAvatarFallback() {
    Box(contentAlignment = Alignment.Center) {
        Icon(
            imageVector = Icons.Outlined.PersonOutline,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.fillMaxWidth(0.56f),
        )
    }
}

@Composable
private fun SteamPrimaryActionButton(
    text: String,
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
    height: androidx.compose.ui.unit.Dp,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = height),
        colors =
            ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
    ) {
        icon()
        Text(text = text, modifier = Modifier.padding(start = 8.dp))
    }
}

private const val STEAM_CARD_DESIGN_WIDTH = 460f
private const val STEAM_CARD_DESIGN_HEIGHT = 200f
private const val STEAM_CARD_CORNER = 46f
private const val STEAM_CARD_HORIZONTAL_PADDING = 32f
private const val STEAM_CARD_TOP_PADDING = 31f
private const val STEAM_CARD_BOTTOM_PADDING = 25f
private const val STEAM_CARD_AVATAR_SIZE = 85f
private const val STEAM_CARD_CONTENT_GAP = 14f
private const val STEAM_CARD_BUTTON_HEIGHT = 46f
private const val STEAM_CARD_PROGRESS_HEIGHT = 4
