package com.ts10.inspector.ui

import androidx.compose.runtime.Immutable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Coordinates in the original 800 x 469 launcher design. */
internal object DesignGrid {
    const val Width = 800f
    const val Height = 469f
    const val VehiclePanelWidth = 308f
    const val SystemBarHeight = 31f
    const val CoolantIndicatorWidth = 220f
    /** 47 design units scale to the measured 72 px QF Android bar on the 1280 x 720 unit. */
    const val VehicleTopIndicatorHeight = 47f
    const val RightPanelWidth = Width - VehiclePanelWidth
    const val RightPanelModalMargin = 12f
    const val RightPanelModalTop = SystemBarHeight + RightPanelModalMargin
    const val RightPanelModalLeft = VehiclePanelWidth + RightPanelModalMargin
    const val RightPanelModalMaxWidth = RightPanelWidth - RightPanelModalMargin * 2f
    const val RightPanelModalMaxHeight = Height - RightPanelModalTop - RightPanelModalMargin
}

@Immutable
internal data class LauncherLayout(
    val scale: Float,
    /** Text-only multiplier for dense areas such as the settings panel. */
    val textScale: Float = 1f,
) {
    fun dp(value: Number): Dp = (value.toFloat() * scale).dp
    fun sp(value: Number): TextUnit = (value.toFloat() * scale * textScale).sp
}

internal data class RightPanelModalFrame(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
)

internal fun rightPanelModalFrame(
    width: Float = DesignGrid.RightPanelModalMaxWidth,
    height: Float = DesignGrid.RightPanelModalMaxHeight,
    top: Float = DesignGrid.RightPanelModalTop,
): RightPanelModalFrame {
    val safeWidth = width.coerceAtMost(DesignGrid.RightPanelModalMaxWidth)
    val safeHeight = height.coerceAtMost(DesignGrid.RightPanelModalMaxHeight)
    val x = DesignGrid.RightPanelModalLeft +
        (DesignGrid.RightPanelModalMaxWidth - safeWidth) / 2f
    val y = top.coerceIn(
        DesignGrid.RightPanelModalTop,
        DesignGrid.Height - DesignGrid.RightPanelModalMargin - safeHeight,
    )
    return RightPanelModalFrame(x = x, y = y, width = safeWidth, height = safeHeight)
}

internal fun modalCornerShape(layout: LauncherLayout) =
    RoundedCornerShape(layout.dp(18))
