package io.github.shyamnathsankar.zeroclaw.tools

import android.content.Context
import io.github.shyamnathsankar.zeroclaw.files.Outbox
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Tools that give the user a file: make a PDF / Word / Excel / PowerPoint document ([DocEngine]), save
 * text as a file, or hand over a file the agent already wrote in its workspace. Every result lands in
 * [Outbox]: a card over the chat with Open and Share, a copy in Downloads/ZeroClaw, and the App tab's list.
 */
object FileTools {
    private const val HANDED_OVER =
        "It is on the user's screen now with Open and Share buttons, and saved to Downloads/ZeroClaw. " +
            "Tell the user it is ready; don't paste its contents into the chat."

    val all: List<Tool> = listOf(
        tool(
            "create_pdf",
            "Make a PDF document for the user from HTML (headings, paragraphs, bold/italic, links, lists, tables, " +
                "<pre>, images as data: URLs). Tamil and Hindi text are supported. Use whenever the user asks for a PDF, " +
                "report, invoice, letter, resume or printable document.",
            schema(
                "filename" to stringProp("File name, e.g. 'trip-plan.pdf'"),
                "html" to stringProp("The document as HTML. Inline <style> is ignored for PDFs; use semantic tags."),
                "title" to stringProp("Document title (optional)"),
                "landscape" to boolProp("Landscape pages (default portrait A4)"),
                required = listOf("filename", "html"),
            ),
        ) { ctx, a -> document(ctx, "pdf", a, JSONObject().put("html", a.getString("html"))) },
        tool(
            "create_docx",
            "Make a Word document (.docx) for the user from HTML (headings, paragraphs, formatting, lists, tables, links, " +
                "images as data: URLs, CSS colours). Use whenever the user asks for a Word or editable document.",
            schema(
                "filename" to stringProp("File name, e.g. 'cover-letter.docx'"),
                "html" to stringProp("The document as HTML"),
                "title" to stringProp("Document title (optional)"),
                "landscape" to boolProp("Landscape pages"),
                required = listOf("filename", "html"),
            ),
        ) { ctx, a -> document(ctx, "docx", a, JSONObject().put("html", a.getString("html"))) },
        tool(
            "create_xlsx",
            "Make an Excel spreadsheet (.xlsx) for the user. Each sheet is a list of rows; the first row is the header. " +
                "Numbers stay numbers, and a cell starting with '=' is a formula (e.g. '=SUM(B2:B9)').",
            schema(
                "filename" to stringProp("File name, e.g. 'budget.xlsx'"),
                "sheets" to arrayProp(
                    "The sheets, in order",
                    objectProp(
                        "One sheet",
                        "name" to stringProp("Sheet name (max 31 characters)"),
                        "rows" to arrayProp("Rows of cells", arrayProp("One row", JSONObject().put("type", "string"))),
                        required = listOf("rows"),
                    ),
                ),
                required = listOf("filename", "sheets"),
            ),
        ) { ctx, a -> document(ctx, "xlsx", a, JSONObject().put("sheets", a.getJSONArray("sheets"))) },
        tool(
            "create_pptx",
            "Make a PowerPoint presentation (.pptx) for the user: an optional title slide, then slides with a title, " +
                "bullet points, a short paragraph, a table and speaker notes.",
            schema(
                "filename" to stringProp("File name, e.g. 'pitch.pptx'"),
                "title" to stringProp("Title slide heading (optional)"),
                "subtitle" to stringProp("Title slide subheading (optional)"),
                "slides" to arrayProp(
                    "The slides, in order",
                    objectProp(
                        "One slide",
                        "title" to stringProp("Slide title"),
                        "bullets" to arrayProp("Bullet points", JSONObject().put("type", "string")),
                        "text" to stringProp("A short paragraph"),
                        "table" to arrayProp("Table rows; the first row is the header", arrayProp("One row", JSONObject().put("type", "string"))),
                        "notes" to stringProp("Speaker notes"),
                    ),
                ),
                required = listOf("filename", "slides"),
            ),
        ) { ctx, a ->
            val spec = JSONObject().put("slides", a.getJSONArray("slides"))
            a.optString("title").takeIf { it.isNotEmpty() }?.let { spec.put("title", it) }
            a.optString("subtitle").takeIf { it.isNotEmpty() }?.let { spec.put("subtitle", it) }
            document(ctx, "pptx", a, spec, passTitle = false)
        },
        tool(
            "create_file",
            "Save text as a file the user can open, download and share: HTML pages, Markdown, CSV, JSON, code, " +
                "SVG, plain text and so on. Use this (not file_write) whenever the user asks for a file.",
            schema(
                "filename" to stringProp("File name with extension, e.g. 'index.html' or 'notes.md'"),
                "content" to stringProp("The file's full text"),
                required = listOf("filename", "content"),
            ),
        ) { ctx, a ->
            val name = Outbox.cleanName(a.getString("filename"), "txt")
            val item = withContext(Dispatchers.IO) { Outbox.deliver(ctx, name, a.getString("content").toByteArray()) }
            ToolResult("Created ${item.name} (${Outbox.formatSize(item.size)}). $HANDED_OVER")
        },
        tool(
            "deliver_file",
            "Give the user a file you already made in your workspace (for example with file_write or shell), so " +
                "they can open, download and share it. Files in your workspace are not visible to the user otherwise.",
            schema(
                "path" to stringProp("Path of the file, absolute or relative to your workspace"),
                "filename" to stringProp("Name to give it (optional; defaults to its current name)"),
                required = listOf("path"),
            ),
        ) { ctx, a ->
            withContext(Dispatchers.IO) {
                val file = resolveWorkspaceFile(ctx, a.getString("path"))
                    ?: return@withContext ToolResult("No such file in the agent workspace: ${a.getString("path")}", isError = true)
                if (file.length() > Outbox.MAX_BYTES) {
                    return@withContext ToolResult("${file.name} is larger than ${Outbox.MAX_BYTES / 1024 / 1024} MB", isError = true)
                }
                val name = Outbox.cleanName(a.optString("filename").ifEmpty { file.name }, file.extension)
                val item = Outbox.deliver(ctx, name, file.readBytes())
                ToolResult("Delivered ${item.name} (${Outbox.formatSize(item.size)}). $HANDED_OVER")
            }
        },
    )

