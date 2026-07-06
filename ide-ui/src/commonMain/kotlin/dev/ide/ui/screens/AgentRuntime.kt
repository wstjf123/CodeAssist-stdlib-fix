package dev.ide.ui.screens

import androidx.compose.ui.text.TextRange
import dev.ide.ui.AgentConversationItem
import dev.ide.ui.IdeUiState
import dev.ide.ui.backend.TreeNode
import dev.ide.ui.backend.UiAgentContentItem
import dev.ide.ui.backend.UiAgentConfig
import dev.ide.ui.backend.UiAgentInputItem
import dev.ide.ui.backend.UiAgentRequest
import dev.ide.ui.backend.UiAgentTokenUsage
import dev.ide.ui.backend.UiAgentTool
import dev.ide.ui.backend.UiAgentToolCall
import dev.ide.ui.backend.UiComposePreview
import dev.ide.ui.backend.UiDiagnostic
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

internal suspend fun runAgentLoop(
    state: IdeUiState,
    messages: MutableList<AgentConversationItem>,
    onTextDelta: (String) -> Unit,
    onStreamChars: (Int) -> Unit,
): String {
    val config = UiAgentConfig(
        baseUrl = state.agentConfig.baseUrl,
        apiKey = state.agentConfig.apiKey,
        model = state.agentConfig.model,
        reasoningEffort = state.agentConfig.reasoningEffort,
    )
    val tools = agentTools()
    repeat(8) {
        var receivedTextDelta = false
        val response = state.backend.agent.respond(
            request = UiAgentRequest(
                config = config,
                instructions = agentInstructions(),
                input = compactAgentInput(messages),
                tools = tools,
            ),
            onTextDelta = { delta ->
                if (isUsefulAgentText(delta)) {
                    receivedTextDelta = true
                    onTextDelta(delta)
                }
            },
            onStreamChars = onStreamChars,
        )
        if (response.toolCalls.isEmpty()) {
            if (maybeAutoCompactForUsage(messages, response.usage)) {
                state.recordAgentChanged()
            }
            if (receivedTextDelta && isUsefulAgentText(response.text)) return ""
            if (isUsefulAgentText(response.text)) return response.text
            return "完成。"
        }
        removeTrailingBlankAssistantMessage(messages)
        response.toolCalls.forEach { call ->
            messages += AgentConversationItem(
                type = "function_call",
                callId = call.callId,
                name = call.name,
                argumentsJson = call.argumentsJson,
            )
        }
        state.recordAgentChanged()
        executeAgentToolCalls(state, response.toolCalls).forEach { result ->
            messages += AgentConversationItem(
                type = "function_call_output",
                text = result.output.text,
                callId = result.call.callId,
                name = result.call.name,
                argumentsJson = result.call.argumentsJson,
                toolSuccess = result.output.success,
                contentItems = result.output.contentItems,
            )
        }
        maybeAutoCompactForUsage(messages, response.usage)
        state.recordAgentChanged()
    }
    return "工具调用次数过多，已停止。"
}

internal fun appendAgentDelta(messages: MutableList<AgentConversationItem>, delta: String) {
    val last = messages.lastOrNull()
    val msg = if (last?.type == "message" && last.role == "assistant") {
        last
    } else {
        AgentConversationItem("message", "assistant", "").also { messages += it }
    }
    msg.text += delta
}

private fun agentInstructions(): String =
    "You are an AI coding agent embedded in CodeAssist IDE. " +
        "Use tools to inspect the workspace, diagnostics, logs, and to modify the currently edited buffer when needed. " +
        "When modifying code, use apply_patch with a unified patch for the current editor file."

private fun compactAgentInput(messages: List<AgentConversationItem>): List<UiAgentInputItem> {
    val input = ArrayList<UiAgentInputItem>()
    normalizeAgentHistoryForPrompt(messages).forEach { item ->
        when (item.type) {
            "message" -> if (item.text.isNotBlank()) {
                input += UiAgentInputItem(
                    type = "message",
                    role = item.role ?: "user",
                    content = item.text,
                )
            }
            "compaction" -> if (item.text.isNotBlank()) {
                input += UiAgentInputItem(
                    type = "message",
                    role = item.role ?: "user",
                    content = item.text,
                )
            }
            "function_call" -> {
                input += UiAgentInputItem(
                    type = "function_call",
                    callId = item.callId,
                    name = item.name,
                    argumentsJson = item.argumentsJson,
                )
            }
            "function_call_output" -> {
                input += UiAgentInputItem(
                    type = "function_call_output",
                    callId = item.callId,
                    output = item.modelVisibleToolOutput(),
                    outputContent = item.modelVisibleToolContent(),
                    outputSuccess = item.toolSuccess,
                )
            }
        }
    }
    return input
}

