package io.github.shyamnathsankar.zeroclaw.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.shyamnathsankar.zeroclaw.files.Outbox
import io.github.shyamnathsankar.zeroclaw.ui.theme.Zc

/** Pastel badge colour by file type. */
private fun badgeColor(name: String): Color = when (name.substringAfterLast('.', "").lowercase()) {
    "pdf" -> Zc.Apricot
    "doc", "docx", "odt", "rtf" -> Zc.Lilac
    "xls", "xlsx", "csv", "ods" -> Zc.Mint
    "ppt", "pptx", "odp" -> Zc.Butter
    else -> Zc.PaperDeep
}

@Composable
private fun FileBadge(name: String, size: Int = 44) {
    val ext = name.substringAfterLast('.', "").uppercase().take(4).ifEmpty { "FILE" }
    val color = badgeColor(name)
    Box(
        Modifier.size(size.dp).clip(RoundedCornerShape(12.dp)).background(color),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            ext,
            fontSize = if (ext.length > 3) 10.sp else 12.sp,
            fontWeight = FontWeight.Bold,
            color = if (color == Zc.PaperDeep) Zc.Ink else Zc.OnPastel,
        )
    }
}

/**
 * The newest file the agent made, floating over the chat with Open and Share, so the user gets it right
 * where they asked for it. Stays until dismissed.
 */
@Composable
fun FreshFileCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val item by Outbox.fresh.collectAsState()
    // Keeps the card's content while it slides away after being dismissed.
    var last by remember { mutableStateOf<Outbox.Item?>(null) }
    LaunchedEffect(item) { if (item != null) last = item }
    AnimatedVisibility(
        visible = item != null,
        modifier = modifier,
        enter = slideInVertically { -it } + fadeIn(),
        exit = slideOutVertically { -it } + fadeOut(),
    ) {
        val f = item ?: last ?: return@AnimatedVisibility
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .shadow(10.dp, RoundedCornerShape(22.dp))
                .clip(RoundedCornerShape(22.dp))
                .background(Zc.Paper)
                .border(1.dp, Zc.Hairline, RoundedCornerShape(22.dp))
                .padding(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FileBadge(f.name)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        f.name,
                        style = MaterialTheme.typography.titleSmall,
                        color = Zc.Ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        Outbox.formatSize(f.size) + if (f.downloadUri != null) " · Saved to Downloads" else " · Ready",
                        style = MaterialTheme.typography.bodySmall,
                        color = Zc.Muted,
                    )
                }
                Box(
                    Modifier.size(32.dp).clip(CircleShape).clickable { Outbox.dismissFresh() },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("×", fontSize = 20.sp, color = Zc.Muted)
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    Modifier
                        .clip(CircleShape)
                        .background(Zc.Ink)
                        .clickable { Outbox.open(context, f) }
                        .padding(horizontal = 20.dp, vertical = 9.dp),
                ) {
                    Text("Open", style = MaterialTheme.typography.labelMedium, color = Zc.Sage)
                }
                OutlinePill("Share", onClick = { Outbox.share(context, f) })
            }
        }
    }
}

/** Everything the agent has handed over, newest first (App tab). */
@Composable
fun FilesSection() {
    val context = LocalContext.current
    val items by Outbox.items.collectAsState()
    Text("Files", style = MaterialTheme.typography.headlineMedium, color = Zc.Ink)
    Text(
        if (items.isEmpty()) {
            "Ask the agent for a PDF, Word, Excel or PowerPoint file, or any other file. It shows up here and in Downloads/ZeroClaw."
        } else {
            "Made by your agent. Copies are in Downloads/ZeroClaw."
        },
        style = MaterialTheme.typography.bodyMedium,
        color = Zc.InkSoft,
    )
    Spacer(Modifier.height(8.dp))
    var showAll by remember { mutableStateOf(false) }
    val shown = if (showAll) items else items.take(5)
    shown.forEach { f ->
        Hairline()
        Row(
            Modifier.fillMaxWidth().clickable { Outbox.open(context, f) }.padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FileBadge(f.name, size = 38)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(f.name, style = MaterialTheme.typography.bodyLarge, color = Zc.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${Outbox.formatSize(f.size)} · ${formatAgo(f.createdAt)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Zc.Muted,
                )
            }
            Text(
                "Share",
                style = MaterialTheme.typography.labelMedium,
                color = Zc.Forest,
                modifier = Modifier.clip(CircleShape).clickable { Outbox.share(context, f) }.padding(8.dp),
            )
            Text(
                "Remove",
                style = MaterialTheme.typography.labelMedium,
                color = Zc.Muted,
                modifier = Modifier.clip(CircleShape).clickable { Outbox.delete(context, f) }.padding(8.dp),
            )
        }
    }
    if (items.size > 5) {
        Hairline()
        Text(
            if (showAll) "Show fewer" else "Show all ${items.size}",
            style = MaterialTheme.typography.labelMedium,
            color = Zc.Forest,
            modifier = Modifier.padding(vertical = 12.dp).clickable { showAll = !showAll },
        )
    }
    if (items.isNotEmpty()) Hairline()
}

private fun formatAgo(at: Long): String {
    val minutes = (System.currentTimeMillis() - at) / 60_000
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "$minutes min ago"
        minutes < 24 * 60 -> "${minutes / 60} h ago"
        else -> java.time.Instant.ofEpochMilli(at).atZone(java.time.ZoneId.systemDefault())
            .format(java.time.format.DateTimeFormatter.ofPattern("d MMM"))
    }
}
