package com.ts10.inspector.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QfMediaMetadataFilterTest {
    @Test
    fun firstStationNameIsShownImmediately() {
        val filter = QfRadioStationNameFilter()

        assertEquals("LOS40 CL", filter.update(band = 0, frequency = "88.50", rawName = "LOS40 CL"))
    }

    @Test
    fun isolatedCorruptedNameDoesNotReplaceStableStation() {
        val filter = QfRadioStationNameFilter()
        filter.update(band = 0, frequency = "88.50", rawName = "LOS40 CL")

        assertEquals("LOS40 CL", filter.update(band = 0, frequency = "88.50", rawName = "LOSr0 Cz"))
        assertEquals("LOS40 CL", filter.update(band = 0, frequency = "88.50", rawName = "LOS40 CL"))
    }

    @Test
    fun repeatedNameChangeBecomesStable() {
        val filter = QfRadioStationNameFilter()
        filter.update(band = 0, frequency = "88.50", rawName = "LOS40 CL")

        assertEquals("LOS40 CL", filter.update(band = 0, frequency = "88.50", rawName = "RADIO 3"))
        assertEquals("RADIO 3", filter.update(band = 0, frequency = "88.50", rawName = "RADIO 3"))
    }

    @Test
    fun frequencyChangeAcceptsItsNameImmediately() {
        val filter = QfRadioStationNameFilter()
        filter.update(band = 0, frequency = "88.50", rawName = "LOS40 CL")

        assertEquals("RNE 3", filter.update(band = 0, frequency = "93.20", rawName = "RNE 3"))
    }

    @Test
    fun unavailableBluetoothMetadataIsTreatedAsEmpty() {
        assertNull(normalizeQfMetadata("Not Provided"))
        assertNull(normalizeQfMetadata("  not provided  "))
        assertNull(normalizeQfMetadata(" \u0000 "))
        assertEquals("Song title", normalizeQfMetadata("  Song   title  "))
    }
}