private fun buildCompactedSummary(items: List<AgentConversationItem>): String? {
    if (items.isEmpty()) return null
    val lines = ArrayList<String>()
    items.forEach { item ->
        when {
            item.type == "message" && item.role == "user" -> {
                lines += "User: ${item.text.oneLineForSummary()}"
            }
            item.type == "message" && item.role == "assistant" -> {
                lines += "Assistant: ${item.text.oneLineForSummary()}"
            }
            item.type == "function_call" -> {
                lines += "Tool call: ${item.name.orEmpty()} ${item.argumentsJson.orEmpty().oneLineForSummary(220)}"
            }
            item.type == "function_call_output" -> {
                val status = if (item.toolSuccess == false) "failed" else "completed"
                lines += "Tool output ($status): ${item.name.orEmpty()} ${item.text.oneLineForSummary(220)}"
            }
            item.type == "compaction" -> {
                lines += "Previous summary: ${item.text.oneLineForSummary(720)}"
            }
        }
    }
    if (lines.isEmpty()) return null
    return "$COMPACT_SUMMARY_PREFIX\nEarlier conversation was compacted. Preserve these facts and decisions:\n" +
        lines.takeLast(80).joinToString("\n")
}

private fun buildCompactedHistory(
    userMessages: List<String>,
    summaryText: String,
): List<AgentConversationItem> {
    val out = ArrayList<AgentConversationItem>()
    selectTokenLimitedUserMessages(userMessages).forEach { message ->
        out += AgentConversationItem("message", "user", message)
    }
    out += AgentConversationItem(
        type = "compaction",
        role = "user",
        text = summaryText.ifBlank { "$COMPACT_SUMMARY_PREFIX\n(no summary available)" },
    )
    return out
}

private fun selectTokenLimitedUserMessages(messages: List<String>): List<String> {
    val selected = ArrayList<String>()
    var remaining = COMPACT_USER_MESSAGE_MAX_TOKENS
    for (message in messages.asReversed()) {
        if (remaining <= 0) break
        val tokens = approximateTokenCount(message)
        if (tokens <= remaining) {
            selected += message
            remaining -= tokens
        } else {
            selected += truncateToApproxTokenCount(message, remaining)
            break
        }
    }
    selected.reverse()
    return selected
}

private fun collectRealUserMessages(items: List<AgentConversationItem>): List<String> =
    items.mapNotNull { item ->
        if (item.type == "message" && item.role == "user" && !isSummaryMessage(item.text)) {
            item.text.takeIf { it.isNotBlank() }
        } else {
            null
        }
    }

private fun isSummaryMessage(text: String): Boolean =
    text.startsWith("$COMPACT_SUMMARY_PREFIX\n")

private fun AgentConversationItem.modelVisibleToolOutput(): String {
    if (toolSuccess != false) return text
    return "success=false\n$text"
}

private fun AgentConversationItem.modelVisibleToolContent(): List<UiAgentContentItem> {
    if (contentItems.isEmpty()) return emptyList()
    if (toolSuccess != false) return contentItems
    return listOf(UiAgentContentItem(type = "input_text", text = modelVisibleToolOutput()))
}

private fun String.oneLineForSummary(limit: Int = 360): String {
    val value = trim().replace(Regex("\\s+"), " ")
    return if (value.length <= limit) value else value.take(limit) + "..."
}

private fun approximateTokenCount(text: String): Int =
    if (text.isEmpty()) 0 else ((text.length + 3) / 4).coerceAtLeast(1)

private fun truncateToApproxTokenCount(text: String, maxTokens: Int): String {
    if (maxTokens <= 0) return "... [tokens truncated]"
    val maxChars = (maxTokens * 4).coerceAtMost(text.length)
    return text.take(maxChars).trimEnd() + "\n... [tokens truncated]"
}

