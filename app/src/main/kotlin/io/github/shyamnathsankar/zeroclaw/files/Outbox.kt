package io.github.shyamnathsankar.zeroclaw.files

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.core.content.FileProvider
import io.github.shyamnathsankar.zeroclaw.MainActivity
import io.github.shyamnathsankar.zeroclaw.ZeroClawRuntime
import io.github.shyamnathsankar.zeroclaw.background.AgentController
import io.github.shyamnathsankar.zeroclaw.background.AgentController.Hold
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Files the agent made for the user. Each one is kept in app storage (for Open and Share, through a
 * FileProvider), copied to Downloads/ZeroClaw on Android 10+ (MediaStore, no storage permission), shown as
 * a card over the chat, and listed in the App tab. When the app is in the background, a notification
 * with Open and Share stands in for the card.
 */
object Outbox {
    data class Item(
        val id: String,
        val name: String,
        val mime: String,
        val size: Long,
        val path: String,
        /** Its copy in Downloads (MediaStore content URI), or null on Android 8-9. */
        val downloadUri: String?,
        val createdAt: Long,
    )

    const val MAX_BYTES = 50L * 1024 * 1024
    private const val MAX_ITEMS = 50
    private const val CHANNEL_ID = "agent_files"

    /** Extensions the agent may not hand out: installable or executable code. */
    private val blocked = setOf("apk", "apks", "xapk", "aab", "dex", "so", "jar", "exe", "msi", "bat", "cmd", "com", "scr", "dll")

    private val _items = MutableStateFlow<List<Item>>(emptyList())
    val items: StateFlow<List<Item>> = _items.asStateFlow()

    private val _fresh = MutableStateFlow<Item?>(null)
    /** The newest file, shown as a card over the chat until the user dismisses it. */
    val fresh: StateFlow<Item?> = _fresh.asStateFlow()

    private var loaded = false

    private fun dir(context: Context) = File(context.filesDir, "outbox").also { it.mkdirs() }
    private fun index(context: Context) = File(dir(context), "index.json")

    @Synchronized
    fun load(context: Context) {
        if (loaded) return
        loaded = true
        val arr = runCatching { JSONArray(index(context).readText()) }.getOrNull() ?: return
        _items.value = (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            Item(
                o.getString("id"), o.getString("name"), o.getString("mime"), o.getLong("size"), o.getString("path"),
                o.optString("downloadUri").ifEmpty { null }, o.getLong("createdAt"),
            ).takeIf { File(it.path).isFile }
        }
    }

