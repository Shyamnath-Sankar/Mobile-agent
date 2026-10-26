package io.github.shyamnathsankar.zeroclaw.tools

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Every tool the app exposes to ZeroClaw (as `android__<name>`). */
object Tools {
    val all: List<Tool> = listOf(
        simple(
            "browser_navigate",
            "Open a web page in the phone's agent browser (visible to the user in the Browser tab). Public http(s) sites only.",
            schema("url" to stringProp("Page URL"), required = listOf("url")),
        ) { ctx, a -> AgentBrowser.navigate(ctx, a.getString("url")) },
        simple(
            "browser_snapshot",
            "Read the current page: visible text plus a numbered list of links, buttons and fields to use with browser_click / browser_type.",
            schema("max_chars" to intProp("Maximum characters of page text (default 8000)")),
        ) { ctx, a -> AgentBrowser.snapshot(ctx, a.optInt("max_chars", 8_000).coerceIn(500, 20_000)) },
        simple(
            "browser_click",
            "Click element [n] from the latest snapshot. Submits, sign-ins, payments and deletes ask the user first.",
            schema("n" to intProp("Element number"), required = listOf("n")),
        ) { ctx, a -> AgentBrowser.click(ctx, a.getInt("n")) },
        simple(
            "browser_type",
            "Type text into field [n] from the latest snapshot; set submit=true to submit the form (asks the user first). " +
                "Never type passwords or one-time codes: use browser_handoff so the user enters them.",
            schema(
                "n" to intProp("Element number"),
                "text" to stringProp("Text to type"),
                "submit" to boolProp("Submit the form after typing"),
                required = listOf("n", "text"),
            ),
        ) { ctx, a -> AgentBrowser.type(ctx, a.getInt("n"), a.getString("text"), a.optBoolean("submit", false)) },
        simple(
            "browser_scroll",
            "Scroll the current page.",
            schema("direction" to enumProp("Where to scroll", "down", "up", "top", "bottom"), required = listOf("direction")),
        ) { ctx, a -> AgentBrowser.scroll(ctx, a.getString("direction")) },
        simple("browser_back", "Go back to the previous page.", schema()) { ctx, _ -> AgentBrowser.back(ctx) },
        simple(
            "browser_wait",
            "Wait until text appears, a CSS selector matches, or (with neither) the page finishes loading. Use after clicks on pages that load content dynamically.",
            schema(
                "text" to stringProp("Visible text to wait for"),
                "selector" to stringProp("CSS selector to wait for"),
                "timeout_seconds" to intProp("1-60 (default 15)"),
            ),
        ) { ctx, a ->
            AgentBrowser.waitFor(ctx, a.optString("text").ifEmpty { null }, a.optString("selector").ifEmpty { null }, a.optInt("timeout_seconds", 15))
        },
        simple(
            "browser_screenshot",
            "Take a screenshot of the current page (returned as an image) to see layout, charts or images that the text snapshot misses.",
            schema(),
        ) { ctx, _ -> AgentBrowser.screenshot(ctx) },
        simple(
            "browser_handoff",
            "Hand the browser to the user and wait until they tap Done: use it when a page needs the user to sign in, " +
                "enter a password or code, solve a captcha or confirm something personally. Never type passwords yourself. " +
                "Returns once the user is finished (or cancelled); then call browser_snapshot to continue.",
            schema("message" to stringProp("What the user should do, e.g. 'Sign in to your GitHub account'"), required = listOf("message")),
        ) { ctx, a ->
            val done = Handoff.request(ctx, a.getString("message"))
            if (done) {
                ToolResult("The user finished. Now at: ${AgentBrowser.state.value.title}\n${AgentBrowser.state.value.url}")
            } else {
                ToolResult("The user cancelled or did not respond.", isError = true)
            }
        },
        simple(
            "web_fetch",
            "Download a public web page and return clean readable text (faster than the browser for research).",
            schema(
                "url" to stringProp("Page URL"),
                "max_chars" to intProp("Maximum characters to return (default 12000)"),
                required = listOf("url"),
            ),
        ) { _, a ->
            withContext(Dispatchers.IO) { WebFetch.fetch(a.getString("url"), a.optInt("max_chars", 12_000).coerceIn(500, 50_000)) }
        },
        simple(
            "run_command",
            "Run a shell command with the phone's built-in toybox shell inside the app workspace " +
                "(ls, cat, grep, sed, awk, find, sort, wc, curl-less; no root, no package installs). " +
                "Commands that delete or overwrite files ask the user first.",
            schema(
                "command" to stringProp("Shell command for /system/bin/sh -c"),
                "timeout_seconds" to intProp("Time limit, 1-${Shell.MAX_TIMEOUT_S} (default ${Shell.DEFAULT_TIMEOUT_S})"),
                "cwd" to stringProp("Working directory relative to the workspace"),
                required = listOf("command"),
            ),
        ) { ctx, a ->
            val command = a.getString("command")
            val reason = when {
                Shell.isDestructive(command) -> "Run a command that changes files"
                Shell.leavesWorkspace(command) -> "Run a command outside the agent workspace"
                else -> null
            }
            if (reason != null && !Approvals.request(ctx, reason, command.take(400))) {
                return@simple ToolResult("The user denied this command.", isError = true)
            }
            val o = Shell.run(ctx, command, a.optInt("timeout_seconds", Shell.DEFAULT_TIMEOUT_S), a.optString("cwd").ifEmpty { null })
            TerminalLog.record(command, o, byAgent = true)
            val status = when {
                o.timedOut -> "timed out"
                else -> "exit ${o.exitCode}"
            }
            ToolResult("[$status]\n${o.output}", isError = o.timedOut || o.exitCode != 0)
        },
    )

    fun find(name: String) = all.firstOrNull { it.name == name }

    private fun simple(
        name: String,
        description: String,
        inputSchema: JSONObject,
        block: suspend (Context, JSONObject) -> ToolResult,
    ): Tool = object : Tool {
        override val name = name
        override val description = description
        override val inputSchema = inputSchema
        override suspend fun call(context: Context, args: JSONObject) = block(context, args)
    }
}