private fun normalizeAgentHistoryForPrompt(items: List<AgentConversationItem>): List<AgentConversationItem> {
    val filtered = items
        .filterNot { it.type == "message" && it.text.isBlank() }
        .map { it.copyAgentItem() }
    return removeOrphanToolOutputs(ensureCallOutputsPresent(filtered))
}

private fun ensureCallOutputsPresent(items: List<AgentConversationItem>): List<AgentConversationItem> {
    val outputIds = items.asSequence()
        .filter { it.type == "function_call_output" }
        .mapNotNull { it.callId }
        .toSet()
    val out = ArrayList<AgentConversationItem>(items.size)
    items.forEach { item ->
        out += item
        val callId = item.callId
        if (item.type == "function_call" && !callId.isNullOrBlank() && callId !in outputIds) {
            out += AgentConversationItem(
                type = "function_call_output",
                text = "aborted",
                callId = callId,
                name = item.name,
                argumentsJson = item.argumentsJson,
                toolSuccess = false,
            )
        }
    }
    return out
}

private fun removeOrphanToolOutputs(items: List<AgentConversationItem>): List<AgentConversationItem> {
    val callIds = items.asSequence()
        .filter { it.type == "function_call" }
        .mapNotNull { it.callId }
        .toSet()
    return items.filter { item ->
        item.type != "function_call_output" || item.callId in callIds
    }
}

private fun AgentConversationItem.copyAgentItem(): AgentConversationItem =
    AgentConversationItem(
        type = type,
        role = role,
        text = text,
        callId = callId,
        name = name,
        argumentsJson = argumentsJson,
        toolSuccess = toolSuccess,
        contentItems = contentItems,
    )

private fun isUsefulAgentText(text: String): Boolean {
    val value = text.trim()
    if (value.isEmpty() || value == "null") return false
    if (Regex("""(?:\{\}|\[\])+\s*""").matches(value)) return false
    return true
}

private fun removeTrailingBlankAssistantMessage(messages: MutableList<AgentConversationItem>) {
    val last = messages.lastOrNull() ?: return
    if (last.type == "message" && last.role == "assistant" && last.text.isBlank()) {
        messages.removeAt(messages.lastIndex)
    }
}

private fun maybeAutoCompactForUsage(
    messages: MutableList<AgentConversationItem>,
    usage: UiAgentTokenUsage?,
): Boolean {
    if (!shouldAutoCompact(messages, usage)) return false
    val normalized = normalizeAgentHistoryForPrompt(messages)
    val summary = buildCompactedSummary(normalized) ?: return false
    val userMessages = collectRealUserMessages(normalized)
    val compacted = buildCompactedHistory(userMessages, summary)
    messages.clear()
    messages += compacted
    return true
}

private fun shouldAutoCompact(
    messages: List<AgentConversationItem>,
    usage: UiAgentTokenUsage?,
): Boolean {
    val localTokens = estimateTokensAfterLastModelGeneratedItem(messages)
    val totalTokens = usage?.totalTokens?.plus(localTokens) ?: estimateAgentItemsTokens(messages)
    return (usage?.inputTokens ?: totalTokens) >= AUTO_COMPACT_INPUT_TOKEN_THRESHOLD ||
        totalTokens >= AUTO_COMPACT_TOTAL_TOKEN_THRESHOLD
}

private fun estimateTokensAfterLastModelGeneratedItem(items: List<AgentConversationItem>): Int {
    val start = items.indexOfLast(::isModelGeneratedItem).let { index ->
        if (index < 0) items.size else index + 1
    }
    return estimateAgentItemsTokens(items.drop(start))
}

private fun estimateAgentItemsTokens(items: List<AgentConversationItem>): Int =
    items.fold(0) { total, item ->
        total + approximateTokenCount(item.text) +
            item.contentItems.sumOf { approximateTokenCount(it.text.orEmpty()) } +
            approximateTokenCount(item.argumentsJson.orEmpty()) +
            approximateTokenCount(item.name.orEmpty())
    }

private fun isModelGeneratedItem(item: AgentConversationItem): Boolean =
    (item.type == "message" && item.role == "assistant") ||
        item.type == "function_call"