    /** A safe file name: no folders, no control characters, with an extension. */
    fun cleanName(name: String, defaultExt: String): String {
        var n = name.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[\\x00-\\x1f<>:\"|?*]"), "_").trim().trim('.')
        if (n.isEmpty()) n = "file"
        if (n.length > 120) n = n.take(120)
        if (!n.contains('.') && defaultExt.isNotEmpty()) n = "$n.$defaultExt"
        return n
    }

    fun isBlocked(name: String) = name.substringAfterLast('.', "").lowercase() in blocked

    fun mimeOf(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "md", "markdown" -> "text/markdown"
            "csv" -> "text/csv"
            "json" -> "application/json"
            "ts", "tsx", "kt", "kts", "py", "rs", "go", "java", "c", "cpp", "h", "sh", "yaml", "yml", "toml", "log" -> "text/plain"
            else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        }
    }

    /** Saves [bytes] as [name] and shows it to the user. Blocking; call off the main thread. */
    fun deliver(context: Context, name: String, bytes: ByteArray): Item {
        val app = context.applicationContext
        load(app)
        require(!isBlocked(name)) { "Files of type .${name.substringAfterLast('.')} can't be handed out" }
        require(bytes.size <= MAX_BYTES) { "The file is larger than ${MAX_BYTES / 1024 / 1024} MB" }
        val id = System.currentTimeMillis().toString(36) + (0..999).random()
        val folder = File(dir(app), id).also { it.mkdirs() }
        val file = File(folder, name)
        file.writeBytes(bytes)
        val mime = mimeOf(name)
        val download = runCatching { copyToDownloads(app, name, mime, bytes) }
            .onFailure { ZeroClawRuntime.appLog("[app] could not save $name to Downloads: ${it.message}") }
            .getOrNull()
        val item = Item(id, name, mime, bytes.size.toLong(), file.absolutePath, download?.toString(), System.currentTimeMillis())
        synchronized(this) {
            val all = listOf(item) + _items.value
            all.drop(MAX_ITEMS).forEach { removeFiles(app, it) }
            _items.value = all.take(MAX_ITEMS)
            save(app)
        }
        _fresh.value = item
        ZeroClawRuntime.appLog("[app] file ready: $name (${formatSize(item.size)})")
        if (!AgentController.isHeld(Hold.Ui)) notify(app, item)
        return item
    }

    fun dismissFresh() {
        _fresh.value = null
    }

    fun delete(context: Context, item: Item) {
        val app = context.applicationContext
        synchronized(this) {
            removeFiles(app, item)
            _items.value = _items.value.filterNot { it.id == item.id }
            save(app)
        }
        if (_fresh.value?.id == item.id) _fresh.value = null
    }

    fun open(context: Context, item: Item) {
        val intent = viewIntent(context, item)
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, "No app here opens ${item.name.substringAfterLast('.')} files. Try Share.", Toast.LENGTH_LONG).show()
        }
    }

    fun share(context: Context, item: Item) {
        context.startActivity(shareChooser(context, item))
    }

    fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
    }

    private fun uriFor(context: Context, item: Item): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.files", File(item.path))

    private fun viewIntent(context: Context, item: Item): Intent {
        val uri = uriFor(context, item)
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, item.mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun shareChooser(context: Context, item: Item): Intent {
        val uri = uriFor(context, item)
        val send = Intent(Intent.ACTION_SEND)
            .setType(item.mime)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(item.name, uri)
        return Intent.createChooser(send, item.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun copyToDownloads(context: Context, name: String, mime: String, bytes: ByteArray): Uri? {
        if (Build.VERSION.SDK_INT < 29) return null
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/ZeroClaw")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        try {
            resolver.openOutputStream(uri)!!.use { it.write(bytes) }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        return uri
    }

    private fun removeFiles(context: Context, item: Item) {
        File(item.path).parentFile?.deleteRecursively()
        // The Downloads copy belongs to the user once saved; it stays.
        context.getSystemService(NotificationManager::class.java)?.cancel(notificationId(item))
    }

    private fun save(context: Context) {
        val arr = JSONArray()
        _items.value.forEach {
            arr.put(
                JSONObject().put("id", it.id).put("name", it.name).put("mime", it.mime).put("size", it.size)
                    .put("path", it.path).put("downloadUri", it.downloadUri ?: "").put("createdAt", it.createdAt),
            )
        }
        index(context).writeText(arr.toString())
    }

    private fun notificationId(item: Item) = 3000 + (item.id.hashCode() and 0xfff)

    private fun notify(context: Context, item: Item) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Files from your agent", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "When the agent finishes a document or file for you"
            },
        )
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val code = notificationId(item)
        val openApp = PendingIntent.getActivity(context, code, Intent(context, MainActivity::class.java), flags)
        val open = PendingIntent.getActivity(context, code + 1, viewIntent(context, item), flags)
        val share = PendingIntent.getActivity(context, code + 2, shareChooser(context, item), flags)
        val where = if (item.downloadUri != null) "Saved to Downloads/ZeroClaw" else "Saved in the app"
        val n = android.app.Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(item.name)
            .setContentText("${formatSize(item.size)} · $where")
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .addAction(android.app.Notification.Action.Builder(null, "Open", open).build())
            .addAction(android.app.Notification.Action.Builder(null, "Share", share).build())
            .build()
        runCatching { nm.notify(code, n) }
    }
}
