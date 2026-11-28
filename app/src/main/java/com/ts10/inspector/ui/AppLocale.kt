package com.ts10.inspector.ui

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ts10.inspector.settings.DashboardPreferences
import java.util.Locale

/** Idioma de la interfaz. AUTO sigue a Android y, si no está soportado, usa inglés. */
enum class AppLanguage(val tag: String?, val nativeName: String) {
    AUTO(null, "Auto"),
    ES("es", "Español"),
    EN("en", "English"),
    DE("de", "Deutsch"),
    FR("fr", "Français"),
    IT("it", "Italiano"),
    PT("pt", "Português"),
    NL("nl", "Nederlands"),
    PL("pl", "Polski"),
}

/**
 * Traducción fuera de Compose (estados, avisos) con el idioma elegido en los ajustes.
 * Los informes de diagnóstico se mantienen deliberadamente en español.
 */
internal object AppLocale {
    @Volatile private var cache: Pair<AppLanguage, Resources>? = null

    fun resources(context: Context): Resources {
        val app = context.applicationContext
        val language = DashboardPreferences.current(app).language
        cache?.takeIf { it.first == language }?.let { return it.second }
        return localizedContext(app, language).resources.also { cache = language to it }
    }

    fun string(context: Context, @StringRes id: Int, vararg args: Any): String =
        resources(context).getString(id, *args)

    fun localizedContext(base: Context, language: AppLanguage): Context {
        val tag = language.tag ?: return base
        val configuration = Configuration(base.resources.configuration).apply {
            setLocale(Locale.forLanguageTag(tag))
        }
        return base.createConfigurationContext(configuration)
    }
}

/**
 * Aplica el idioma elegido a todo el contenido Compose sin recrear actividades ni ventanas.
 * Solo se sustituyen los recursos: el contexto sigue siendo el original para abrir actividades,
 * servicios del sistema, etc.
 */
@Composable
internal fun LocalizedContent(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val baseConfiguration = LocalConfiguration.current
    val settings by DashboardPreferences.get(context).state.collectAsStateWithLifecycle()
    if (settings.language == AppLanguage.AUTO) {
        content()
        return
    }
    val localized = remember(context, baseConfiguration, settings.language) {
        val resources = AppLocale.localizedContext(context, settings.language).resources
        object : ContextWrapper(context) {
            override fun getResources(): Resources = resources
        }
    }
    CompositionLocalProvider(
        LocalContext provides localized,
        LocalConfiguration provides localized.resources.configuration,
        content = content,
    )
}
