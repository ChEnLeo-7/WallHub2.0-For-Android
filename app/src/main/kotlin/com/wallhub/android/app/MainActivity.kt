package com.wallhub.android

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.wallhub.android.app.AppNavigationCommandBus
import com.wallhub.android.core.designsystem.WallHubTheme
import com.wallhub.android.core.model.SteamSessionPhase
import com.wallhub.android.core.model.SteamUnifiedWorkshopRepository
import com.wallhub.android.core.model.WorkshopBrowseQuery
import com.wallhub.android.core.model.WorkshopRating
import com.wallhub.android.core.model.WorkshopSort
import com.wallhub.android.core.model.SteamSessionRepository
import com.wallhub.android.core.model.SteamSessionState
import com.wallhub.android.data.steam.SteamContentSdkEngine
import com.wallhub.android.core.model.ThemePreference
import dagger.hilt.android.AndroidEntryPoint
import java.security.MessageDigest
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject internal lateinit var steamSessionRepository: SteamSessionRepository
    @Inject internal lateinit var steamContentSdkEngine: SteamContentSdkEngine
    @Inject internal lateinit var workshopRepository: SteamUnifiedWorkshopRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.getBooleanExtra(CM_PARITY_PROBE_EXTRA, false)) {
            runCmParityProbe()
            return
        }
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        AppNavigationCommandBus.accept(intent)
        setContent {
            val viewModel: ShellViewModel = hiltViewModel()
            val preferences by viewModel.preferences.collectAsStateWithLifecycle()
            val steamSession by viewModel.steamSession.collectAsStateWithLifecycle()
            val loadedPreferences = preferences
            if (loadedPreferences == null) {
                StartupLoadingState(steamSession)
            } else {
                FormalWallHubApp(
                    preferences = loadedPreferences,
                    steamSession = steamSession,
                    onSetupWizardCompleted = { viewModel.setSetupWizardCompleted(true) },
                    onRetrySteamRestore = viewModel::restoreSteamSession,
                )
            }
        }
    }

    private fun runCmParityProbe() {
        lifecycleScope.launch {
            val startedAt = SystemClock.elapsedRealtime()
            steamSessionRepository.restorePersistedSession()
            for (attempt in 0 until PROBE_SESSION_ATTEMPTS) {
                if (steamSessionRepository.session.value.phase == SteamSessionPhase.SIGNED_IN) {
                    break
                }
                delay(PROBE_SESSION_DELAY_MS)
            }
            val query =
                WorkshopBrowseQuery(
                    page = 1,
                    pageSize = 30,
                    sort = WorkshopSort.MOST_RECENT,
                    ratings = emptySet<WorkshopRating>(),
                    allowNsfw = true,
                    language = 0,
                )
            runCatching {
                workshopRepository.invalidateDiscoverCache()
                val account = checkNotNull(workshopRepository.browsePublic(query)) { "Account CM search failed" }
                val anonymous = steamContentSdkEngine.diagnosticAnonymousPublicSearch()
                account to anonymous
            }.onSuccess { (account, anonymous) ->
                val accountIds = account.items.map { it.id }
                val anonymousIds = anonymous.rows.map { it.itemId }
                val orderEqual = accountIds == anonymousIds
                val setEqual = accountIds.toSet() == anonymousIds.toSet()
                val publicFieldsEqual =
                    account.items.map { it.id to it.title } == anonymous.rows.map { it.itemId to it.title }
                val accountFlags =
                    account.items.count {
                        it.subscriptionState != com.wallhub.android.core.model.SubscriptionState.UNKNOWN ||
                            it.favoriteState != com.wallhub.android.core.model.FavoriteState.UNKNOWN
                    }
                val anonymousFlags = anonymous.rows.count { it.subscribed != null || it.favourited != null }
                Log.i(
                    CM_PARITY_PROBE_TAG,
                    "complete elapsedMs=${SystemClock.elapsedRealtime() - startedAt} " +
                        "accountRows=${account.items.size} anonymousRows=${anonymous.rows.size} " +
                        "accountTotal=${account.totalCount} anonymousTotal=${anonymous.total} " +
                        "orderEqual=$orderEqual setEqual=$setEqual publicFieldsEqual=$publicFieldsEqual " +
                        "accountDigest=${accountIds.digest()} anonymousDigest=${anonymousIds.digest()} " +
                        "accountInteractionRows=$accountFlags anonymousInteractionRows=$anonymousFlags",
                )
            }.onFailure { error ->
                    val stage =
                        when (error.message) {
                            "Account CM session is not authenticated" -> "account_not_authenticated"
                            "Account CM engine is unavailable", "Published account CM engine unavailable" -> "account_engine_unavailable"
                            "Account CM search failed" -> "account_search"
                            "Anonymous CM engine creation failed" -> "anonymous_engine_create"
                            "Anonymous CM detail failed" -> "anonymous_detail"
                            "Anonymous CM session unavailable" -> "anonymous_session_open"
                            "Anonymous CM authentication timed out" -> "anonymous_auth_timeout"
                            else ->
                                if (error.message.orEmpty().startsWith("Anonymous CM search failed:")) {
                                    "anonymous_search_${error.message.orEmpty().substringAfterLast(':')}"
                                } else {
                                    "sdk_operation"
                                }
                        }
                    Log.e(
                        CM_PARITY_PROBE_TAG,
                        "failed stage=$stage type=${error.javaClass.simpleName} " +
                            "repositoryPhase=${steamSessionRepository.session.value.phase} " +
                            "sdkState=${steamContentSdkEngine.state()} " +
                            "elapsedMs=${SystemClock.elapsedRealtime() - startedAt}",
                    )
                }
            finish()
        }
    }

    private fun List<Long>.digest(): String =
        MessageDigest.getInstance("SHA-256")
            .digest(joinToString(",").toByteArray())
            .take(8)
            .joinToString("") { "%02x".format(it) }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(CM_PARITY_PROBE_EXTRA, false)) {
            runCmParityProbe()
            return
        }
        AppNavigationCommandBus.accept(intent)
    }

    private companion object {
        const val CM_PARITY_PROBE_EXTRA = "cm_parity_probe"
        const val CM_PARITY_PROBE_TAG = "WallHubCmParityProbe"
        const val PROBE_SESSION_ATTEMPTS = 250
        const val PROBE_SESSION_DELAY_MS = 100L
    }
}

@Composable
private fun StartupLoadingState(session: SteamSessionState) {
    WallHubTheme(preference = ThemePreference.SYSTEM) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    CircularProgressIndicator()
                    Text(
                        text = session.message ?: stringResource(R.string.app_starting),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}
