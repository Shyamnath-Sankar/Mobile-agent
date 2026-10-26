package io.github.shyamnathsankar.zeroclaw.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.shyamnathsankar.zeroclaw.ui.theme.Manrope
import io.github.shyamnathsankar.zeroclaw.ui.theme.Zc

/** "✱ ZeroClaw" wordmark used at the top of every screen. */
@Composable
fun BrandMark(modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text("✱", fontSize = 22.sp, color = Zc.Ink, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(6.dp))
        Text("ZeroClaw", fontFamily = Manrope, fontWeight = FontWeight.Bold, fontSize = 17.sp, color = Zc.Ink)
    }
}

@Composable
fun Hairline(modifier: Modifier = Modifier) = HorizontalDivider(modifier, thickness = 1.dp, color = Zc.Hairline)

/** Small filled pill, e.g. "● Running". */
@Composable
fun Pill(text: String, color: Color, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    Box(
        modifier
            .clip(CircleShape)
            .background(color)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = Zc.Ink)
    }
}

/** Outlined pill button for secondary actions. */
@Composable
fun OutlinePill(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Box(
        modifier
            .clip(CircleShape)
            .border(1.dp, if (enabled) Zc.Ink else Zc.Hairline, CircleShape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = if (enabled) Zc.Ink else Zc.Muted)
    }
}

/** The round ink button with an arrow from the reference design. */
@Composable
fun CircleArrowButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    diagonal: Boolean = false,
    loading: Boolean = false,
    enabled: Boolean = true,
    size: Dp = 46.dp,
    contentDescription: String? = null,
) {
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(if (enabled) Zc.Ink else Zc.Muted)
            .clickable(enabled = enabled && !loading, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            CircularProgressIndicator(Modifier.size(20.dp), color = Zc.Sage, strokeWidth = 2.dp)
        } else {
            Icon(
                Icons.Filled.ArrowForward,
                contentDescription = contentDescription,
                tint = Zc.Sage,
                modifier = Modifier.size(20.dp).rotate(if (diagonal) -45f else 0f),
            )
        }
    }
}

/** A labelled usage bar: "Device memory ▬▬▬▭▭ 62%". */
@Composable
fun UsageBar(label: String, fraction: Float, color: Color, modifier: Modifier = Modifier) {
    val clamped = fraction.coerceIn(0f, 1f)
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = Zc.Ink)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .weight(1f)
                    .height(22.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Zc.Paper),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(clamped)
                        .height(22.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(color),
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(
                "${(clamped * 100).toInt()}%",
                style = MaterialTheme.typography.bodyMedium,
                color = Zc.Ink,
                modifier = Modifier.width(44.dp),
            )
        }
    }
}

/** A row separated by hairlines with a trailing arrow button. */
@Composable
fun ActionRow(
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
    enabled: Boolean = true,
    diagonal: Boolean = false,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = if (enabled) Zc.Ink else Zc.Muted)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Zc.Muted)
            }
        }
        CircleArrowButton(onClick = onClick, enabled = enabled, diagonal = diagonal, size = 40.dp)
    }
}

private data class Bubble(val color: Color, val icon: ImageVector, val label: String)

/** Overlapping pastel circles with line icons — the agent's capabilities. */
@Composable
fun CapabilityCluster(modifier: Modifier = Modifier, size: Dp = 84.dp) {
    val top = listOf(
        Bubble(Zc.Forest, Icons.Outlined.DateRange, "Schedules"),
        Bubble(Zc.Butter, Icons.Outlined.Star, "Skills"),
    )
    val bottom = listOf(
        Bubble(Zc.Lilac, Icons.Outlined.Send, "Chat"),
        Bubble(Zc.Mint, Icons.Outlined.Email, "Channels"),
        Bubble(Zc.Apricot, Icons.Outlined.Build, "Tools"),
    )
    val step = size - size / 12
    Box(modifier.size(width = step * 2 + size, height = step + size)) {
        top.forEachIndexed { i, b -> BubbleView(b, size, Modifier.offset(x = step * i)) }
        bottom.forEachIndexed { i, b -> BubbleView(b, size, Modifier.offset(x = step * i, y = step)) }
    }
}

@Composable
private fun BubbleView(b: Bubble, size: Dp, modifier: Modifier = Modifier) {
    Box(
        modifier.size(size).clip(CircleShape).background(b.color),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            b.icon,
            contentDescription = b.label,
            tint = if (b.color == Zc.Forest) Zc.Sage else Zc.Ink,
            modifier = Modifier.size(size * 0.36f),
        )
    }
}

/** A hairline-separated row with a switch, styled to the sage palette. */
@Composable
fun ToggleRow(title: String, subtitle: String?, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = Zc.Ink)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Zc.Muted)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Zc.Sage,
                checkedTrackColor = Zc.Ink,
                uncheckedThumbColor = Zc.Ink,
                uncheckedTrackColor = Zc.Paper,
                uncheckedBorderColor = Zc.Ink,
            ),
        )
    }
}
