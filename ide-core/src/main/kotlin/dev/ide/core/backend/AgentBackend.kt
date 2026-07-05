package dev.ide.core.backend

import dev.ide.ui.backend.AgentService
import dev.ide.ui.backend.UiAgentRequest
import dev.ide.ui.backend.UiAgentResponse
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
            setRequestProperty("Accept", if (request.stream) "text/event-stream" else "application/json")
        }
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val raw = stream?.use { input ->
            BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { it.readText() }
        }.orEmpty()
        if (code !in 200..299) {
            return@withContext UiAgentResponse("请求失败 HTTP $code\n${raw.take(4000)}", raw)
        }
        UiAgentResponse(extractResponseText(raw).ifBlank { raw.take(12000) }, raw)
    }

    private fun buildRequestBody(request: UiAgentRequest): String {
        val input = buildString {
            append(request.prompt.trim())
            if (request.context.isNotBlank()) {
                append("\n\n--- IDE context ---\n")
                append(request.context)
            }
        }
        return buildString {
            append('{')
            append("\"model\":\"").append(jsonEscape(request.config.model)).append("\",")
            append("\"input\":\"").append(jsonEscape(input)).append("\",")
            append("\"stream\":").append(request.stream).append(',')
            append("\"reasoning\":{\"effort\":\"").append(jsonEscape(request.config.reasoningEffort)).append("\"}")
            append('}')
        }
    }

    private fun extractResponseText(raw: String): String {
        if (raw.startsWith("data:") || raw.contains("\ndata:")) {
            val out = StringBuilder()
            raw.lineSequence()
                .map { it.trim() }
                .filter { it.startsWith("data:") }
                .map { it.removePrefix("data:").trim() }
                .filter { it.isNotEmpty() && it != "[DONE]" }
                .forEach { chunk ->
                    extractJsonStringAfter(chunk, "\"delta\"")?.let { out.append(it) }
                    extractJsonStringAfter(chunk, "\"text\"")?.let { out.append(it) }
                    extractJsonStringAfter(chunk, "\"output_text\"")?.let { out.append(it) }
                }
            if (out.isNotBlank()) return out.toString()
        }
        extractJsonStringAfter(raw, "\"output_text\"")?.let { return it }
        val values = Regex("\"type\"\\s*:\\s*\"output_text\"[\\s\\S]*?\"text\"\\s*:\\s*\"")
            .findAll(raw)
            .mapNotNull { match -> readJsonString(raw, match.range.last + 1) }
            .toList()
        if (values.isNotEmpty()) return values.joinToString("")
        return extractJsonStringAfter(raw, "\"text\"").orEmpty()
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
