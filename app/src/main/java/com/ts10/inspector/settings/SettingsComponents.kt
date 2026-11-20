package com.ts10.inspector.settings

import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.BoxWithConstraints
import com.ts10.inspector.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.ts10.inspector.ui.DashboardColors
import com.ts10.inspector.ui.LauncherLayout
import com.ts10.inspector.ui.LocalDashboardColors

@Composable
internal fun DiagnosticAction(
    text: String,
    color: Color,
    layout: LauncherLayout,
    onClick: () -> Unit,
) {
    Text(
        text,
        color = color,
        fontSize = layout.sp(9),
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .teslaControlSurface(LocalDashboardColors.current, layout, fillWidth = false)
            .clickable(onClick = onClick)
            .padding(horizontal = layout.dp(11), vertical = layout.dp(8)),
    )
}

@Composable
internal fun DetailNavigationRow(title: String, value: String, layout: LauncherLayout, onClick: () -> Unit) {
    val colors = LocalDashboardColors.current
    Row(
        modifier = Modifier
            .teslaControlSurface(colors, layout)
            .clickable(onClick = onClick)
            .padding(horizontal = layout.dp(11), vertical = layout.dp(8)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = colors.primary, fontSize = layout.sp(10), fontWeight = FontWeight.Bold)
            Text(value, color = colors.muted, fontSize = layout.sp(8), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.Outlined.ChevronRight, null, tint = colors.muted, modifier = Modifier.size(layout.dp(17)))
    }
}

@Composable
internal fun SettingsCard(layout: LauncherLayout, width: Float, content: @Composable ColumnScope.() -> Unit) {
    val colors = LocalDashboardColors.current
    Column(
        modifier = Modifier
            .padding(bottom = layout.dp(9))
            .width(layout.dp(width))
            .wrapContentHeight()
            .background(colors.primary.copy(alpha = if (colors.dark) .055f else .045f), RoundedCornerShape(layout.dp(8)))
            .padding(horizontal = layout.dp(12), vertical = layout.dp(7)),
        content = content,
    )
}

@Composable
internal fun MetricRow(label: String, value: String, source: String, layout: LauncherLayout) {
    val colors = LocalDashboardColors.current
    BoxWithConstraints(modifier = Modifier.padding(vertical = layout.dp(5))) {
        // Un valor largo no puede pasar del 55 %: antes aplastaba la etiqueta a una letra por línea.
        val maxValueWidth = maxWidth * 0.55f
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(label, color = colors.primary, fontSize = layout.sp(10), fontWeight = FontWeight.Bold)
                Text(source, color = colors.muted, fontSize = layout.sp(7), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(
                value,
                color = if (value == stringResource(R.string.common_pending) || value.startsWith("--")) colors.muted else colors.primary,
                fontSize = layout.sp(10),
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.End,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = layout.dp(8)).widthIn(max = maxValueWidth),
            )
        }
    }
}

@Composable
internal fun ToggleRow(title: String, subtitle: String, checked: Boolean, layout: LauncherLayout, onChange: (Boolean) -> Unit) {
    val colors = LocalDashboardColors.current
    Row(modifier = Modifier.padding(vertical = layout.dp(5)), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = colors.primary, fontSize = layout.sp(10), fontWeight = FontWeight.Bold)
            Text(subtitle, color = colors.muted, fontSize = layout.sp(8))
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
internal fun ActionRow(title: String, subtitle: String, layout: LauncherLayout, enabled: Boolean, onClick: () -> Unit) {
    val colors = LocalDashboardColors.current
    Row(
        modifier = Modifier
            .teslaControlSurface(colors, layout, enabled = enabled)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = layout.dp(11), vertical = layout.dp(8)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = if (enabled) Color(0xFF2E9D50) else colors.muted, fontSize = layout.sp(9), fontWeight = FontWeight.Bold)
            Text(subtitle, color = colors.muted, fontSize = layout.sp(8))
        }
        Icon(Icons.Outlined.ChevronRight, null, tint = colors.muted, modifier = Modifier.size(layout.dp(18)))
    }
}

@Composable
internal fun SectionHint(text: String, layout: LauncherLayout, modifier: Modifier = Modifier) {
    val colors = LocalDashboardColors.current
    Text(text, color = colors.muted, fontSize = layout.sp(8), lineHeight = layout.sp(11), modifier = modifier.padding(bottom = layout.dp(8)))
}

