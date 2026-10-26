package io.github.shyamnathsankar.zeroclaw.tools

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** One command run in the Terminal tab or by the agent. */
data class TerminalEntry(val command: String, val output: String, val status: String, val byAgent: Boolean)

/** History shown in the Terminal tab, so the user can see everything the agent ran. */
object TerminalLog {
    private val _entries = MutableStateFlow<List<TerminalEntry>>(emptyList())
    val entries: StateFlow<List<TerminalEntry>> = _entries.asStateFlow()

    fun record(command: String, outcome: Shell.Outcome, byAgent: Boolean) {
        val status = if (outcome.timedOut) "timed out" else "exit ${outcome.exitCode}"
        _entries.update { (it + TerminalEntry(command, outcome.output, status, byAgent)).takeLast(200) }
    }

    fun clear() = _entries.update { emptyList() }
}
