package com.ts10.inspector.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

class LauncherChromeModeTest {
    @Test
    fun expandedApplicationsKeepTheTopBar() {
        assertEquals(
            LauncherChromeMode.TOP_BAR,
            reduceLauncherChrome(
                LauncherChromeEvent.OPEN_EXPANDED_APP,
            ),
        )
    }

    @Test
    fun exclusiveApplicationsAndRecentsHideAllChrome() {
        assertEquals(
            LauncherChromeMode.HIDDEN,
            reduceLauncherChrome(
                LauncherChromeEvent.OPEN_EXCLUSIVE_APP,
            ),
        )
        assertEquals(
            LauncherChromeMode.HIDDEN,
            reduceLauncherChrome(
                LauncherChromeEvent.OPEN_RECENTS,
            ),
        )
    }

    @Test
    fun homeAlwaysRestoresTheCompleteLauncher() {
        assertEquals(
            LauncherChromeMode.FULL,
            reduceLauncherChrome(LauncherChromeEvent.SHOW_HOME),
        )
    }
}
