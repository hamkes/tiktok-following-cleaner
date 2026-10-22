package com.hamdi.tiktokcleaner

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.os.LocaleList
import java.util.Locale

/**
 * v1.7: tiny locale applier. Reads the persisted UI language ("uiLang") from
 * the SAME SharedPreferences file the rest of the app uses ("cleaner_prefs"),
 * WITHOUT touching Prefs.kt (per the v1.7 delivery constraint). No AndroidX;
 * uses ContextWrapper + Configuration + LocaleList (API 26+).
 *
 * Persisted keys:
 *   uiLang : "ar" (default) or "en"
 */
object LocaleHelper {

    const val PREFS_FILE = "cleaner_prefs"
    const val KEY_UI_LANG = "uiLang"
    const val LANG_AR = "ar"
    const val LANG_EN = "en"
    val SUPPORTED = listOf(LANG_AR, LANG_EN)
    const val DEFAULT_LANG = LANG_AR

    fun readLang(context: Context): String {
        val sp: SharedPreferences =
            context.applicationContext.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
        val raw = sp.getString(KEY_UI_LANG, DEFAULT_LANG) ?: DEFAULT_LANG
        return if (raw in SUPPORTED) raw else DEFAULT_LANG
    }

    fun writeLang(context: Context, lang: String) {
        if (lang !in SUPPORTED) return
        context.applicationContext
            .getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_UI_LANG, lang)
            .apply()
    }

    /** Wrap [base] with a context whose Configuration carries the chosen locale. */
    fun wrap(base: Context): Context {
        val lang = readLang(base)
        val locale = Locale(lang)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        @Suppress("DEPRECATION")
        config.setLocales(LocaleList(locale))
        return base.createConfigurationContext(config)
    }
}
