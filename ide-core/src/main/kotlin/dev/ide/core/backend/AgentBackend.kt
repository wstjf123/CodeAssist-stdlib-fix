package dev.ide.core.backend

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import dev.ide.core.BackendContext
import dev.ide.ui.backend.AgentService
import dev.ide.ui.backend.UiAgentConversationItemRecord
import dev.ide.ui.backend.UiAgentConversationRecord
import dev.ide.ui.backend.UiAgentConversationStore
import dev.ide.ui.backend.UiAgentInputItem
import dev.ide.ui.backend.UiAgentRequest
import dev.ide.ui.backend.UiAgentResponse
import dev.ide.ui.backend.UiAgentTokenUsage
import dev.ide.ui.backend.UiAgentToolCall
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URI

internal class AgentBackend(private val ctx: BackendContext? = null) : AgentService {
    override fun loadConversationStore(): UiAgentConversationStore {
        val raw = ctx?.manager?.preference(CONVERSATIONS_PREF).orEmpty()
        if (raw.isBlank()) {
            return UiAgentConversationStore(
                activeConversationId = ctx?.manager?.preference(ACTIVE_CONVERSATION_PREF).orEmpty(),
                nextSeq = ctx?.manager?.preference(CONVERSATION_SEQ_PREF)?.toLongOrNull() ?: 0L,
            )
        }
        parseConversationStore(raw)?.let { return it }
        return parseLegacyConversationStore(raw).copy(
            activeConversationId = ctx?.manager?.preference(ACTIVE_CONVERSATION_PREF).orEmpty(),
            nextSeq = ctx?.manager?.preference(CONVERSATION_SEQ_PREF)?.toLongOrNull() ?: 0L,
        )
    }

    override fun saveConversationStore(store: UiAgentConversationStore) {
        ctx?.manager?.setPreference(CONVERSATIONS_PREF, gson.toJson(store))
        ctx?.manager?.setPreference(ACTIVE_CONVERSATION_PREF, store.activeConversationId)
        ctx?.manager?.setPreference(CONVERSATION_SEQ_PREF, store.nextSeq.toString())
    }

