package com.wallhub.android.core.database

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.wallhub.android.core.model.AccentPreference
import com.wallhub.android.core.model.ThemePreference
import com.wallhub.android.core.model.VisualStyle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class AppPreferencesStoreVisualStyleTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `legacy store without a style key resolves to classic`() =
        runTest {
            val store = newStore("legacy.preferences_pb")

            assertEquals(VisualStyle.MATERIAL3, store.preferences.first().visualStyle)
        }

    @Test
    fun `legacy store disables Home bottom bar auto hide`() =
        runTest {
            val store = newStore("legacy-bottom-bar.preferences_pb")

            assertEquals(false, store.preferences.first().homeBottomBarAutoHide)
        }

    @Test
    fun `Home bottom bar auto hide round trips independently of visual style`() =
        runTest {
            val store = newStore("bottom-bar-roundtrip.preferences_pb")

            store.setHomeBottomBarAutoHide(true)
            store.setVisualStyle(VisualStyle.MATERIAL3)

            val restored = store.preferences.first()
            assertEquals(true, restored.homeBottomBarAutoHide)
            assertEquals(VisualStyle.MATERIAL3, restored.visualStyle)
        }

    @Test
    fun `unknown persisted style resolves to classic`() =
        runTest {
            val file = File(temporaryFolder.root, "unknown.preferences_pb")
            val dataStore = PreferenceDataStoreFactory.create(scope = backgroundScope, produceFile = { file })
            dataStore.edit { preferences ->
                preferences[stringPreferencesKey("visual_style")] = "future_style"
            }
            val store = AppPreferencesStore(dataStore)

            assertEquals(VisualStyle.MATERIAL3, store.preferences.first().visualStyle)
        }

    @Test
    fun `selected style persists without changing theme palette or accent`() =
        runTest {
            val store = newStore("roundtrip.preferences_pb")
            store.setTheme(ThemePreference.DARK)
            store.setAccent(AccentPreference.ROSE, customColor = null)
            store.setSystemMonetEnabled(false)

            store.setVisualStyle(VisualStyle.MATERIAL3_EXPRESSIVE)

            val restored = store.preferences.first()
            assertEquals(VisualStyle.MATERIAL3_EXPRESSIVE, restored.visualStyle)
            assertEquals(ThemePreference.DARK, restored.theme)
            assertEquals(AccentPreference.ROSE, restored.accent)
            assertEquals(false, restored.useSystemMonet)
        }

    private fun TestScope.newStore(fileName: String): AppPreferencesStore {
        val file = File(temporaryFolder.root, fileName)
        val dataStore = PreferenceDataStoreFactory.create(scope = backgroundScope, produceFile = { file })
        return AppPreferencesStore(dataStore)
    }
}

