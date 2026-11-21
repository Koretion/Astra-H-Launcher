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
    /** 10,5 unidades = 16 px en la TS10 de 1280 x 720 (escala 720 / 469). */
    const val RightPanelModalMargin = 10.5f
    const val RightPanelModalLeft = VehiclePanelWidth + RightPanelModalMargin
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

/**
 * Marco común de las ventanas sobre la zona derecha (clima, Aplicaciones, Teléfono, conductor,
 * fuentes multimedia): todas del mismo tamaño, el máximo con el mismo margen a los cuatro lados
 * para que se vea la aplicación de detrás. Ajustes es la excepción: ocupa la zona entera.
 *
 * [windowWidth] y [windowHeight] son el tamaño real de la ventana en unidades de diseño: la
 * ventana empieza bajo la barra de estado y la pantalla suele ser más ancha que [DesignGrid.Width]
 * (1280 x 720 son 834 unidades), así que no se pueden deducir de la cuadrícula.
 */
internal fun rightPanelModalFrame(windowWidth: Float, windowHeight: Float): RightPanelModalFrame = RightPanelModalFrame(
    x = DesignGrid.RightPanelModalLeft,
    y = DesignGrid.RightPanelModalMargin,
    width = windowWidth - DesignGrid.RightPanelModalLeft - DesignGrid.RightPanelModalMargin,
    height = windowHeight - DesignGrid.RightPanelModalMargin * 2f,
)

internal fun modalCornerShape(layout: LauncherLayout) =
    RoundedCornerShape(layout.dp(18))
