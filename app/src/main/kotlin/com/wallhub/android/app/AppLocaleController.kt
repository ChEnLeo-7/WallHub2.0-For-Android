package com.wallhub.android.app

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.wallhub.android.core.model.AppLanguage
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppLocaleController
    @Inject
    constructor() {
        fun current(): AppLanguage = AppCompatDelegate.getApplicationLocales().toAppLanguage()

        fun set(language: AppLanguage) {
            val locales = language.toLocaleList()
            if (locales.toLanguageTags() != AppCompatDelegate.getApplicationLocales().toLanguageTags()) {
                AppCompatDelegate.setApplicationLocales(locales)
            }
        }
    }

internal fun AppLanguage.toLocaleList(): LocaleListCompat =
    when (this) {
        AppLanguage.SYSTEM -> LocaleListCompat.getEmptyLocaleList()
        AppLanguage.SIMPLIFIED_CHINESE -> LocaleListCompat.forLanguageTags("zh-CN")
        AppLanguage.ENGLISH -> LocaleListCompat.forLanguageTags("en")
    }

internal fun LocaleListCompat.toAppLanguage(): AppLanguage =
    when (get(0)?.language) {
        "zh" -> AppLanguage.SIMPLIFIED_CHINESE
        "en" -> AppLanguage.ENGLISH
        else -> AppLanguage.SYSTEM
    }
