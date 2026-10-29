package com.hamdi.tiktokcleaner

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

object LocaleHelper {
    fun wrap(context: Context): Context {
        val lang = Prefs.getLanguage(context)
        val cleanTag = when {
            lang.startsWith("ar", ignoreCase = true) -> "ar"
            lang.startsWith("en", ignoreCase = true) -> "en"
            else -> "ar"
        }
        val locale = Locale.forLanguageTag(cleanTag)
        Locale.setDefault(locale)
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        return context.createConfigurationContext(config)
    }
}
