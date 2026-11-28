package com.ts10.inspector.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.window.Dialog
import com.ts10.inspector.R
import com.ts10.inspector.ui.LauncherLayout
import com.ts10.inspector.ui.LocalDashboardColors

/** Un círculo de la fila: [color] null pinta el arcoíris que abre la paleta. */
internal data class ColorSwatch(val key: String, val color: Color?, val label: String)

private val RainbowColors = listOf(
    Color(0xFFFF3B30), Color(0xFFFFCC00), Color(0xFF34C759), Color(0xFF00C7BE),
    Color(0xFF007AFF), Color(0xFFAF52DE), Color(0xFFFF3B30),
)

/**
 * Fila de círculos de color como los ajustes de Tesla. El último círculo (arcoíris) abre una paleta;
 * [customArgb] es el color que la paleta muestra al abrirse.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ColorSwatchRow(
    title: String,
    swatches: List<ColorSwatch>,
    selectedKey: String,
    customArgb: Int,
    onSwatch: (ColorSwatch) -> Unit,
    onCustom: (Int) -> Unit,
    layout: LauncherLayout,
) {
    val colors = LocalDashboardColors.current
    var paletteOpen by remember { mutableStateOf(false) }
    val selectedLabel = swatches.firstOrNull { it.key == selectedKey }?.label.orEmpty()
    Column(modifier = Modifier.padding(vertical = layout.dp(6))) {
        Text(title, color = colors.primary, fontSize = layout.sp(10), fontWeight = FontWeight.Bold)
        Text(selectedLabel, color = colors.muted, fontSize = layout.sp(8))
        FlowRow(
            modifier = Modifier.padding(top = layout.dp(5)),
            horizontalArrangement = Arrangement.spacedBy(layout.dp(7)),
            verticalArrangement = Arrangement.spacedBy(layout.dp(6)),
        ) {
            swatches.forEach { swatch ->
                val selected = swatch.key == selectedKey
                val fill = swatch.color
                Box(
                    modifier = Modifier
                        .size(layout.dp(26))
                        .border(layout.dp(if (selected) 2f else 0.6f), if (selected) colors.primary else colors.muted.copy(alpha = .35f), CircleShape)
                        .padding(layout.dp(if (selected) 3f else 1f))
                        .clip(CircleShape)
                        .then(
                            if (fill != null) Modifier.background(fill)
                            else Modifier.background(Brush.sweepGradient(RainbowColors)),
                        )
                        .clickable { if (fill == null) paletteOpen = true else onSwatch(swatch) },
                )
            }
        }
    }
    if (paletteOpen) {
        ColorPaletteDialog(customArgb, layout, onDismiss = { paletteOpen = false }) {
            paletteOpen = false
            onCustom(it)
        }
    }
}

/** Paleta sencilla: tono, saturación y brillo con barras arrastrables y vista previa. */
@Composable
private fun ColorPaletteDialog(initialArgb: Int, layout: LauncherLayout, onDismiss: () -> Unit, onPicked: (Int) -> Unit) {
    val colors = LocalDashboardColors.current
    val hsv = remember(initialArgb) { FloatArray(3).also { android.graphics.Color.colorToHSV(initialArgb, it) } }
    var hue by remember { mutableFloatStateOf(hsv[0]) }
    var saturation by remember { mutableFloatStateOf(hsv[1]) }
    var value by remember { mutableFloatStateOf(hsv[2]) }
    val current = Color.hsv(hue, saturation, value)
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .background(if (colors.dark) Color(0xFF1C1E21) else Color.White, RoundedCornerShape(layout.dp(12)))
                .padding(layout.dp(14)),
            verticalArrangement = Arrangement.spacedBy(layout.dp(10)),
        ) {
            Text(stringResource(R.string.color_palette), color = colors.primary, fontSize = layout.sp(12), fontWeight = FontWeight.Bold)
            Box(Modifier.fillMaxWidth().height(layout.dp(34)).background(current, RoundedCornerShape(layout.dp(8))))
            PaletteBar(
                brush = Brush.horizontalGradient((0..6).map { Color.hsv(it * 60f % 360f, 1f, 1f) }),
                fraction = hue / 360f,
                layout = layout,
            ) { hue = (it * 360f).coerceIn(0f, 359.9f) }
            PaletteBar(
                brush = Brush.horizontalGradient(listOf(Color.hsv(hue, 0f, value), Color.hsv(hue, 1f, value))),
                fraction = saturation,
                layout = layout,
            ) { saturation = it }
            PaletteBar(
                brush = Brush.horizontalGradient(listOf(Color.Black, Color.hsv(hue, saturation, 1f))),
                fraction = value,
                layout = layout,
            ) { value = it }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                DiagnosticAction(stringResource(R.string.common_cancel_caps), colors.muted, layout, onDismiss)
                Box(Modifier.size(layout.dp(8)))
                DiagnosticAction(stringResource(R.string.color_use), Color(0xFF2E9D50), layout) { onPicked(current.toArgb()) }
            }
        }
    }
}

@Composable
private fun PaletteBar(brush: Brush, fraction: Float, layout: LauncherLayout, onChange: (Float) -> Unit) {
    val colors = LocalDashboardColors.current
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(layout.dp(26))
            .clip(RoundedCornerShape(layout.dp(13)))
            .pointerInput(Unit) { detectTapGestures { onChange((it.x / size.width).coerceIn(0f, 1f)) } }
            .pointerInput(Unit) {
                detectDragGestures { change, _ -> onChange((change.position.x / size.width).coerceIn(0f, 1f)) }
            },
    ) {
        drawRect(brush)
        val x = fraction.coerceIn(0f, 1f) * size.width
        drawCircle(Color.White, radius = size.height * 0.42f, center = Offset(x, size.height / 2f))
        drawCircle(colors.muted, radius = size.height * 0.42f, center = Offset(x, size.height / 2f), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f))
    }
}