@Composable
internal fun EmptyState(title: String, subtitle: String, layout: LauncherLayout) {
    val colors = LocalDashboardColors.current
    Column(modifier = Modifier.padding(top = layout.dp(60)), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Outlined.LocationOn, null, tint = colors.muted, modifier = Modifier.size(layout.dp(38)))
        Text(title, color = colors.primary, fontSize = layout.sp(12), fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = layout.dp(9)))
        Text(subtitle, color = colors.muted, fontSize = layout.sp(9), modifier = Modifier.padding(top = layout.dp(4), start = layout.dp(22), end = layout.dp(22)))
    }
}

@Composable
internal fun Choice(text: String, selected: Boolean, layout: LauncherLayout, onClick: () -> Unit) {
    val colors = LocalDashboardColors.current
    Text(
        text,
        color = if (selected) Color(0xFF2E9D50) else colors.muted,
        fontSize = layout.sp(9),
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .padding(horizontal = layout.dp(2))
            .background(colors.primary.copy(alpha = if (colors.dark) .08f else .05f), RoundedCornerShape(layout.dp(5)))
            .border(layout.dp(1), if (selected) Color(0xFF2E9D50) else colors.muted.copy(alpha = .22f), RoundedCornerShape(layout.dp(5)))
            .clickable(onClick = onClick)
            .padding(horizontal = layout.dp(9), vertical = layout.dp(6)),
    )
}

internal fun Modifier.teslaControlSurface(
    colors: DashboardColors,
    layout: LauncherLayout,
    enabled: Boolean = true,
    selected: Boolean = false,
    fillWidth: Boolean = true,
): Modifier {
    val outline = when {
        selected -> Color(0xFF2E9D50)
        enabled -> colors.muted.copy(alpha = if (colors.dark) .28f else .22f)
        else -> colors.muted.copy(alpha = .12f)
    }
    val sized = if (fillWidth) fillMaxWidth() else this
    return sized
        .padding(vertical = layout.dp(3))
        .background(
            colors.primary.copy(alpha = if (enabled && colors.dark) .075f else if (enabled) .05f else .025f),
            RoundedCornerShape(layout.dp(6)),
        )
        .border(layout.dp(1), outline, RoundedCornerShape(layout.dp(6)))
}

@Composable
internal fun TeslaStepButton(text: String, layout: LauncherLayout, onClick: () -> Unit) {
    val colors = LocalDashboardColors.current
    Text(
        text,
        color = Color(0xFF2E9D50),
        fontSize = layout.sp(14),
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .padding(horizontal = layout.dp(6))
            .background(colors.primary.copy(alpha = if (colors.dark) .08f else .05f), RoundedCornerShape(layout.dp(4)))
            .border(layout.dp(1), colors.muted.copy(alpha = .24f), RoundedCornerShape(layout.dp(4)))
            .clickable(onClick = onClick)
            .padding(horizontal = layout.dp(9), vertical = layout.dp(2)),
    )
}

@Composable
internal fun <T> OptionChoices(title: String, options: List<T>, selected: T, label: (T) -> String, onSelected: (T) -> Unit, layout: LauncherLayout) {
    val colors = LocalDashboardColors.current
    Column(modifier = Modifier.padding(vertical = layout.dp(8))) {
        Text(title, color = colors.primary, fontSize = layout.sp(10), fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(layout.dp(3))) { options.forEach { option -> Choice(label(option), option == selected, layout) { onSelected(option) } } }
    }
}

/** Como [OptionChoices], pero salta de línea cuando las opciones no caben en una fila. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun <T> WrappedOptionChoices(title: String, options: List<T>, selected: T, label: (T) -> String, onSelected: (T) -> Unit, layout: LauncherLayout) {
    val colors = LocalDashboardColors.current
    Column(modifier = Modifier.padding(vertical = layout.dp(8))) {
        Text(title, color = colors.primary, fontSize = layout.sp(10), fontWeight = FontWeight.Bold)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(layout.dp(3)),
            verticalArrangement = Arrangement.spacedBy(layout.dp(5)),
        ) { options.forEach { option -> Choice(label(option), option == selected, layout) { onSelected(option) } } }
    }
}

@Composable
internal fun openLabel(open: Boolean) = if (open) stringResource(R.string.common_open) else stringResource(R.string.common_closed)
