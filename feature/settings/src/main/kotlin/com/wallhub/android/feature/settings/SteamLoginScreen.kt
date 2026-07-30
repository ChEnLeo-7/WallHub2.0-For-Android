@file:Suppress("ktlint:standard:function-naming")

package com.wallhub.android.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.wallhub.android.core.designsystem.WallHubSecondaryButton
import com.wallhub.android.core.designsystem.WallHubSpacing
import com.wallhub.android.core.model.SteamSessionRepository
import com.wallhub.android.core.model.SteamSessionState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import com.wallhub.android.core.designsystem.WallHubIcons as Icons

sealed interface SteamLoginAction {
    data class Login(
        val accountName: String,
        val password: String,
    ) : SteamLoginAction

    data class SubmitCode(
        val code: String,
    ) : SteamLoginAction

    data object UseManualCodeFallback : SteamLoginAction

    data object RetryRestore : SteamLoginAction

    data object Logout : SteamLoginAction
}

@HiltViewModel
class SteamLoginViewModel
    @Inject
    constructor(
        private val steamSessionRepository: SteamSessionRepository,
    ) : ViewModel() {
        val session: StateFlow<SteamSessionState> =
            steamSessionRepository.session.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = SteamSessionState(),
            )

        fun onAction(action: SteamLoginAction) {
            when (action) {
                is SteamLoginAction.Login -> login(action.accountName, action.password)
                is SteamLoginAction.SubmitCode -> submitCode(action.code)
                SteamLoginAction.UseManualCodeFallback -> useManualCodeFallback()
                SteamLoginAction.RetryRestore -> retryRestore()
                SteamLoginAction.Logout -> logout()
            }
        }

        private fun login(
            accountName: String,
            password: String,
        ) {
            steamSessionRepository.login(accountName, password)
        }

        private fun submitCode(code: String) {
            steamSessionRepository.submitSteamGuardCode(code)
        }

        private fun useManualCodeFallback() {
            steamSessionRepository.useManualSteamGuardFallback()
        }

        private fun retryRestore() {
            steamSessionRepository.restorePersistedSession()
        }

        private fun logout() {
            steamSessionRepository.logout()
        }
    }

@Composable
fun SteamLoginRoute(
    onBack: () -> Unit,
    viewModel: SteamLoginViewModel = hiltViewModel(),
) {
    val session by viewModel.session.collectAsStateWithLifecycle()
    SteamLoginScreen(
        session = session,
        onBack = onBack,
        onAction = viewModel::onAction,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SteamLoginScreen(
    session: SteamSessionState,
    onBack: () -> Unit,
    onAction: (SteamLoginAction) -> Unit,
) {
    val onLogin: (String, String) -> Unit = { accountName, password ->
        onAction(SteamLoginAction.Login(accountName, password))
    }
    val onSubmitCode: (String) -> Unit = { onAction(SteamLoginAction.SubmitCode(it)) }
    val onUseManualCodeFallback: () -> Unit = { onAction(SteamLoginAction.UseManualCodeFallback) }
    val onRetryRestore: () -> Unit = { onAction(SteamLoginAction.RetryRestore) }
    val onLogout: () -> Unit = { onAction(SteamLoginAction.Logout) }
    var accountName by rememberSaveable { mutableStateOf(session.accountName.orEmpty()) }
    // Password intentionally is not saveable, so Android does not place it in saved instance state.
    var password by remember { mutableStateOf("") }
    // Guard codes are credentials too; never place them in Android saved instance state.
    var guardCode by remember { mutableStateOf("") }
    val uiState = session.toSteamLoginUiState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    androidx.compose.foundation.layout.Row(
                        horizontalArrangement = Arrangement.spacedBy(WallHubSpacing.compact),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.PersonOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Text("Steam 登录")
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = "返回",
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = WallHubSpacing.content, vertical = WallHubSpacing.md)
                    .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(WallHubSpacing.md),
        ) {
            session.message?.let { message ->
                Text(
                    text = message,
                    color =
                        if (uiState.isFailure) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                )
            }
            if (uiState.isBusy) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            if (uiState.isSignedIn) {
                SteamLoginCard {
                    Text("已登录：${session.accountName.orEmpty()}")
                    WallHubSecondaryButton(
                        onClick = onLogout,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("退出 Steam")
                    }
                }
                return@Column
            }

            SteamLoginCard {
                SettingsFilledTextField(
                    value = accountName,
                    onValueChange = { accountName = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !uiState.isBusy,
                    label = { Text("Steam 用户名") },
                )
                SettingsFilledTextField(
                    value = password,
                    onValueChange = { password = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !uiState.isBusy,
                    label = { Text("密码") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    leadingIcon = {
                        Icon(imageVector = Icons.Outlined.Lock, contentDescription = null)
                    },
                )
                Button(
                    onClick = {
                        onLogin(accountName, password)
                        password = ""
                        guardCode = ""
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !uiState.isBusy && accountName.isNotBlank() && password.isNotBlank(),
                ) {
                    Text("登录 Steam")
                }
            }

            if (uiState.showRestoreRetry) {
                WallHubSecondaryButton(
                    onClick = onRetryRestore,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("重试恢复已保存的 Steam 会话")
                }
            }

            if (uiState.showDeviceConfirmationHint) {
                Text(
                    "已向 Steam 手机客户端发送确认请求。完成放行后，页面会自动继续。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (uiState.showManualCodeFallback) {
                    WallHubSecondaryButton(
                        onClick = onUseManualCodeFallback,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(imageVector = Icons.Outlined.PhoneAndroid, contentDescription = null)
                        Text("改用 Steam Guard 验证码", modifier = Modifier.padding(start = WallHubSpacing.xs))
                    }
                }
            }

            if (uiState.showCodeInput) {
                SteamLoginCard {
                    SettingsFilledTextField(
                        value = guardCode,
                        onValueChange = { guardCode = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("Steam Guard / 邮件验证码") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    )
                    Button(
                        onClick = {
                            onSubmitCode(guardCode)
                            guardCode = ""
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = guardCode.isNotBlank(),
                    ) {
                        Text("提交验证码")
                    }
                }
            }
        }
    }
}

@Composable
private fun SteamLoginCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.padding(WallHubSpacing.md),
            verticalArrangement = Arrangement.spacedBy(WallHubSpacing.sm),
            content = content,
        )
    }
}
