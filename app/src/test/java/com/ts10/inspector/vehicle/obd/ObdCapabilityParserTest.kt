package com.ts10.inspector.vehicle.obd

import com.ts10.inspector.R
import com.ts10.inspector.ui.UiText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ObdCapabilityParserTest {
    // Los resúmenes son UiText: se comprueban el recurso y los datos, no el texto traducido.
    private fun ObdCapabilityResult.res() = summary as UiText.Res

    @Test
    fun `decodes supported pid bitmap`() {
        val result = summarizeObdCapability("0100", R.string.obdcap_pids_01, "41 00 BE 3E B8 13\r")

        assertTrue(result.available)
        assertEquals(R.string.obdcap_pids, result.res().id)
        val pids = result.res().args[1].toString()
        assertTrue(pids.contains("0101"))
        assertTrue(pids.contains("010C"))
        assertTrue(pids.contains("0120"))
    }

    @Test
    fun `decodes mil dtc count and monitor bytes`() {
        val result = summarizeObdCapability("0101", R.string.obdcap_monitors, "41 01 82 07 65 04\r")

        assertEquals(R.string.obdcap_monitor_summary, result.res().id)
        assertEquals(UiText.of(R.string.obdcap_mil_on), result.res().args[0])
        assertEquals(2, result.res().args[1])
        assertEquals("07 65 04", result.res().args[2])
    }

    @Test
    fun `reassembles vin from mode 09 lines`() {
        val response = """
            49 02 01 57 30 4C 30 41 48
            49 02 02 4C 34 38 35 32 30
            49 02 03 30 30 31 32 33
        """.trimIndent()

        val result = summarizeObdCapability("0902", R.string.obdcap_vin, response)

        assertEquals(UiText.Raw("VIN: W0L0AHL4852000123"), result.summary)
    }

    @Test
    fun `decodes pending trouble codes without writing`() {
        val result = summarizeObdCapability("07", R.string.obdcap_dtc_pending, "47 01 33 00 00\r")

        assertEquals(R.string.obdcap_codes, result.res().id)
        assertTrue(result.res().args[1].toString().contains("P0133"))
    }

    @Test
    fun `reports no data as unavailable`() {
        val result = summarizeObdCapability("0A", R.string.obdcap_dtc_permanent, "NO DATA\r")

        assertFalse(result.available)
        assertEquals(R.string.obdcap_unavailable, result.res().id)
    }
}
