package com.ts10.inspector.dashboard

import androidx.compose.foundation.layout.size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.ts10.inspector.vehicle.tires.TirePosition

internal fun DrawScope.drawTireVehicleDiagram(
    tireColors: Map<TirePosition, Color>,
    engineColor: Color,
    fuelColor: Color,
    fuelFraction: Float?,
    serviceBrakePressed: Boolean,
    handbrakeEngaged: Boolean,
    outlineColor: Color,
) {
    val blue = Color(0xFF279BE8)
    val green = Color(0xFF31C967)
    val red = Color(0xFFE33B43)
    val body = Path().apply {
        moveTo(118f, 4f)
        cubicTo(105f, 6f, 99f, 15f, 98f, 29f)
        lineTo(94f, 91f)
        cubicTo(96f, 106f, 106f, 113f, 121f, 114f)
        lineTo(149f, 114f)
        cubicTo(164f, 113f, 174f, 106f, 176f, 91f)
        lineTo(172f, 29f)
        cubicTo(171f, 15f, 165f, 6f, 152f, 4f)
        close()
    }
    drawPath(body, outlineColor.copy(alpha = 0.09f))
    drawPath(body, outlineColor.copy(alpha = 0.30f), style = Stroke(width = 1.5f))

    // The central floor has no trustworthy traction-battery signal on this combustion car.
    drawRoundRect(
        color = blue.copy(alpha = 0.18f),
        topLeft = Offset(105f, 43f),
        size = Size(60f, 43f),
        cornerRadius = CornerRadius(8f),
    )
    drawRoundRect(
        color = blue.copy(alpha = 0.55f),
        topLeft = Offset(105f, 43f),
        size = Size(60f, 43f),
        cornerRadius = CornerRadius(8f),
        style = Stroke(width = 1.3f),
    )

    // Engine temperature: blue while cold/unknown, green normal, orange hot and red critical.
    drawRoundRect(engineColor.copy(alpha = 0.78f), Offset(109f, 12f), Size(52f, 25f), CornerRadius(6f))
    drawLine(engineColor, Offset(115f, 18f), Offset(155f, 18f), 2f, StrokeCap.Round)
    drawLine(engineColor, Offset(115f, 31f), Offset(155f, 31f), 2f, StrokeCap.Round)

    // Rear fuel tank. Its fill follows the calibrated percentage; an unknown level stays blue.
    drawRoundRect(
        color = fuelColor.copy(alpha = 0.24f),
        topLeft = Offset(111f, 92f),
        size = Size(48f, 15f),
        cornerRadius = CornerRadius(5f),
    )
    fuelFraction?.let { fraction ->
        drawRoundRect(
            color = fuelColor.copy(alpha = 0.88f),
            topLeft = Offset(113f, 94f),
            size = Size(44f * fraction, 11f),
            cornerRadius = CornerRadius(3f),
        )
    }
    drawRoundRect(
        color = fuelColor,
        topLeft = Offset(111f, 92f),
        size = Size(48f, 15f),
        cornerRadius = CornerRadius(5f),
        style = Stroke(width = 1.4f),
    )

    val wheels = listOf(
        Triple(TirePosition.FRONT_LEFT, Offset(82f, 17f), false),
        Triple(TirePosition.FRONT_RIGHT, Offset(177f, 17f), false),
        Triple(TirePosition.REAR_LEFT, Offset(82f, 76f), true),
        Triple(TirePosition.REAR_RIGHT, Offset(177f, 76f), true),
    )
    wheels.forEach { (position, origin, rear) ->
        val tireColor = tireColors[position] ?: blue
        drawRoundRect(
            color = tireColor.copy(alpha = 0.86f),
            topLeft = origin,
            size = Size(11f, 25f),
            cornerRadius = CornerRadius(5f),
        )
        val brakeColor = when {
            rear && handbrakeEngaged -> red
            serviceBrakePressed -> green
            else -> blue
        }
        val brakeX = if (origin.x < 135f) origin.x + 14f else origin.x - 6f
        drawRoundRect(
            color = brakeColor.copy(alpha = 0.92f),
            topLeft = Offset(brakeX, origin.y + 3f),
            size = Size(4f, 19f),
            cornerRadius = CornerRadius(2f),
        )
    }
}