private data class AgentToolResult(
    val text: String,
    val success: Boolean,
    val contentItems: List<UiAgentContentItem> = emptyList(),
)

private data class AgentToolCallResult(val call: UiAgentToolCall, val output: AgentToolResult)

private data class AgentToolFuture(val call: UiAgentToolCall, val output: Deferred<AgentToolResult>)

private data class AgentToolContext(
    val rootPath: String,
    val filePaths: List<String>,
    val activePath: String?,
    val activeText: String?,
    val diagnostics: List<UiDiagnostic>,
    val buildLog: List<String>,
    val ideLogs: List<String>,
    val readFile: (String) -> String,
)

private fun toolSuccess(text: String, contentItems: List<UiAgentContentItem> = emptyList()): AgentToolResult =
    AgentToolResult(text, true, contentItems)

private fun toolFailure(text: String): AgentToolResult =
    AgentToolResult(text, false)

private fun agentTools(): List<UiAgentTool> = listOf(
    UiAgentTool(
        "list_workspace_files",
        "List openable files in the current workspace. Returns absolute paths.",
        """{"type":"object","properties":{},"additionalProperties":false}""",
    ),
    UiAgentTool(
        "read_workspace_file",
        "Read a workspace file by absolute path. Reads the unsaved editor buffer when the path is currently open.",
        """{"type":"object","properties":{"path":{"type":"string"}},"required":["path"],"additionalProperties":false}""",
    ),
    UiAgentTool(
        "get_current_file",
        "Return the current editor file path and unsaved buffer text.",
        """{"type":"object","properties":{},"additionalProperties":false}""",
    ),
    UiAgentTool(
        "get_diagnostics",
        "Return current editor diagnostics and warning/error messages.",
        """{"type":"object","properties":{},"additionalProperties":false}""",
    ),
    UiAgentTool(
        "get_build_logs",
        "Return recent build logs.",
        """{"type":"object","properties":{},"additionalProperties":false}""",
    ),
    UiAgentTool(
        "get_ide_logs",
        "Return recent IDE logs.",
        """{"type":"object","properties":{},"additionalProperties":false}""",
    ),
    UiAgentTool(
        "apply_patch",
        "Apply a unified patch to the current editor file. The patch must use *** Begin Patch, *** Update File: <current path>, @@ hunks, and *** End Patch.",
        """{"type":"object","properties":{"patch":{"type":"string"}},"required":["patch"],"additionalProperties":false}""",
    ),
    UiAgentTool(
        "capture_compose_preview",
        "Capture a PNG screenshot of a Compose @Preview in the current editor file and return it as an input_image tool output. If previewName is omitted, captures the currently selected preview or the first preview.",
        """{"type":"object","properties":{"previewName":{"type":"string"},"dark":{"type":"string","description":"Optional true/false night mode override"}},"additionalProperties":false}""",
    ),
)

private suspend fun executeAgentToolCalls(
    state: IdeUiState,
    calls: List<UiAgentToolCall>,
): List<AgentToolCallResult> {
    if (calls.any { !supportsParallelToolExecution(it) }) {
        return calls.map { call -> AgentToolCallResult(call, executeAgentTool(state, call)) }
    }
    val context = createAgentToolContext(state)
    return coroutineScope {
        val futures = calls.map { call ->
            AgentToolFuture(call, async(Dispatchers.Default) { executeAgentTool(context, call) })
        }
        futures.map { future -> AgentToolCallResult(future.call, future.output.await()) }
    }
}

private fun createAgentToolContext(state: IdeUiState): AgentToolContext {
    val active = state.active
    return AgentToolContext(
        rootPath = state.backend.project.rootPath,
        filePaths = collectFilePaths(state.tree),
        activePath = active?.path,
        activeText = active?.text,
        diagnostics = active?.session?.diagnostics.orEmpty().toList(),
        buildLog = state.backend.build.buildState.value.log
            .takeLast(160)
            .map { "${it.timeLabel} ${it.level}: ${it.message}" },
        ideLogs = state.backend.diagnostics.recentLogs()
            .takeLast(160)
            .map { "${it.timeLabel} ${it.level}/${it.tag}: ${it.message}" },
        readFile = state.backend.files::readFile,
    )
}

