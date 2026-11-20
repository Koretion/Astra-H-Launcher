package com.ts10.inspector.launcher

/** Visibility of the launcher-owned chrome while another task is in front. */
internal enum class LauncherChromeMode {
    FULL,
    TOP_BAR,
    HIDDEN,
}

internal enum class LauncherChromeEvent {
    SHOW_HOME,
    OPEN_EXPANDED_APP,
    OPEN_EXCLUSIVE_APP,
    OPEN_RECENTS,
}

internal fun reduceLauncherChrome(
    event: LauncherChromeEvent,
): LauncherChromeMode = when (event) {
    LauncherChromeEvent.SHOW_HOME -> LauncherChromeMode.FULL
    LauncherChromeEvent.OPEN_EXPANDED_APP -> LauncherChromeMode.TOP_BAR
    LauncherChromeEvent.OPEN_EXCLUSIVE_APP,
    LauncherChromeEvent.OPEN_RECENTS,
    -> LauncherChromeMode.HIDDEN
}
