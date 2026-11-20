package com.ts10.inspector.dashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedLimitSignTest {
    @Test
    fun `three digit limits remain legible`() {
        assertEquals(16, speedLimitFontSizeDesignSp(100))
        assertEquals(16, speedLimitFontSizeDesignSp(120))
        assertTrue(speedLimitFontSizeDesignSp(100) > 12)
    }

    @Test
    fun `two digit limits keep their larger size`() {
        assertEquals(18, speedLimitFontSizeDesignSp(30))
        assertEquals(18, speedLimitFontSizeDesignSp(90))
    }
}