private fun supportsParallelToolExecution(call: UiAgentToolCall): Boolean =
    when (call.name) {
        "list_workspace_files",
        "read_workspace_file",
        "get_current_file",
        "get_diagnostics",
        "get_build_logs",
        "get_ide_logs" -> true
        else -> false
    }

private fun executeAgentTool(context: AgentToolContext, call: UiAgentToolCall): AgentToolResult {
    return runCatching {
        executeAgentToolUnchecked(context, call)
    }.getOrElse {
        toolFailure("tool failed: ${it.message ?: "unknown error"}")
    }
}

private suspend fun executeAgentTool(state: IdeUiState, call: UiAgentToolCall): AgentToolResult {
    return runCatching {
        executeAgentToolUnchecked(state, call)
    }.getOrElse {
        toolFailure("tool failed: ${it.message ?: "unknown error"}")
    }
}

private fun executeAgentToolUnchecked(context: AgentToolContext, call: UiAgentToolCall): AgentToolResult {
    return when (call.name) {
        "list_workspace_files" -> {
            toolSuccess(context.filePaths.take(500).joinToString("\n"))
        }
        "read_workspace_file" -> {
            val path = call.stringArguments["path"] ?: return toolFailure("missing path")
            if (path !in context.filePaths && !path.startsWith(context.rootPath)) {
                return toolFailure("path is outside workspace")
            }
            toolSuccess(if (context.activePath == path) context.activeText.orEmpty() else context.readFile(path).take(20000))
        }
        "get_current_file" -> {
            val path = context.activePath
            val text = context.activeText
            if (path == null || text == null) {
                toolFailure("no current file")
            } else {
                toolSuccess("path: $path\n```\n${text.take(20000)}\n```")
            }
        }
        "get_diagnostics" -> {
            if (context.diagnostics.isEmpty()) toolSuccess("no diagnostics")
            else toolSuccess(buildString { appendDiagnostics(this, context.diagnostics) })
        }
        "get_build_logs" -> {
            if (context.buildLog.isEmpty()) toolSuccess("no build logs")
            else toolSuccess(context.buildLog.joinToString("\n"))
        }
        "get_ide_logs" -> {
            if (context.ideLogs.isEmpty()) toolSuccess("no IDE logs")
            else toolSuccess(context.ideLogs.joinToString("\n"))
        }
        else -> toolFailure("unknown tool: ${call.name}")
    }
}

private suspend fun executeAgentToolUnchecked(state: IdeUiState, call: UiAgentToolCall): AgentToolResult {
    val active = state.active
    return when (call.name) {
        "list_workspace_files" -> {
            toolSuccess(collectFilePaths(state.tree).take(500).joinToString("\n"))
        }
        "read_workspace_file" -> {
            val path = call.stringArguments["path"] ?: return toolFailure("missing path")
            val known = collectFilePaths(state.tree).toSet()
            if (path !in known && !path.startsWith(state.backend.project.rootPath)) {
                return toolFailure("path is outside workspace")
            }
            toolSuccess(if (active?.path == path) active.text else state.backend.files.readFile(path).take(20000))
        }
        "get_current_file" -> {
            if (active == null) {
                toolFailure("no current file")
            } else {
                toolSuccess("path: ${active.path}\n```\n${active.text.take(20000)}\n```")
            }
        }
        "get_diagnostics" -> {
            val diagnostics = active?.session?.diagnostics.orEmpty()
            if (diagnostics.isEmpty()) toolSuccess("no diagnostics")
            else toolSuccess(buildString { appendDiagnostics(this, diagnostics) })
        }
        "get_build_logs" -> {
            val log = state.backend.build.buildState.value.log.takeLast(160)
            if (log.isEmpty()) toolSuccess("no build logs")
            else toolSuccess(log.joinToString("\n") { "${it.timeLabel} ${it.level}: ${it.message}" })
        }
        "get_ide_logs" -> {
            val logs = state.backend.diagnostics.recentLogs().takeLast(160)
            if (logs.isEmpty()) toolSuccess("no IDE logs")
            else toolSuccess(logs.joinToString("\n") { "${it.timeLabel} ${it.level}/${it.tag}: ${it.message}" })
        }
        "apply_patch" -> {
            val patch = call.stringArguments["patch"] ?: return toolFailure("missing patch")
            val file = active ?: return toolFailure("no current file")
            val result = applyAgentPatch(file.path, file.text, patch)
            if (!result.ok) return toolFailure(result.message)
            if (result.text == file.text) {
                return toolSuccess("patch applied to ${file.path}: ${result.message}")
            }
            val session = file.session
            session.replaceRange(0, session.doc.length, result.text, TextRange(result.text.length))
            toolSuccess("applied patch to ${file.path}: ${result.message}")
        }
        "capture_compose_preview" -> {
            val file = active ?: return toolFailure("no current file")
            val host = state.composePreviewHost ?: return toolFailure("Compose preview capture is not available")
            val previews = state.backend.preview.composePreviews(file.path, file.text)
            val target = selectComposePreview(previews, call.stringArguments["previewName"], file.previewTarget)
                ?: return toolFailure("no Compose @Preview found in ${file.path}")
            val dark = call.stringArguments["dark"]?.equals("true", ignoreCase = true) ?: (target.config.nightMode == true)
            val capture = host.capturePreview(file.path, target, file.text, dark)
            if (!capture.ok || capture.imageDataUrl.isNullOrBlank()) {
                return toolFailure(capture.message)
            }
            val label = target.label.ifBlank { target.functionName }
            val text = "Captured Compose preview `$label` (${capture.widthPx}x${capture.heightPx}px)."
            toolSuccess(
                text,
                listOf(
                    UiAgentContentItem(type = "input_text", text = text),
                    UiAgentContentItem(type = "input_image", imageUrl = capture.imageDataUrl, detail = "high"),
                )
            )
        }
        else -> toolFailure("unknown tool: ${call.name}")
    }
}

