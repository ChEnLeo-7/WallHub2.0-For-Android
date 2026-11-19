package com.wallhub.android.data.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.wallhub.android.core.database.AppPreferencesStore
import com.wallhub.android.core.model.ThemeContrastLevel
import com.wallhub.android.core.model.ThemeSchemeStyle
import com.wallhub.android.data.security.EncryptedStringReadResult
import com.wallhub.android.data.security.EncryptedStringStore
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class DataStoreSettingsRepositoryThemeOptionsTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `pure black preference persists through settings repository`() =
        runTest {
            val store = newStore("pure-black.preferences_pb")
            val repository =
                DataStoreSettingsRepository(
                    store = store,
                    steamApiCredentialRepository = newSteamApiCredentialRepository(),
                )

            repository.setPureBlackInDark(true)

            assertEquals(true, repository.preferences.first().pureBlackInDark)
        }

    @Test
    fun `scheme style and contrast level persist through settings repository`() =
        runTest {
            val store = newStore("theme-options.preferences_pb")
            val repository =
                DataStoreSettingsRepository(
                    store = store,
                    steamApiCredentialRepository = newSteamApiCredentialRepository(),
                )

            repository.setSchemeStyle(ThemeSchemeStyle.VIBRANT)
            repository.setContrastLevel(ThemeContrastLevel.HIGH)

            val restored = repository.preferences.first()
            assertEquals(ThemeSchemeStyle.VIBRANT, restored.schemeStyle)
            assertEquals(ThemeContrastLevel.HIGH, restored.contrastLevel)
        }

    private fun newSteamApiCredentialRepository(): SteamApiCredentialRepository =
        SteamApiCredentialRepository(
            secureStore =
                object : EncryptedStringStore {
                    override fun read(): EncryptedStringReadResult = EncryptedStringReadResult.Missing

                    override fun write(value: String) = Unit

                    override fun clear() = Unit
                },
            legacyStore =
                object : LegacySteamApiKeyStore {
                    override suspend fun read(): String = ""

                    override suspend fun clear() = Unit
                },
        )

    private fun TestScope.newStore(fileName: String): AppPreferencesStore {
        val file = File(temporaryFolder.root, fileName)
        val dataStore = PreferenceDataStoreFactory.create(scope = backgroundScope, produceFile = { file })
        return AppPreferencesStore(dataStore)
    }
}