    override suspend fun respond(
        request: UiAgentRequest,
        onTextDelta: (String) -> Unit,
        onStreamChars: (Int) -> Unit,
    ): UiAgentResponse = withContext(Dispatchers.IO) {
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
        val cancellation = currentCoroutineContext()[Job]?.invokeOnCompletion { cause ->
            if (cause is CancellationException) conn.disconnect()
        }
        try {
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
                return@withContext readSse(conn, onTextDelta, onStreamChars)
            }
            val raw = conn.inputStream.use { input ->
                BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { it.readText() }
            }
            if (raw.lineSequence().any { it.trimStart().startsWith("data:") }) {
                return@withContext parseSse(raw, onTextDelta, onStreamChars)
            }
            onStreamChars(raw.length)
            val root = parseObject(raw)
            UiAgentResponse(
                text = extractResponseText(root),
                responseId = root?.string("id")?.takeIf { it.startsWith("resp_") },
                toolCalls = extractToolCalls(root),
                usage = extractUsage(root),
                raw = raw,
            )
        } finally {
            cancellation?.dispose()
        }
    }

    private fun buildRequestBody(request: UiAgentRequest): String {
        val root = JsonObject().apply {
            addProperty("model", request.config.model)
            if (request.instructions.isNotBlank()) addProperty("instructions", request.instructions)
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

    private fun readSse(
        conn: HttpURLConnection,
        onTextDelta: (String) -> Unit,
        onStreamChars: (Int) -> Unit,
    ): UiAgentResponse {
        val raw = StringBuilder()
        val state = SseResponseState()
        conn.inputStream.use { input ->
            BufferedReader(InputStreamReader(input, Charsets.UTF_8)).useLines { lines ->
                lines.forEach { line ->
                    raw.append(line).append('\n')
                    val trimmed = line.trim()
                    if (!trimmed.startsWith("data:")) return@forEach
                    val data = trimmed.removePrefix("data:").trim()
                    if (data.isEmpty()) return@forEach
                    onStreamChars(data.length)
                    if (data == "[DONE]") {
                        state.completed = true
                        return@forEach
                    }
                    handleSseData(data, state, onTextDelta)
                }
            }
        }
        requireCompletedStream(state.completed)
        return state.toResponse(raw.toString())
    }

    private fun parseSse(
        raw: String,
        onTextDelta: (String) -> Unit,
        onStreamChars: (Int) -> Unit,
    ): UiAgentResponse {
        val state = SseResponseState()
        raw.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("data:") }
            .map { it.removePrefix("data:").trim() }
            .filter { it.isNotEmpty() }
            .forEach { data ->
                onStreamChars(data.length)
                if (data == "[DONE]") {
                    state.completed = true
                    return@forEach
                }
                handleSseData(data, state, onTextDelta)
            }
        requireCompletedStream(state.completed)
        return state.toResponse(raw)
    }

    private fun handleSseData(
        data: String,
        state: SseResponseState,
        onTextDelta: (String) -> Unit,
    ) {
        val event = parseObject(data) ?: return
        when (event.string("type")) {
            "response.output_item.added" -> {
                event.obj("item")?.let { item ->
                    state.activeItem = ActiveOutputItem(
                        id = item.string("id"),
                        outputIndex = event.int("output_index"),
                        type = item.string("type"),
                        callId = item.string("call_id"),
                        name = item.string("name"),
                        arguments = item.string("arguments").orEmpty(),
                    )
                }
            }
            "response.output_text.delta" -> {
                val delta = event.string("delta").orEmpty()
                if (delta.isNotEmpty() && state.acceptsTextDelta(event)) {
                    state.text.append(delta)
                    onTextDelta(delta)
                }
            }
            "response.output_item.done" -> {
                val item = event.obj("item") ?: return
                collectMessageText(item).takeIf { it.isNotEmpty() && state.text.isEmpty() }?.let { value ->
                    state.text.append(value)
                }
                collectToolCall(item, state.activeItem)?.let { state.calls += it }
                if (state.activeItem?.matches(event, item) == true) {
                    state.activeItem = null
                }
            }
            "response.function_call_arguments.delta" -> {
                state.activeItem?.appendFunctionArguments(event)
            }
            "response.function_call_arguments.done" -> {
                state.activeItem?.setFunctionArguments(event)
            }
            "response.completed" -> {
                event.obj("response")?.let { response ->
                    response.string("id")?.let { state.responseId = it }
                    state.usage = extractUsage(response)
                }
                state.completed = true
            }
            "response.failed" -> throw IllegalStateException(responseFailureMessage(event, "response failed"))
            "response.incomplete" -> throw IllegalStateException(responseFailureMessage(event, "response incomplete"))
        }
    }

    private fun requireCompletedStream(completed: Boolean) {
        if (!completed) {
            throw IllegalStateException("stream disconnected before response.completed")
        }
    }

    private fun responseFailureMessage(event: JsonObject, fallback: String): String {
        val response = event.obj("response")
        val error = event.obj("error") ?: response?.obj("error")
        return error?.string("message")
            ?: error?.string("code")
            ?: response?.string("incomplete_details")
            ?: fallback
    }

    private inner class SseResponseState {
        val text = StringBuilder()
        val calls = ArrayList<UiAgentToolCall>()
        var activeItem: ActiveOutputItem? = null
        var responseId: String? = null
        var usage: UiAgentTokenUsage? = null
        var completed: Boolean = false

        fun acceptsTextDelta(event: JsonObject): Boolean {
            val active = activeItem ?: return true
            val eventItemId = event.string("item_id")
            val eventOutputIndex = event.int("output_index")
            return active.isMessage &&
                (eventItemId == null || active.id == null || eventItemId == active.id) &&
                (eventOutputIndex == null || active.outputIndex == null || eventOutputIndex == active.outputIndex)
        }

        fun toResponse(raw: String): UiAgentResponse =
            UiAgentResponse(
                text = text.toString(),
                responseId = responseId,
                toolCalls = calls.distinctBy { it.callId },
                usage = usage,
                raw = raw,
            )
    }

    private inner class ActiveOutputItem(
        val id: String?,
        val outputIndex: Int?,
        val type: String?,
        val callId: String?,
        val name: String?,
        arguments: String,
    ) {
        val isMessage: Boolean get() = type == "message"
        val isFunctionCall: Boolean get() = type == "function_call"
        private val argumentsBuilder = StringBuilder(arguments)
        val argumentsText: String get() = argumentsBuilder.toString()

        fun matches(event: JsonObject, item: JsonObject): Boolean {
            val doneId = item.string("id")
            val doneIndex = event.int("output_index")
            return (id == null || doneId == null || id == doneId) &&
                (outputIndex == null || doneIndex == null || outputIndex == doneIndex)
        }

        fun appendFunctionArguments(event: JsonObject) {
            if (!isFunctionCall || !matchesEvent(event)) return
            argumentsBuilder.append(event.string("delta").orEmpty())
        }

        fun setFunctionArguments(event: JsonObject) {
            if (!isFunctionCall || !matchesEvent(event)) return
            val arguments = event.string("arguments") ?: return
            argumentsBuilder.setLength(0)
            argumentsBuilder.append(arguments)
        }

        private fun matchesEvent(event: JsonObject): Boolean {
            val eventItemId = event.string("item_id")
            val eventOutputIndex = event.int("output_index")
            return (eventItemId == null || id == null || eventItemId == id) &&
                (eventOutputIndex == null || outputIndex == null || eventOutputIndex == outputIndex)
        }
    }

    private fun UiAgentInputItem.toJson(): JsonObject =
        JsonObject().apply {
            addProperty("type", type)
            when (type) {
                "message" -> {
                    val itemRole = role ?: "user"
                    addProperty("role", itemRole)
                    add(
                        "content",
                        JsonArray().apply {
                            add(
                                JsonObject().apply {
                                    addProperty("type", if (itemRole == "assistant") "output_text" else "input_text")
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

    private fun List<UiAgentInputItem>.toJson(): JsonArray {
        val items = this
        return JsonArray().apply { items.forEach { add(it.toJson()) } }
    }

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

    private fun extractUsage(root: JsonObject?): UiAgentTokenUsage? {
        val usage = root?.obj("usage") ?: return null
        val input = usage.int("input_tokens") ?: 0
        val output = usage.int("output_tokens") ?: 0
        val total = usage.int("total_tokens") ?: input + output
        if (input == 0 && output == 0 && total == 0) return null
        return UiAgentTokenUsage(
            inputTokens = input,
            outputTokens = output,
            totalTokens = total,
        )
    }

    private fun collectToolCall(item: JsonObject, active: ActiveOutputItem? = null): UiAgentToolCall? {
        if (item.string("type") != "function_call") return null
        val callId = item.string("call_id") ?: active?.callId ?: item.string("id")
        val name = item.string("name") ?: active?.name
        val args = item.string("arguments") ?: active?.argumentsText.orEmpty()
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

    private fun parseConversationStore(raw: String): UiAgentConversationStore? =
        runCatching {
            gson.fromJson<UiAgentConversationStore>(raw, conversationStoreType)
        }.getOrNull()

    private fun parseLegacyConversationStore(raw: String): UiAgentConversationStore {
        val conversations = ArrayList<UiAgentConversationRecord>()
        var current: LegacyConversationBuilder? = null
        raw.lineSequence().forEach { line ->
            if (line.isBlank()) return@forEach
            val parts = line.split('\t')
            when (parts.firstOrNull()) {
                "C" -> {
                    if (parts.size < 5) return@forEach
                    val record = LegacyConversationBuilder(
                        id = parts[1].decodeLegacyAgentField(),
                        title = parts[2].decodeLegacyAgentField().ifBlank { "新对话" },
                        createdSeq = parts[3].toLongOrNull() ?: 0L,
                        updatedSeq = parts[4].toLongOrNull() ?: 0L,
                    )
                    conversations += record.toRecord()
                    current = record
                }
                "I" -> {
                    val builder = current ?: return@forEach
                    if (parts.size < 8) return@forEach
                    builder.items += UiAgentConversationItemRecord(
                        type = parts[1].decodeLegacyAgentField(),
                        role = parts[2].decodeLegacyAgentField().ifBlank { null },
                        text = parts[3].decodeLegacyAgentField(),
                        callId = parts[4].decodeLegacyAgentField().ifBlank { null },
                        name = parts[5].decodeLegacyAgentField().ifBlank { null },
                        argumentsJson = parts[6].decodeLegacyAgentField().ifBlank { null },
                    )
                    conversations[conversations.lastIndex] = builder.toRecord()
                }
            }
        }
        return UiAgentConversationStore(conversations = conversations.sortedByDescending { it.updatedSeq })
    }

    private fun JsonObject.string(key: String): String? =
        get(key)?.asStringOrNull()

    private fun JsonObject.int(key: String): Int? =
        get(key)?.asIntOrNull()

    private fun JsonObject.obj(key: String): JsonObject? =
        get(key)?.takeIf { it.isJsonObject }?.asJsonObject

    private fun JsonObject.array(key: String): JsonArray? =
        get(key)?.takeIf { it.isJsonArray }?.asJsonArray

    private fun JsonArray.objects(): List<JsonObject> =
        mapNotNull { it.takeIf { value -> value.isJsonObject }?.asJsonObject }

    private fun JsonElement.asStringOrNull(): String? =
        takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    private fun JsonElement.asIntOrNull(): Int? =
        takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt

    private class LegacyConversationBuilder(
        val id: String,
        val title: String,
        val createdSeq: Long,
        val updatedSeq: Long,
        val items: MutableList<UiAgentConversationItemRecord> = ArrayList(),
    ) {
        fun toRecord(): UiAgentConversationRecord =
            UiAgentConversationRecord(id, title, createdSeq, updatedSeq, items.toList())
    }

    private companion object {
        private const val CONVERSATIONS_PREF = "agent.conversations"
        private const val ACTIVE_CONVERSATION_PREF = "agent.activeConversationId"
        private const val CONVERSATION_SEQ_PREF = "agent.conversationSeq"
        val gson = Gson()
        val conversationStoreType = object : TypeToken<UiAgentConversationStore>() {}.type
    }
}

private fun String.decodeLegacyAgentField(): String {
    if ('%' !in this) return this
    val out = StringBuilder(length)
    var i = 0
    while (i < length) {
        if (this[i] == '%' && i + 2 < length) {
            val decoded = when (substring(i + 1, i + 3)) {
                "25" -> '%'
                "0A" -> '\n'
                "0D" -> '\r'
                "09" -> '\t'
                else -> null
            }
            if (decoded != null) {
                out.append(decoded)
                i += 3
            } else {
                out.append(this[i])
                i++
            }
        } else {
            out.append(this[i])
            i++
        }
    }
    return out.toString()
}
