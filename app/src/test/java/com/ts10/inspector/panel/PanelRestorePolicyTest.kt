package com.ts10.inspector.panel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PanelRestorePolicyTest {
    @Test fun `last manual application wins over navigation fallback`() {
        assertEquals("com.spotify.music", selectPanelRestoreTarget("com.spotify.music", "com.waze"))
        assertEquals("com.waze", selectPanelRestoreTarget(null, "com.waze"))
    }

    @Test fun `external fullscreen task blocks automatic panel restoration`() {
        assertTrue(shouldBlockPanelRestore("com.spotify.music", "com.ts10.inspector", 1))
        assertFalse(shouldBlockPanelRestore("com.spotify.music", "com.ts10.inspector", 5))
        assertFalse(shouldBlockPanelRestore("com.ts10.inspector", "com.ts10.inspector", 1))
        assertFalse(shouldBlockPanelRestore("com.android.systemui", "com.ts10.inspector", 1))
    }
}