    private suspend fun document(
        ctx: Context,
        kind: String,
        args: JSONObject,
        spec: JSONObject,
        passTitle: Boolean = true,
    ): ToolResult {
        val name = Outbox.cleanName(args.getString("filename"), kind).let {
            if (it.substringAfterLast('.', "").lowercase() == kind) it else "${it.substringBeforeLast('.')}.$kind"
        }
        if (passTitle) args.optString("title").takeIf { it.isNotEmpty() }?.let { spec.put("title", it) }
        if (args.optBoolean("landscape", false)) spec.put("landscape", true)
        val bytes = runCatching { DocEngine.render(ctx, kind, spec) }.getOrElse {
            return ToolResult("Could not make $name: ${it.message}", isError = true)
        }
        val item = withContext(Dispatchers.IO) { Outbox.deliver(ctx, name, bytes) }
        return ToolResult("Created ${item.name} (${Outbox.formatSize(item.size)}). $HANDED_OVER")
    }

    /**
     * Finds [path] in an agent workspace (files/zeroclaw/agents/<alias>/workspace) or the shell workspace.
     * Absolute paths must lie inside one of them; a relative path picks the most recently changed match.
     */
    fun resolveWorkspaceFile(ctx: Context, path: String): File? {
        val roots = workspaceRoots(ctx)
        val p = path.trim().removePrefix("file://")
        val candidates = if (p.startsWith("/")) {
            listOf(File(p))
        } else {
            roots.map { File(it, p.removePrefix("./")) }
        }
        return candidates
            .map { it.canonicalFile }
            .filter { f -> f.isFile && roots.any { f.path.startsWith(it.path + File.separator) } }
            .maxByOrNull { it.lastModified() }
    }

    fun workspaceRoots(ctx: Context): List<File> {
        val agents = File(ctx.filesDir, "zeroclaw/agents").listFiles()?.map { File(it, "workspace") }.orEmpty()
        return (agents + Shell.workspace(ctx)).filter { it.isDirectory }.map { it.canonicalFile }
    }

    private fun tool(name: String, description: String, inputSchema: JSONObject, block: suspend (Context, JSONObject) -> ToolResult): Tool =
        object : Tool {
            override val name = name
            override val description = description
            override val inputSchema = inputSchema
            override suspend fun call(context: Context, args: JSONObject) = block(context, args)
        }
}
