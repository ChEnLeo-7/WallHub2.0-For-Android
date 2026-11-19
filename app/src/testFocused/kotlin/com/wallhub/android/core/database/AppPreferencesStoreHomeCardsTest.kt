package com.wallhub.android.core.database

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import com.wallhub.android.core.model.HomeCardAction
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
class AppPreferencesStoreHomeCardsTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `legacy mobile columns remain the fallback until new preference is chosen`() =
        runTest {
            val file = File(temporaryFolder.root, "legacy-home-columns.preferences_pb")
            val dataStore = PreferenceDataStoreFactory.create(scope = backgroundScope, produceFile = { file })
            dataStore.edit { preferences ->
                preferences[intPreferencesKey("home_columns")] = 3
            }

            assertEquals(3, AppPreferencesStore(dataStore).preferences.first().homeColumns)
        }

    @Test
    fun `automatic cards per row survives grouped Home preference writes`() =
        runTest {
            val store = newStore("home-cards-automatic.preferences_pb")

            store.setHomePreferences(
                pageSize = 50,
                columns = 0,
                multiSelect = false,
                cardAction = HomeCardAction.OPEN_STEAM,
                matureContentEnabled = false,
            )

            val restored = store.preferences.first()
            assertEquals(0, restored.homeColumns)
            assertEquals(50, restored.homePageSize)
            assertEquals(false, restored.homeFilterMultiSelect)
            assertEquals(HomeCardAction.OPEN_STEAM, restored.homeCardAction)
        }

    @Test
    fun `cards per row supports eight and persists independently`() =
        runTest {
            val store = newStore("home-cards-per-row.preferences_pb")

            store.setHomePreferences(
                pageSize = 30,
                columns = 8,
                multiSelect = true,
                cardAction = HomeCardAction.DOWNLOAD,
                matureContentEnabled = false,
            )

            assertEquals(8, store.preferences.first().homeColumns)
        }

    @Test
    fun `Home top information defaults off and persists independently`() =
        runTest {
            val store = newStore("home-top-info.preferences_pb")

            assertEquals(false, store.preferences.first().showHomeTopInfo)

            store.setHomeTopInfoVisible(true)

            assertEquals(true, store.preferences.first().showHomeTopInfo)
        }

    private fun TestScope.newStore(fileName: String): AppPreferencesStore {
        val file = File(temporaryFolder.root, fileName)
        val dataStore = PreferenceDataStoreFactory.create(scope = backgroundScope, produceFile = { file })
        return AppPreferencesStore(dataStore)
    }
}
