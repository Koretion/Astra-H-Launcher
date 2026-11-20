package com.ts10.inspector.ui

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * Texto que se guarda en el estado de un cliente y se traduce al mostrarlo. Así cambia en vivo con
 * el idioma y los informes de diagnóstico pueden resolverlo en español.
 */
sealed interface UiText {
    data class Res(@StringRes val id: Int, val args: List<Any> = emptyList()) : UiText
    data class Raw(val text: String) : UiText

    fun resolve(context: Context): String = when (this) {
        is Raw -> text
        is Res -> context.resources.getString(id, *args.map { if (it is UiText) it.resolve(context) else it }.toTypedArray())
    }

    /** Siempre en inglés: idioma de los registros e informes de diagnóstico. */
    fun english(context: Context): String = resolve(AppLocale.localizedContext(context, AppLanguage.EN))

    companion object {
        fun of(@StringRes id: Int, vararg args: Any): UiText = Res(id, args.toList())
    }
}

@Composable
fun UiText.text(): String = resolve(LocalContext.current)

/** Excepción cuyo mensaje se puede mostrar traducido. */
class LocalizedException(val text: UiText) : IllegalStateException((text as? UiText.Raw)?.text ?: text.toString())

/** Lanza un error traducible; sustituye a `error("...")`. */
fun fail(@StringRes id: Int, vararg args: Any): Nothing = throw LocalizedException(UiText.of(id, *args))

/** Mensaje traducible de cualquier error; los que no son [LocalizedException] se muestran tal cual. */
fun Throwable.uiText(): UiText = (this as? LocalizedException)?.text
    ?: UiText.Raw(message ?: javaClass.simpleName)
