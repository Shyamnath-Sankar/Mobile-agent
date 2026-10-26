package io.github.shyamnathsankar.zeroclaw.tools

import android.content.Context
import org.json.JSONObject

/** Result of a tool call, returned to the agent as MCP text content. */
data class ToolResult(val text: String, val isError: Boolean = false)

/** A capability the app offers the agent over MCP. */
interface Tool {
    val name: String
    val description: String

    /** JSON Schema for the arguments object. */
    val inputSchema: JSONObject

    suspend fun call(context: Context, args: JSONObject): ToolResult
}

/** Small helper to build JSON Schemas without boilerplate. */
internal fun schema(vararg props: Pair<String, JSONObject>, required: List<String> = emptyList()): JSONObject =
    JSONObject()
        .put("type", "object")
        .put("properties", JSONObject().apply { props.forEach { (k, v) -> put(k, v) } })
        .put("required", org.json.JSONArray(required))
        .put("additionalProperties", false)

internal fun stringProp(description: String) = JSONObject().put("type", "string").put("description", description)
internal fun intProp(description: String) = JSONObject().put("type", "integer").put("description", description)
internal fun boolProp(description: String) = JSONObject().put("type", "boolean").put("description", description)
internal fun enumProp(description: String, vararg values: String) =
    JSONObject().put("type", "string").put("description", description).put("enum", org.json.JSONArray(values.toList()))
