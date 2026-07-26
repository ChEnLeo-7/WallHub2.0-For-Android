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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wallhub.android.core.designsystem.WallHubIcons as Icons
import com.wallhub.android.core.designsystem.WallHubSecondaryButton
import com.wallhub.android.core.model.SteamSessionRepository
import com.wallhub.android.core.model.SteamSessionState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

@HiltViewModel
class SteamLoginViewModel @Inject constructor(
    private val steamSessionRepository: SteamSessionRepository,
) : ViewModel() {
    val session: StateFlow<SteamSessionState> = steamSessionRepository.session.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SteamSessionState(),
    )

    fun login(accountName: String, password: String) {
        steamSessionRepository.login(accountName, password)
    }

    fun submitCode(code: String) {
        steamSessionRepository.submitSteamGuardCode(code)
    }

    fun useManualCodeFallback() {
        steamSessionRepository.useManualSteamGuardFallback()
    }

    fun retryRestore() {
        steamSessionRepository.restorePersistedSession()
    }

    fun logout() {
        steamSessionRepository.logout()
    }
}

@Composable
fun SteamLoginRoute(
    onBack: () -> Unit,
    viewModel: SteamLoginViewModel = hiltViewModel(),
) {
    val session by viewModel.session.collectAsState()
    SteamLoginScreen(
        session = session,
        onBack = onBack,
        onLogin = viewModel::login,
        onSubmitCode = viewModel::submitCode,
        onUseManualCodeFallback = viewModel::useManualCodeFallback,
        onRetryRestore = viewModel::retryRestore,
        onLogout = viewModel::logout,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SteamLoginScreen(
    session: SteamSessionState,
    onBack: () -> Unit,
    onLogin: (String, String) -> Unit,
    onSubmitCode: (String) -> Unit,
    onUseManualCodeFallback: () -> Unit,
    onRetryRestore: () -> Unit,
    onLogout: () -> Unit,
) {
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
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
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
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp, vertical = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            session.message?.let { message ->
                Text(
                    text = message,
                    color = if (uiState.isFailure) {
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
                        Text("改用 Steam Guard 验证码", modifier = Modifier.padding(start = 8.dp))
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
private fun SteamLoginCard(
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}
