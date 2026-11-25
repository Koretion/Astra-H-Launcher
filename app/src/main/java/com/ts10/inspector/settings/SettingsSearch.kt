package com.ts10.inspector.settings

import java.text.Normalizer
import java.util.Locale

/** A settings screen the search can open, with the localized texts it shows. */
internal data class SettingsSearchTarget(
    val id: String,
    val title: String,
    /** "Section › Detail" as shown in the results. */
    val path: String,
    val texts: List<String>,
)

internal data class SettingsSearchHit(val target: SettingsSearchTarget, val matches: List<String>)

private val PLACEHOLDER = Regex("%(\\d+\\$)?[sdf]|%%")
private val DIACRITICS = Regex("\\p{Mn}+")
private val SPACES = Regex("\\s+")
private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")

/** Lower case, without accents or format placeholders: "Diagnóstico" matches "diagnostico". */
internal fun normalizeForSettingsSearch(text: String): String =
    Normalizer.normalize(text.replace(PLACEHOLDER, " "), Normalizer.Form.NFD)
        .replace(DIACRITICS, "")
        .lowercase(Locale.ROOT)
        .replace(SPACES, " ")
        .trim()

/** Display form of a resource text: placeholders removed, spacing tidied. */
internal fun displaySettingsText(text: String): String = text.replace(PLACEHOLDER, "").replace(SPACES, " ").trim()

/**
 * Every word of [query] must appear in the screen title or in one of its texts. Screens whose
 * title matches come first, then the ones with more matching texts.
 */
internal fun searchSettings(query: String, targets: List<SettingsSearchTarget>): List<SettingsSearchHit> {
    val words = normalizeForSettingsSearch(query).split(' ').filter { it.isNotEmpty() }
    if (words.isEmpty()) return emptyList()
    // Word starts, not substrings: "diagnos" finds "diagnóstico" but "tema" does not find "sistema".
    fun matches(text: String): Boolean {
        val textWords = normalizeForSettingsSearch(text).split(NON_WORD)
        return words.all { word -> textWords.any { it.startsWith(word) } }
    }
    return targets.mapNotNull { target ->
        val titleMatch = matches(target.path)
        val texts = target.texts.filter(::matches).map(::displaySettingsText).filter { it.isNotEmpty() }.distinct()
        if (!titleMatch && texts.isEmpty()) null else Triple(target, texts, titleMatch)
    }
        .sortedWith(compareByDescending<Triple<SettingsSearchTarget, List<String>, Boolean>> { it.third }.thenByDescending { it.second.size })
        .map { (target, texts, _) -> SettingsSearchHit(target, texts) }
}