private fun selectComposePreview(
    previews: List<UiComposePreview>,
    requested: String?,
    selected: String?,
): UiComposePreview? {
    val key = requested?.takeIf { it.isNotBlank() } ?: selected
    if (!key.isNullOrBlank()) {
        previews.firstOrNull { it.variantId == key }?.let { return it }
        previews.firstOrNull { it.functionName == key }?.let { return it }
        previews.firstOrNull { it.label == key }?.let { return it }
    }
    return previews.firstOrNull()
}

private data class AgentPatchResult(val ok: Boolean, val text: String, val message: String)

private data class AgentPatchHunk(val oldText: String, val newText: String)

private data class AgentPatchParseResult(val ok: Boolean, val hunks: List<AgentPatchHunk> = emptyList(), val message: String = "")

private fun applyAgentPatch(currentPath: String, currentText: String, patch: String): AgentPatchResult {
    val parsed = parseAgentPatch(currentPath, patch)
    if (!parsed.ok) return AgentPatchResult(false, currentText, parsed.message)
    var text = currentText
    var searchFrom = 0
    var applied = 0
    parsed.hunks.forEachIndexed { index, hunk ->
        val match = findPatchMatch(text, hunk.oldText, searchFrom)
            ?: findPatchMatch(text, hunk.oldText, 0)
            ?: return AgentPatchResult(false, currentText, "hunk ${index + 1} did not match current file")
        text = text.replaceRange(match.first, match.last + 1, hunk.newText)
        searchFrom = match.first + hunk.newText.length
        applied++
    }
    if (applied == 0) return AgentPatchResult(false, currentText, "patch contained no hunks")
    if (text == currentText) return AgentPatchResult(true, text, "no text changed")
    return AgentPatchResult(true, text, "$applied hunk(s)")
}

