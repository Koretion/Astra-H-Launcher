package com.ts10.inspector.vehicle

import com.ts10.inspector.settings.DashboardPreferences
import java.math.BigDecimal

/** Parses an odometer reference using either the Spanish comma or a decimal point. */
internal fun parseManualOdometerKm(input: String): Double? {
    val normalized = input.trim().replace(',', '.')
    if (!ODOMETER_PATTERN.matches(normalized)) return null
    return normalized.toDoubleOrNull()
        ?.takeIf { it.isFinite() && it in 0.0..DashboardPreferences.MAX_ODOMETER_KM }
}

internal fun formatManualOdometerKm(value: Double?): String = value?.let {
    BigDecimal.valueOf(it).stripTrailingZeros().toPlainString().replace('.', ',')
}.orEmpty()

private val ODOMETER_PATTERN = Regex("\\d{1,7}(?:\\.\\d{1,3})?")
