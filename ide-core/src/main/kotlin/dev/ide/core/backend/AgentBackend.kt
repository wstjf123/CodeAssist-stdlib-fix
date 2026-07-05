package dev.ide.core.backend

import dev.ide.ui.backend.AgentService
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
        UiAgentResponse(
            text = extractResponseText(raw),
            responseId = extractResponseId(raw),
            toolCalls = extractToolCalls(raw),
            raw = raw,
        )
    }

    private fun buildRequestBody(request: UiAgentRequest): String = buildString {
        append('{')
        append("\"model\":\"").append(jsonEscape(request.config.model)).append("\",")
        append("\"input\":").append(request.input).append(',')
        append("\"stream\":true,")
        append("\"reasoning\":{\"effort\":\"").append(jsonEscape(request.config.reasoningEffort)).append("\"}")
        request.previousResponseId?.takeIf { it.isNotBlank() }?.let {
            append(",\"previous_response_id\":\"").append(jsonEscape(it)).append("\"")
        }
        if (request.tools.isNotEmpty()) {
            append(",\"tools\":[")
            request.tools.forEachIndexed { index, tool ->
                if (index > 0) append(',')
                append('{')
                append("\"type\":\"function\",")
                append("\"name\":\"").append(jsonEscape(tool.name)).append("\",")
                append("\"description\":\"").append(jsonEscape(tool.description)).append("\",")
                append("\"parameters\":").append(tool.parametersJson)
                append('}')
            }
            append(']')
        }
        append('}')
    }

    private fun readSse(conn: HttpURLConnection, onTextDelta: (String) -> Unit): UiAgentResponse {
        val raw = StringBuilder()
        val text = StringBuilder()
        var responseId: String? = null
        val calls = ArrayList<UiAgentToolCall>()
        val partialCalls = LinkedHashMap<String, PartialToolCall>()
        conn.inputStream.use { input ->
            BufferedReader(InputStreamReader(input, Charsets.UTF_8)).useLines { lines ->
                lines.forEach { line ->
                    raw.append(line).append('\n')
                    val trimmed = line.trim()
                    if (!trimmed.startsWith("data:")) return@forEach
                    val data = trimmed.removePrefix("data:").trim()
                    if (data.isEmpty() || data == "[DONE]") return@forEach
                    handleSseData(data, text, calls, partialCalls, onTextDelta) { id ->
                        if (responseId == null) responseId = id
                    }
                }
            }
        }
        partialCalls.values.forEach { partial ->
            val callId = partial.callId
            val name = partial.name
            if (!callId.isNullOrBlank() && !name.isNullOrBlank()) {
                calls += UiAgentToolCall(callId, name, partial.arguments.toString())
            }
        }
        return UiAgentResponse(text.toString(), responseId, calls.distinctBy { it.callId }, raw.toString())
    }

    private fun parseSse(raw: String, onTextDelta: (String) -> Unit): UiAgentResponse {
        val text = StringBuilder()
        var responseId: String? = null
        val calls = ArrayList<UiAgentToolCall>()
        val partialCalls = LinkedHashMap<String, PartialToolCall>()
        raw.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("data:") }
            .map { it.removePrefix("data:").trim() }
            .filter { it.isNotEmpty() && it != "[DONE]" }
            .forEach { data ->
                handleSseData(data, text, calls, partialCalls, onTextDelta) { id ->
                    if (responseId == null) responseId = id
                }
            }
        partialCalls.values.forEach { partial ->
            val callId = partial.callId
            val name = partial.name
            if (!callId.isNullOrBlank() && !name.isNullOrBlank()) {
                calls += UiAgentToolCall(callId, name, partial.arguments.toString())
            }
        }
        return UiAgentResponse(text.toString(), responseId, calls.distinctBy { it.callId }, raw)
    }

    private fun handleSseData(
        data: String,
        text: StringBuilder,
        calls: MutableList<UiAgentToolCall>,
        partialCalls: MutableMap<String, PartialToolCall>,
        onTextDelta: (String) -> Unit,
        onResponseId: (String) -> Unit,
    ) {
        val event = MiniJson.parse(data) as? Map<*, *> ?: return
        val type = event.string("type").orEmpty()

        event.string("response_id")?.let(onResponseId)
        val response = event.map("response")
        response?.string("id")?.let(onResponseId)
        if (type == "response.created" || type == "response.completed") {
            event.string("id")?.takeIf { it.startsWith("resp_") }?.let(onResponseId)
        }

        if (type.endsWith("output_text.delta") || type.endsWith("text.delta")) {
            val delta = event.string("delta").orEmpty()
            if (delta.isNotEmpty()) {
                text.append(delta)
                onTextDelta(delta)
            }
            return
        }

        if (type == "response.function_call_arguments.delta") {
            val partial = partialForEvent(event, partialCalls)
            event.string("delta")?.let { partial.arguments.append(it) }
            return
        }

        if (type == "response.function_call_arguments.done") {
            val partial = partialForEvent(event, partialCalls)
            event.string("arguments")?.let {
                partial.arguments.clear()
                partial.arguments.append(it)
            }
            return
        }

        event.map("item")?.let { item ->
            if (item.string("type") == "function_call") {
                val key = event.string("item_id") ?: item.string("id") ?: item.string("call_id") ?: partialCalls.size.toString()
                val partial = partialCalls.getOrPut(key) { PartialToolCall() }
                item.string("call_id")?.let { partial.callId = it }
                item.string("name")?.let { partial.name = it }
                item.string("arguments")?.takeIf { it.isNotEmpty() }?.let {
                    partial.arguments.clear()
                    partial.arguments.append(it)
                }
            }
        }

        if (type.endsWith(".done") || type == "response.completed") {
            collectToolCalls(event).forEach { call -> calls += call }
            if (calls.isEmpty()) extractToolCalls(data).forEach { call -> calls += call }
        }
    }

    private fun partialForEvent(event: Map<*, *>, partialCalls: MutableMap<String, PartialToolCall>): PartialToolCall {
        val key = event.string("item_id")
            ?: event.string("output_index")
            ?: event.string("call_id")
            ?: partialCalls.size.toString()
        val partial = partialCalls.getOrPut(key) { PartialToolCall() }
        event.string("call_id")?.let { partial.callId = it }
        event.string("name")?.let { partial.name = it }
        return partial
    }

    private fun collectToolCalls(value: Any?): List<UiAgentToolCall> {
        val out = ArrayList<UiAgentToolCall>()
        fun walk(v: Any?) {
            when (v) {
                is Map<*, *> -> {
                    if (v.string("type") == "function_call") {
                        val callId = v.string("call_id") ?: v.string("id")
                        val name = v.string("name")
                        val args = v.string("arguments").orEmpty()
                        if (!callId.isNullOrBlank() && !name.isNullOrBlank()) {
                            out += UiAgentToolCall(callId, name, args)
                        }
                    }
                    v.values.forEach(::walk)
                }
                is List<*> -> v.forEach(::walk)
            }
        }
        walk(value)
        return out.distinctBy { it.callId }
    }

    private class PartialToolCall {
        var callId: String? = null
        var name: String? = null
        val arguments = StringBuilder()
    }

    private fun extractResponseText(raw: String): String {
        extractJsonStringAfter(raw, "\"output_text\"")?.let { return it }
        val values = Regex("\"type\"\\s*:\\s*\"output_text\"[\\s\\S]*?\"text\"\\s*:\\s*\"")
            .findAll(raw)
            .mapNotNull { match -> readJsonString(raw, match.range.last + 1) }
            .toList()
        if (values.isNotEmpty()) return values.joinToString("")
        return ""
    }

    private fun extractResponseId(raw: String): String? {
        val parsed = MiniJson.parse(raw) as? Map<*, *>
        parsed?.string("id")?.takeIf { it.startsWith("resp_") }?.let { return it }
        return Regex("\"id\"\\s*:\\s*\"(resp_[^\"]+)\"").find(raw)?.groupValues?.getOrNull(1)
    }

    private fun extractToolCalls(raw: String): List<UiAgentToolCall> {
        val calls = ArrayList<UiAgentToolCall>()
        var index = 0
        while (true) {
            val typeAt = raw.indexOf("\"type\"", index)
            if (typeAt < 0) break
            val value = extractJsonStringAfter(raw.substring(typeAt), "\"type\"")
            if (value != "function_call") {
                index = typeAt + 6
                continue
            }
            val objStart = raw.lastIndexOf('{', typeAt).takeIf { it >= 0 } ?: typeAt
            val objEnd = findObjectEnd(raw, objStart) ?: run {
                index = typeAt + 6
                continue
            }
            val obj = raw.substring(objStart, objEnd + 1)
            val callId = extractJsonStringAfter(obj, "\"call_id\"")
                ?: extractJsonStringAfter(obj, "\"id\"")
                ?: ""
            val name = extractJsonStringAfter(obj, "\"name\"").orEmpty()
            val args = extractJsonStringAfter(obj, "\"arguments\"").orEmpty()
            if (callId.isNotBlank() && name.isNotBlank()) {
                calls += UiAgentToolCall(callId, name, args)
            }
            index = objEnd + 1
        }
        return calls
    }

    private fun findObjectEnd(text: String, start: Int): Int? {
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until text.length) {
            val c = text[i]
            if (inString) {
                if (escaped) escaped = false
                else if (c == '\\') escaped = true
                else if (c == '"') inString = false
                continue
            }
            when (c) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        return null
    }

    private fun extractJsonStringAfter(json: String, key: String): String? {
        val keyAt = json.indexOf(key)
        if (keyAt < 0) return null
        val colon = json.indexOf(':', keyAt + key.length)
        if (colon < 0) return null
        val quote = json.indexOf('"', colon + 1)
        if (quote < 0) return null
        return readJsonString(json, quote + 1)
    }

    private fun readJsonString(json: String, start: Int): String? {
        val out = StringBuilder()
        var i = start
        while (i < json.length) {
            val c = json[i++]
            when (c) {
                '"' -> return out.toString()
                '\\' -> {
                    if (i >= json.length) return null
                    when (val esc = json[i++]) {
                        '"', '\\', '/' -> out.append(esc)
                        'b' -> out.append('\b')
                        'f' -> out.append('\u000C')
                        'n' -> out.append('\n')
                        'r' -> out.append('\r')
                        't' -> out.append('\t')
                        'u' -> {
                            if (i + 4 > json.length) return null
                            out.append(json.substring(i, i + 4).toIntOrNull(16)?.toChar() ?: return null)
                            i += 4
                        }
                    }
                }
                else -> out.append(c)
            }
        }
        return null
    }

    private fun jsonEscape(value: String): String = buildString {
        value.forEach { c ->
            when (c) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c < ' ') append("\\u").append(c.code.toString(16).padStart(4, '0')) else append(c)
            }
        }
    }

    private fun Map<*, *>.string(key: String): String? = this[key] as? String
    private fun Map<*, *>.map(key: String): Map<*, *>? = this[key] as? Map<*, *>

    private object MiniJson {
        fun parse(text: String): Any? = runCatching {
            val parser = Parser(text)
            val value = parser.readValue()
            parser.skipWs()
            if (!parser.atEnd()) return@runCatching null
            value
        }.getOrNull()

        private class Parser(private val s: String) {
            private var pos = 0

            fun atEnd(): Boolean = pos >= s.length

            fun skipWs() {
                while (pos < s.length && s[pos].isWhitespace()) pos++
            }

            fun readValue(): Any? {
                skipWs()
                if (pos >= s.length) error("unexpected end")
                return when (val c = s[pos]) {
                    '{' -> readObject()
                    '[' -> readArray()
                    '"' -> readString()
                    't', 'f' -> readBoolean()
                    'n' -> readNull()
                    else -> if (c == '-' || c in '0'..'9') readNumber() else error("unexpected char")
                }
            }

            private fun readObject(): Map<String, Any?> {
                expect('{')
                val map = LinkedHashMap<String, Any?>()
                skipWs()
                if (peek() == '}') {
                    pos++
                    return map
                }
                while (true) {
                    skipWs()
                    val key = readString()
                    skipWs()
                    expect(':')
                    map[key] = readValue()
                    skipWs()
                    when (next()) {
                        ',' -> continue
                        '}' -> return map
                        else -> error("bad object")
                    }
                }
            }

            private fun readArray(): List<Any?> {
                expect('[')
                val list = ArrayList<Any?>()
                skipWs()
                if (peek() == ']') {
                    pos++
                    return list
                }
                while (true) {
                    list += readValue()
                    skipWs()
                    when (next()) {
                        ',' -> continue
                        ']' -> return list
                        else -> error("bad array")
                    }
                }
            }

            private fun readString(): String {
                expect('"')
                val out = StringBuilder()
                while (true) {
                    if (pos >= s.length) error("unterminated string")
                    when (val c = s[pos++]) {
                        '"' -> return out.toString()
                        '\\' -> {
                            if (pos >= s.length) error("bad escape")
                            when (val esc = s[pos++]) {
                                '"', '\\', '/' -> out.append(esc)
                                'b' -> out.append('\b')
                                'f' -> out.append('\u000C')
                                'n' -> out.append('\n')
                                'r' -> out.append('\r')
                                't' -> out.append('\t')
                                'u' -> {
                                    if (pos + 4 > s.length) error("bad unicode escape")
                                    out.append(s.substring(pos, pos + 4).toInt(16).toChar())
                                    pos += 4
                                }
                                else -> error("bad escape")
                            }
                        }
                        else -> out.append(c)
                    }
                }
            }

            private fun readBoolean(): Boolean =
                when {
                    s.startsWith("true", pos) -> {
                        pos += 4
                        true
                    }
                    s.startsWith("false", pos) -> {
                        pos += 5
                        false
                    }
                    else -> error("bad boolean")
                }

            private fun readNull(): Any? {
                if (!s.startsWith("null", pos)) error("bad null")
                pos += 4
                return null
            }

            private fun readNumber(): Number {
                val start = pos
                if (peek() == '-') pos++
                while (pos < s.length && s[pos] in "0123456789.eE+-") pos++
                val token = s.substring(start, pos)
                return if (token.any { it == '.' || it == 'e' || it == 'E' }) token.toDouble() else token.toLong()
            }

            private fun peek(): Char = s[pos]
            private fun next(): Char = s[pos++]
            private fun expect(c: Char) {
                if (pos >= s.length || s[pos] != c) error("expected char")
                pos++
            }
        }
    }
}
