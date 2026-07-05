package dev.ide.core.backend

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.ide.ui.backend.AgentService
import dev.ide.ui.backend.UiAgentInputItem
import dev.ide.ui.backend.UiAgentRequest
import dev.ide.ui.backend.UiAgentResponse
import dev.ide.ui.backend.UiAgentToolCall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URI

internal class AgentBackend : AgentService {
    override suspend fun respond(request: UiAgentRequest, onTextDelta: (String) -> Unit): UiAgentResponse = withContext(Dispatchers.IO) {
        val base = request.config.baseUrl.trimEnd('/')
        val endpoint = if (base.endsWith("/responses")) base else "$base/responses"
        val body = buildRequestBody(request)
        val conn = (URI(endpoint).toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 30_000
            readTimeout = 120_000
            doOutput = true
            setRequestProperty("Authorization", "Bearer ${request.config.apiKey}")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "text/event-stream")
        }
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        if (code !in 200..299) {
            val raw = conn.errorStream?.use { input ->
                BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { it.readText() }
            }.orEmpty()
            return@withContext UiAgentResponse("请求失败 HTTP $code\n${raw.take(4000)}", raw = raw)
        }
        val contentType = conn.contentType.orEmpty()
        if ("text/event-stream" in contentType) {
            return@withContext readSse(conn, onTextDelta)
        }
        val raw = conn.inputStream.use { input ->
            BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { it.readText() }
        }
        if (raw.lineSequence().any { it.trimStart().startsWith("data:") }) {
            return@withContext parseSse(raw, onTextDelta)
        }
        val root = parseObject(raw)
        UiAgentResponse(
            text = extractResponseText(root),
            responseId = root?.string("id")?.takeIf { it.startsWith("resp_") },
            toolCalls = extractToolCalls(root),
            raw = raw,
        )
    }

    private fun buildRequestBody(request: UiAgentRequest): String {
        val root = JsonObject().apply {
            addProperty("model", request.config.model)
            add("input", request.input.toJson())
            addProperty("stream", true)
            addProperty("tool_choice", "auto")
            addProperty("parallel_tool_calls", true)
            addProperty("store", false)
            add("reasoning", JsonObject().apply { addProperty("effort", request.config.reasoningEffort) })
            if (request.tools.isNotEmpty()) {
                add(
                    "tools",
                    JsonArray().apply {
                        request.tools.forEach { tool ->
                            add(
                                JsonObject().apply {
                                    addProperty("type", "function")
                                    addProperty("name", tool.name)
                                    addProperty("description", tool.description)
                                    add("parameters", parseJson(tool.parametersJson) ?: JsonObject())
                                }
                            )
                        }
                    }
                )
            }
        }
        return gson.toJson(root)
    }

    private fun readSse(conn: HttpURLConnection, onTextDelta: (String) -> Unit): UiAgentResponse {
        val raw = StringBuilder()
        val text = StringBuilder()
        var responseId: String? = null
        val calls = ArrayList<UiAgentToolCall>()
        conn.inputStream.use { input ->
            BufferedReader(InputStreamReader(input, Charsets.UTF_8)).useLines { lines ->
                lines.forEach { line ->
                    raw.append(line).append('\n')
                    val trimmed = line.trim()
                    if (!trimmed.startsWith("data:")) return@forEach
                    val data = trimmed.removePrefix("data:").trim()
                    if (data.isEmpty() || data == "[DONE]") return@forEach
                    handleSseData(data, text, calls, onTextDelta) { id -> responseId = id }
                }
            }
        }
        return UiAgentResponse(text.toString(), responseId, calls.distinctBy { it.callId }, raw.toString())
    }

    private fun parseSse(raw: String, onTextDelta: (String) -> Unit): UiAgentResponse {
        val text = StringBuilder()
        var responseId: String? = null
        val calls = ArrayList<UiAgentToolCall>()
        raw.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("data:") }
            .map { it.removePrefix("data:").trim() }
            .filter { it.isNotEmpty() && it != "[DONE]" }
            .forEach { data ->
                handleSseData(data, text, calls, onTextDelta) { id -> responseId = id }
            }
        return UiAgentResponse(text.toString(), responseId, calls.distinctBy { it.callId }, raw)
    }

    private fun handleSseData(
        data: String,
        text: StringBuilder,
        calls: MutableList<UiAgentToolCall>,
        onTextDelta: (String) -> Unit,
        onResponseId: (String) -> Unit,
    ) {
        val event = parseObject(data) ?: return
        when (event.string("type")) {
            "response.output_text.delta" -> {
                val delta = event.string("delta").orEmpty()
                if (delta.isNotEmpty()) {
                    text.append(delta)
                    onTextDelta(delta)
                }
            }
            "response.output_item.done" -> {
                val item = event.obj("item") ?: return
                collectMessageText(item).takeIf { it.isNotEmpty() }?.let { value ->
                    text.append(value)
                }
                collectToolCall(item)?.let { calls += it }
            }
            "response.completed" -> {
                event.obj("response")?.string("id")?.let(onResponseId)
            }
        }
    }

    private fun UiAgentInputItem.toJson(): JsonObject =
        JsonObject().apply {
            addProperty("type", type)
            when (type) {
                "message" -> {
                    addProperty("role", role ?: "user")
                    add(
                        "content",
                        JsonArray().apply {
                            add(
                                JsonObject().apply {
                                    addProperty("type", "input_text")
                                    addProperty("text", content.orEmpty())
                                }
                            )
                        }
                    )
                }
                "function_call" -> {
                    callId?.let { addProperty("call_id", it) }
                    name?.let { addProperty("name", it) }
                    addProperty("arguments", argumentsJson.orEmpty())
                }
                "function_call_output" -> {
                    callId?.let { addProperty("call_id", it) }
                    addProperty("output", output.orEmpty())
                }
            }
        }

    private fun List<UiAgentInputItem>.toJson(): JsonArray =
        JsonArray().apply { forEach { add(it.toJson()) } }

    private fun extractResponseText(root: JsonObject?): String {
        val output = root?.array("output") ?: return ""
        return buildString {
            output.objects().forEach { append(collectMessageText(it)) }
        }
    }

    private fun extractToolCalls(root: JsonObject?): List<UiAgentToolCall> {
        val output = root?.array("output") ?: return emptyList()
        return output.objects().mapNotNull(::collectToolCall)
    }

    private fun collectToolCall(item: JsonObject): UiAgentToolCall? {
        if (item.string("type") != "function_call") return null
        val callId = item.string("call_id") ?: item.string("id")
        val name = item.string("name")
        val args = item.string("arguments").orEmpty()
        if (callId.isNullOrBlank() || name.isNullOrBlank()) return null
        return UiAgentToolCall(callId, name, args, parseStringArguments(args))
    }

    private fun collectMessageText(item: JsonObject): String {
        if (item.string("type") != "message") return ""
        val content = item.array("content") ?: return ""
        return buildString {
            content.objects().forEach { part ->
                if (part.string("type") == "output_text") append(part.string("text").orEmpty())
            }
        }
    }

    private fun parseStringArguments(argumentsJson: String): Map<String, String> {
        val args = parseObject(argumentsJson) ?: return emptyMap()
        return args.entrySet()
            .mapNotNull { (key, value) -> value.asStringOrNull()?.let { key to it } }
            .toMap()
    }

    private fun parseObject(text: String): JsonObject? =
        parseJson(text)?.takeIf { it.isJsonObject }?.asJsonObject

    private fun parseJson(text: String): JsonElement? =
        runCatching { JsonParser.parseString(text) }.getOrNull()

    private fun JsonObject.string(key: String): String? =
        get(key)?.asStringOrNull()

    private fun JsonObject.obj(key: String): JsonObject? =
        get(key)?.takeIf { it.isJsonObject }?.asJsonObject

    private fun JsonObject.array(key: String): JsonArray? =
        get(key)?.takeIf { it.isJsonArray }?.asJsonArray

    private fun JsonArray.objects(): List<JsonObject> =
        mapNotNull { it.takeIf { value -> value.isJsonObject }?.asJsonObject }

    private fun JsonElement.asStringOrNull(): String? =
        takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    private companion object {
        val gson = Gson()
    }
}
