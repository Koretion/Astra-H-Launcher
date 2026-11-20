package com.ts10.inspector.dashboard

import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.text.KeyboardOptions
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ts10.inspector.R
import com.ts10.inspector.ui.DashboardAssetIcon
import com.ts10.inspector.ui.DashboardColors
import com.ts10.inspector.ui.LauncherLayout
import com.ts10.inspector.vehicle.fuel.FuelRangeEstimator
import com.ts10.inspector.vehicle.fuel.FuelTrackingRepository
import com.ts10.inspector.vehicle.fuel.PendingRefuel
import java.util.Locale

@Composable
internal fun FuelRangePopup(layout: LauncherLayout, colors: DashboardColors) {
    val range by FuelRangeEstimator.state.collectAsStateWithLifecycle()
    if (!range.popupVisible) return
    val rangeKm = range.rangeKm ?: return
    Box(
        modifier = Modifier
            .offset(layout.dp(25), layout.dp(161))
            .size(layout.dp(258), layout.dp(68))
            .background(
                if (colors.dark) Color(0xF02A090C) else Color(0xF0FFF0F0),
                RoundedCornerShape(layout.dp(12)),
            )
            .border(layout.dp(2), Color(0xFFD51F2A), RoundedCornerShape(layout.dp(12))),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.foundation.layout.Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DashboardAssetIcon(
                drawable = R.drawable.icon_fuel,
                contentDescription = null,
                tint = Color(0xFFD51F2A),
                modifier = Modifier.size(layout.dp(31)),
            )
            androidx.compose.foundation.layout.Column(
                modifier = Modifier.offset(x = layout.dp(9)),
            ) {
                Text(
                    stringResource(R.string.fuel_low),
                    color = Color(0xFFD51F2A),
                    fontSize = layout.sp(13),
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    stringResource(R.string.fuel_estimated_range, rangeKm),
                    color = colors.primary,
                    fontSize = layout.sp(12),
                )
            }
        }
    }
}

@Composable
internal fun RefuelDetectedPopup(layout: LauncherLayout, colors: DashboardColors) {
    val tracking by FuelTrackingRepository.state.collectAsStateWithLifecycle()
    val pending = tracking.pendingRefuel ?: return
    var formVisible by remember(pending.detectedAtMillis) { mutableStateOf(true) }
    Box(
        modifier = Modifier
            .offset(layout.dp(25), layout.dp(161))
            .size(layout.dp(258), layout.dp(78))
            .background(
                if (colors.dark) Color(0xF00B2B18) else Color(0xF0ECFFF2),
                RoundedCornerShape(layout.dp(12)),
            )
            .border(layout.dp(2), Color(0xFF2E9D50), RoundedCornerShape(layout.dp(12)))
            .clickable { formVisible = true },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.fuel_refuel_detected), color = Color(0xFF2E9D50), fontSize = layout.sp(13), fontWeight = FontWeight.Bold)
            Text(
                stringResource(R.string.fuel_refuel_estimated, "%.1f".format(pending.estimatedLiters)),
                color = colors.primary,
                fontSize = layout.sp(12),
            )
            Text(stringResource(R.string.fuel_refuel_tap), color = colors.muted, fontSize = layout.sp(8))
        }
    }
    if (formVisible) RefuelDetectedDialog(pending, layout, colors) { formVisible = false }
}

@Composable
private fun RefuelDetectedDialog(
    pending: PendingRefuel,
    layout: LauncherLayout,
    colors: DashboardColors,
    onLater: () -> Unit,
) {
    var litersText by remember(pending.detectedAtMillis) {
        mutableStateOf(String.format(Locale.US, "%.1f", pending.estimatedLiters))
    }
    var priceText by remember(pending.detectedAtMillis) { mutableStateOf("") }
    var stationText by remember(pending.detectedAtMillis) { mutableStateOf("") }
    var filledToFull by remember(pending.detectedAtMillis) { mutableStateOf(true) }
    LaunchedEffect(pending.station) {
        pending.station?.let { station ->
            stationText = station.name
            priceText = station.pricePerLiter?.let { String.format(Locale.US, "%.3f", it) }.orEmpty()
        }
    }
    val liters = litersText.replace(',', '.').toFloatOrNull()
    val price = priceText.replace(',', '.').toFloatOrNull()
    val valid = liters != null && liters > 0f && (price == null || price > 0f)
    Dialog(onDismissRequest = onLater) {
        Column(
            modifier = Modifier
                .size(layout.dp(430), layout.dp(330))
                .background(colors.background, RoundedCornerShape(layout.dp(14)))
                .border(layout.dp(2), Color(0xFF2E9D50), RoundedCornerShape(layout.dp(14)))
                .padding(layout.dp(16)),
            verticalArrangement = Arrangement.spacedBy(layout.dp(7)),
        ) {
            Text(stringResource(R.string.fuel_refuel_detected), color = Color(0xFF2E9D50), fontSize = layout.sp(14), fontWeight = FontWeight.Bold)
            Text(
                when {
                    pending.lookingUpStation -> stringResource(R.string.fuel_refuel_searching)
                    else -> pending.lookupMessage ?: stringResource(R.string.fuel_refuel_confirm)
                },
                color = colors.muted,
                fontSize = layout.sp(8),
            )
            OutlinedTextField(
                value = litersText,
                onValueChange = { if (it.length <= 5 && it.all { char -> char.isDigit() || char == ',' || char == '.' }) litersText = it },
                label = { Text(stringResource(R.string.fuel_litres_added)) },
                suffix = { Text("L") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(layout.dp(8))) {
                OutlinedTextField(
                    value = stationText,
                    onValueChange = { if (it.length <= 60) stationText = it },
                    label = { Text(stringResource(R.string.fuel_station)) },
                    singleLine = true,
                    modifier = Modifier.weight(1.3f),
                )
                OutlinedTextField(
                    value = priceText,
                    onValueChange = { if (it.length <= 6 && it.all { char -> char.isDigit() || char == ',' || char == '.' }) priceText = it },
                    label = { Text("EUR/L") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(0.7f),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { filledToFull = !filledToFull }) {
                Text(if (filledToFull) "[X]" else "[ ]", color = Color(0xFF2E9D50), fontSize = layout.sp(10))
                Text(stringResource(R.string.fuel_full_tank), color = colors.primary, fontSize = layout.sp(9))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(layout.dp(12)), modifier = Modifier.fillMaxWidth()) {
                Text(
                    stringResource(R.string.common_later_caps),
                    color = colors.muted,
                    fontSize = layout.sp(10),
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f).clickable(onClick = onLater).padding(vertical = layout.dp(9)),
                )
                Text(
                    stringResource(R.string.common_save_caps),
                    color = if (valid) Color(0xFF2E9D50) else colors.muted,
                    fontSize = layout.sp(10),
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.End,
                    modifier = Modifier.weight(1f).clickable(enabled = valid) {
                        FuelTrackingRepository.recordRefuel(liters!!, filledToFull, price, stationText)
                    }.padding(vertical = layout.dp(9)),
                )
            }
        }
    }
}
