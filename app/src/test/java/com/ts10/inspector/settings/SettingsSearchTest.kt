package com.ts10.inspector.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsSearchTest {
    private val targets = listOf(
        SettingsSearchTarget("section:SYSTEM", "Sistema", "Sistema", listOf("LOG Y DIAGNÓSTICO", "Actualizaciones")),
        SettingsSearchTarget(
            "detail:DIAGNOSTICS",
            "Log y diagnóstico",
            "Sistema › Log y diagnóstico",
            listOf("GUARDAR", "Eventos recientes (%1\$d)"),
        ),
        SettingsSearchTarget("section:DISPLAY", "Pantalla", "Pantalla", listOf("Tema", "Zoom en marcha")),
    )

    @Test
    fun `log finds every screen that shows it, title matches first`() {
        val hits = searchSettings("log", targets)
        assertEquals(listOf("detail:DIAGNOSTICS", "section:SYSTEM"), hits.map { it.target.id })
        assertEquals(listOf("LOG Y DIAGNÓSTICO"), hits[1].matches)
    }

    @Test
    fun `accents and case are ignored and every word must match`() {
        assertEquals("detail:DIAGNOSTICS", searchSettings("DIAGNOSTICO log", targets).first().target.id)
        assertTrue(searchSettings("log tema", targets).isEmpty())
    }

    @Test
    fun `format placeholders are neither matched nor shown`() {
        assertTrue(searchSettings("1\$d", targets).isEmpty())
        assertEquals(listOf("Eventos recientes ()"), searchSettings("recientes", targets).single().matches)
    }
}