private fun parseAgentPatch(currentPath: String, patch: String): AgentPatchParseResult {
    val lines = patch.replace("\r\n", "\n").replace('\r', '\n').split('\n')
    var inPatch = false
    var inCurrentFile = false
    var sawTargetFile = false
    var hunkStarted = false
    val hunks = ArrayList<AgentPatchHunk>()
    val oldText = StringBuilder()
    val newText = StringBuilder()

    fun flushHunk(): AgentPatchResult? {
        if (!hunkStarted) return null
        if (oldText.isEmpty() && newText.isEmpty()) {
            return AgentPatchResult(false, "", "empty hunk")
        }
        hunks += AgentPatchHunk(oldText.toString(), newText.toString())
        oldText.clear()
        newText.clear()
        hunkStarted = false
        return null
    }

    lines.forEach { line ->
        when {
            line == "*** Begin Patch" -> {
                inPatch = true
            }
            line == "*** End Patch" -> {
                flushHunk()?.let { return AgentPatchParseResult(false, message = it.message) }
                inCurrentFile = false
                inPatch = false
            }
            inPatch && line.startsWith("*** Update File:") -> {
                flushHunk()?.let { return AgentPatchParseResult(false, message = it.message) }
                val path = line.removePrefix("*** Update File:").trim()
                inCurrentFile = pathMatchesCurrentFile(path, currentPath)
                sawTargetFile = sawTargetFile || inCurrentFile
            }
            inPatch && line.startsWith("*** ") -> {
                flushHunk()?.let { return AgentPatchParseResult(false, message = it.message) }
                inCurrentFile = false
            }
            inPatch && inCurrentFile && line.startsWith("@@") -> {
                flushHunk()?.let { return AgentPatchParseResult(false, message = it.message) }
                hunkStarted = true
            }
            inPatch && inCurrentFile && hunkStarted && line.isNotEmpty() -> {
                when (line[0]) {
                    ' ' -> {
                        oldText.append(line.drop(1)).append('\n')
                        newText.append(line.drop(1)).append('\n')
                    }
                    '-' -> oldText.append(line.drop(1)).append('\n')
                    '+' -> newText.append(line.drop(1)).append('\n')
                    else -> return AgentPatchParseResult(false, message = "invalid hunk line: $line")
                }
            }
            inPatch && inCurrentFile && hunkStarted && line.isEmpty() -> {
                oldText.append('\n')
                newText.append('\n')
            }
        }
    }
    flushHunk()?.let { return AgentPatchParseResult(false, message = it.message) }
    if (!sawTargetFile) return AgentPatchParseResult(false, message = "patch does not target current file")
    if (hunks.isEmpty()) return AgentPatchParseResult(false, message = "patch contained no hunks")
    return AgentPatchParseResult(true, hunks)
}

private fun pathMatchesCurrentFile(path: String, currentPath: String): Boolean {
    val normalized = path.replace('\\', '/')
    val current = currentPath.replace('\\', '/')
    return normalized == current ||
        current.endsWith("/$normalized") ||
        normalized == current.substringAfterLast('/')
}

private fun findPatchMatch(text: String, oldText: String, startIndex: Int): IntRange? {
    if (oldText.isEmpty()) return null
    val exact = text.indexOf(oldText, startIndex.coerceIn(0, text.length))
    if (exact >= 0) return exact until exact + oldText.length
    if (oldText.endsWith('\n')) {
        val trimmed = oldText.dropLast(1)
        val trimmedIndex = text.indexOf(trimmed, startIndex.coerceIn(0, text.length))
        if (trimmedIndex >= 0) return trimmedIndex until trimmedIndex + trimmed.length
    }
    return null
}

private fun collectFilePaths(root: TreeNode): List<String> {
    val out = ArrayList<String>()
    fun walk(node: TreeNode) {
        node.filePath?.let { out += it }
        node.children.forEach(::walk)
    }
    walk(root)
    return out
}

private fun appendDiagnostics(out: StringBuilder, diagnostics: List<UiDiagnostic>) {
    if (diagnostics.isEmpty()) return
    out.append("## 编辑器诊断\n")
    diagnostics.forEach { d ->
        out.append(d.severity).append(' ').append(d.line).append(':').append(d.col).append(' ').append(d.message).append('\n')
    }
}

private const val AUTO_COMPACT_INPUT_TOKEN_THRESHOLD = 32_000
private const val AUTO_COMPACT_TOTAL_TOKEN_THRESHOLD = 48_000
private const val COMPACT_USER_MESSAGE_MAX_TOKENS = 20_000
private const val COMPACT_SUMMARY_PREFIX =
    "Another language model started to solve this problem and produced a summary of its thinking process. " +
        "You also have access to the state of the tools that were used by that language model. " +
        "Use this to build on the work that has already been done and avoid duplicating work. " +
        "Here is the summary produced by the other language model, use the information in this summary to assist with your own analysis:"
