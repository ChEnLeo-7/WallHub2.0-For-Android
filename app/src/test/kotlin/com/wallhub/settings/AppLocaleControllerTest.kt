package com.wallhub.settings

import androidx.core.os.LocaleListCompat
import com.wallhub.android.app.toAppLanguage
import com.wallhub.android.app.toLocaleList
import com.wallhub.android.core.model.AppLanguage
import kotlin.test.Test
import kotlin.test.assertEquals

class AppLocaleControllerTest {
    @Test
    fun `language choices map to stable application locale tags`() {
        assertEquals("", AppLanguage.SYSTEM.toLocaleList().toLanguageTags())
        assertEquals("zh-CN", AppLanguage.SIMPLIFIED_CHINESE.toLocaleList().toLanguageTags())
        assertEquals("en", AppLanguage.ENGLISH.toLocaleList().toLanguageTags())
    }

    @Test
    fun `application locales map back to supported language choices`() {
        assertEquals(AppLanguage.SYSTEM, LocaleListCompat.getEmptyLocaleList().toAppLanguage())
        assertEquals(AppLanguage.SIMPLIFIED_CHINESE, LocaleListCompat.forLanguageTags("zh-Hans").toAppLanguage())
        assertEquals(AppLanguage.SIMPLIFIED_CHINESE, LocaleListCompat.forLanguageTags("zh-CN").toAppLanguage())
        assertEquals(AppLanguage.ENGLISH, LocaleListCompat.forLanguageTags("en-US").toAppLanguage())
        assertEquals(AppLanguage.SYSTEM, LocaleListCompat.forLanguageTags("fr-FR").toAppLanguage())
    }
}
