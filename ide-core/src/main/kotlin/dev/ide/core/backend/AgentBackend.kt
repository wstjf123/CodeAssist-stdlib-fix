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
    override suspend fun respond(request: UiAgentRequest): UiAgentResponse = withContext(Dispatchers.IO) {
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
            setRequestProperty("Accept", "application/json")
        }
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val raw = stream?.use { input ->
            BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { it.readText() }
        }.orEmpty()
        if (code !in 200..299) {
            return@withContext UiAgentResponse("请求失败 HTTP $code\n${raw.take(4000)}", raw = raw)
        }
        UiAgentResponse(
            text = extractResponseText(raw),
            responseId = extractJsonStringAfter(raw, "\"id\""),
            toolCalls = extractToolCalls(raw),
            raw = raw,
        )
    }

    private fun buildRequestBody(request: UiAgentRequest): String = buildString {
        append('{')
        append("\"model\":\"").append(jsonEscape(request.config.model)).append("\",")
        append("\"input\":").append(request.input).append(',')
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

    private fun extractResponseText(raw: String): String {
        extractJsonStringAfter(raw, "\"output_text\"")?.let { return it }
        val values = Regex("\"type\"\\s*:\\s*\"output_text\"[\\s\\S]*?\"text\"\\s*:\\s*\"")
            .findAll(raw)
            .mapNotNull { match -> readJsonString(raw, match.range.last + 1) }
            .toList()
        if (values.isNotEmpty()) return values.joinToString("")
        return ""
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
}
