package com.ts10.inspector.panel

import com.ts10.inspector.settings.DashboardSettings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalApplicationRoutingTest {
    private val settings = DashboardSettings(
        navigationPackage = "navigation",
        panelPackages = listOf("panel"),
        mediaPackage = "spotify",
        mediaPackages = listOf("spotify", "youtube.music"),
    )

    @Test
    fun `navigation panel and media applications open in content area`() {
        assertTrue(shouldLaunchInContentArea(settings, "navigation"))
        assertTrue(shouldLaunchInContentArea(settings, "panel"))
        assertTrue(shouldLaunchInContentArea(settings, "spotify"))
        assertTrue(shouldLaunchInContentArea(settings, "youtube.music"))
    }

    @Test
    fun `unconfigured application remains full screen`() {
        assertFalse(shouldLaunchInContentArea(settings, "other"))
    }

    @Test
    fun `user action invalidates previously scheduled startup work`() {
        val startupToken = LauncherStartupCoordinator.token()

        LauncherStartupCoordinator.userTookControl()

        assertFalse(LauncherStartupCoordinator.isCurrent(startupToken))
    }

    @Test
    fun `empty panel restores default navigation`() {
        assertTrue(shouldRestoreDefaultNavigation(listOf(null, 1, 3)))
        assertTrue(shouldRestoreDefaultNavigation(emptyList()))
    }

    @Test
    fun `visible configured panel task is preserved`() {
        assertFalse(shouldRestoreDefaultNavigation(listOf(2, 0, null)))
    }
}
