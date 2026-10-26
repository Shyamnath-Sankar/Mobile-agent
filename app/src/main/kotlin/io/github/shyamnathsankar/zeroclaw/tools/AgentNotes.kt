package io.github.shyamnathsankar.zeroclaw.tools

import android.content.Context
import io.github.shyamnathsankar.zeroclaw.ZeroClawRuntime
import java.io.File

/**
 * Tells each agent what the phone gives it. ZeroClaw puts the workspace's TOOLS.md into every system prompt,
 * so the app keeps a short, marked section at the top of it. Without this, models tend to answer "I can't
 * control a browser or make files" even though the android__* tools are in their tool list.
 * The rest of TOOLS.md stays the user's; the file is only rewritten when the section changes.
 */
object AgentNotes {
    private const val START = "<!-- zeroclaw-mobile:phone:start (kept up to date by the ZeroClaw Mobile app) -->"
    private const val END = "<!-- zeroclaw-mobile:phone:end -->"

    private val NOTES = """
        $START
        ## This phone (ZeroClaw Mobile)

        You run inside the ZeroClaw Mobile app on the user's Android phone. Your phone tools are named `android__*`.

        - **You do have a web browser.** `android__browser_navigate` opens any website (e.g. youtube.com) in the app's Browser tab, where the user can watch. Then use `android__browser_snapshot`, `_click`, `_type`, `_scroll`, `_wait` and `_screenshot`. For sign-ins, passwords, codes or captchas call `android__browser_handoff` so the user does it. `android__web_fetch` reads a page quickly as text.
        - **Files for the user.** When the user asks for a file or document, make it with `android__create_pdf`, `android__create_docx`, `android__create_xlsx`, `android__create_pptx`, or `android__create_file` (HTML, Markdown, CSV, code, ...). If you already wrote a file with file_write or shell, call `android__deliver_file` with its path. Only these put the file in front of the user (Open, Share, Downloads); files left in your workspace are invisible to them.
        - **Shell.** `android__run_command` runs the phone's built-in toybox shell. There is no Python, Node or package manager.
        - You can't open other apps or see the phone's screen outside this app.
        $END
    """.trimIndent()

    /** Makes sure [alias]'s TOOLS.md starts with the current notes. Blocking file I/O. */
    fun ensure(context: Context, alias: String) {
        if (!alias.matches(Regex("[A-Za-z0-9_.-]+"))) return
        val workspace = File(context.filesDir, "zeroclaw/agents/$alias/workspace")
        if (!workspace.isDirectory) return
        val file = File(workspace, "TOOLS.md")
        val current = if (file.isFile) runCatching { file.readText() }.getOrNull() ?: return else ""
        val updated = withNotes(current)
        if (updated != current) {
            runCatching { file.writeText(updated) }
                .onSuccess { ZeroClawRuntime.appLog("[app] told agent '$alias' about the phone tools (TOOLS.md)") }
                .onFailure { ZeroClawRuntime.appLog("[app] could not update TOOLS.md for '$alias': ${it.message}") }
        }
    }

    internal fun withNotes(current: String): String {
        val start = current.indexOf(START)
        val end = current.indexOf(END)
        val rest = if (start >= 0 && end > start) {
            (current.substring(0, start) + current.substring(end + END.length)).trimStart('\n')
        } else {
            current
        }
        return if (rest.isBlank()) NOTES + "\n" else NOTES + "\n\n" + rest
    }
}
