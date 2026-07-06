package dev.ide.ui.screens

import androidx.compose.ui.text.TextRange
import dev.ide.ui.AgentConversationItem
import dev.ide.ui.ComposePreviewHost
import dev.ide.ui.EditorViewMode
import dev.ide.ui.IdeUiState
import dev.ide.ui.OpenFile
import dev.ide.ui.backend.BuildState
import dev.ide.ui.backend.RunTaskOption
import dev.ide.ui.backend.StepStatus
import dev.ide.ui.backend.TreeNode
import dev.ide.ui.backend.UiAgentContentItem
import dev.ide.ui.backend.UiAgentConfig
import dev.ide.ui.backend.UiAgentInputItem
import dev.ide.ui.backend.UiAgentRequest
import dev.ide.ui.backend.UiAgentTokenUsage
import dev.ide.ui.backend.UiAgentTool
import dev.ide.ui.backend.UiAgentToolCall
import dev.ide.ui.backend.UiAccent
import dev.ide.ui.backend.UiComposePreview
import dev.ide.ui.backend.UiDiagnostic
import dev.ide.ui.backend.UiSettings
import dev.ide.ui.backend.UiSeverity
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay

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
    val buildProgress: String,
    val runTasks: String,
    val appTheme: String,
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
        "get_build_progress",
        "Return current build status, elapsed time, task steps, diagnostic counts, and recent build log tail.",
        """{"type":"object","properties":{},"additionalProperties":false}""",
    ),
    UiAgentTool(
        "start_build",
        "Start the IDE's default build using the same action as the build console Run button, then return current progress.",
        """{"type":"object","properties":{},"additionalProperties":false}""",
    ),
    UiAgentTool(
        "stop_build",
        "Stop the current IDE build using the same action as the build console Stop button, then return current progress.",
        """{"type":"object","properties":{},"additionalProperties":false}""",
    ),
    UiAgentTool(
        "list_build_tasks",
        "List build and run tasks available in the IDE Run picker.",
        """{"type":"object","properties":{},"additionalProperties":false}""",
    ),
    UiAgentTool(
        "run_build_task",
        "Start a specific IDE build/run task by id from list_build_tasks, then return current progress.",
        """{"type":"object","properties":{"id":{"type":"string"}},"required":["id"],"additionalProperties":false}""",
    ),
    UiAgentTool(
        "get_app_theme",
        "Return the current app theme mode and accent.",
        """{"type":"object","properties":{},"additionalProperties":false}""",
    ),
    UiAgentTool(
        "set_app_theme",
        "Change the IDE app theme live using existing settings. themeMode accepts light, dark, or system. accent optionally accepts violet, teal, or orange.",
        """{"type":"object","properties":{"themeMode":{"type":"string","description":"light, dark, or system"},"accent":{"type":"string","description":"violet, teal, or orange"}},"additionalProperties":false}""",
    ),
    UiAgentTool(
        "toggle_app_theme",
        "Toggle the IDE app theme between explicit light and dark using the existing theme setting.",
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
        "open_compose_preview",
        "Open an existing Compose @Preview in the IDE preview pane using the normal UI state path. If previewName is omitted, opens the current selected preview or the first preview.",
        """{"type":"object","properties":{"previewName":{"type":"string"},"mode":{"type":"string","description":"preview or split"}},"additionalProperties":false}""",
    ),
    UiAgentTool(
        "capture_compose_preview",
        "Capture a PNG screenshot of the visible Compose @Preview in the current editor file and return it as an input_image tool output. Call open_compose_preview first if the requested preview is not already visible.",
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
        buildProgress = formatBuildProgress(state.backend.build.buildState.value),
        runTasks = formatRunTasks(state.backend.build.runTasks()),
        appTheme = formatAppTheme(state.backend.settings.settings()),
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
        "get_build_progress",
        "list_build_tasks",
        "get_app_theme",
        "get_ide_logs" -> true
        else -> false
    }

private fun executeAgentTool(context: AgentToolContext, call: UiAgentToolCall): AgentToolResult {
    return runCatching {
        executeAgentToolUnchecked(context, call)
    }.getOrElse {
        toolFailure("工具执行失败：${it.message ?: "未知错误"}")
    }
}

private suspend fun executeAgentTool(state: IdeUiState, call: UiAgentToolCall): AgentToolResult {
    return runCatching {
        executeAgentToolUnchecked(state, call)
    }.getOrElse {
        toolFailure("工具执行失败：${it.message ?: "未知错误"}")
    }
}

private fun executeAgentToolUnchecked(context: AgentToolContext, call: UiAgentToolCall): AgentToolResult {
    return when (call.name) {
        "list_workspace_files" -> {
            toolSuccess(context.filePaths.take(500).joinToString("\n"))
        }
        "read_workspace_file" -> {
            val path = call.stringArguments["path"] ?: return toolFailure("缺少路径")
            if (path !in context.filePaths && !path.startsWith(context.rootPath)) {
                return toolFailure("路径不在工作区内")
            }
            toolSuccess(if (context.activePath == path) context.activeText.orEmpty() else context.readFile(path).take(20000))
        }
        "get_current_file" -> {
            val path = context.activePath
            val text = context.activeText
            if (path == null || text == null) {
                toolFailure("没有当前文件")
            } else {
                toolSuccess("path: $path\n```\n${text.take(20000)}\n```")
            }
        }
        "get_diagnostics" -> {
            if (context.diagnostics.isEmpty()) toolSuccess("没有诊断信息")
            else toolSuccess(buildString { appendDiagnostics(this, context.diagnostics) })
        }
        "get_build_logs" -> {
            if (context.buildLog.isEmpty()) toolSuccess("没有构建日志")
            else toolSuccess(context.buildLog.joinToString("\n"))
        }
        "get_build_progress" -> {
            toolSuccess(context.buildProgress)
        }
        "list_build_tasks" -> {
            toolSuccess(context.runTasks)
        }
        "get_app_theme" -> {
            toolSuccess(context.appTheme)
        }
        "get_ide_logs" -> {
            if (context.ideLogs.isEmpty()) toolSuccess("没有 IDE 日志")
            else toolSuccess(context.ideLogs.joinToString("\n"))
        }
        else -> toolFailure("未知工具：${call.name}")
    }
}

private suspend fun executeAgentToolUnchecked(state: IdeUiState, call: UiAgentToolCall): AgentToolResult {
    val active = state.active
    return when (call.name) {
        "list_workspace_files" -> {
            toolSuccess(collectFilePaths(state.tree).take(500).joinToString("\n"))
        }
        "read_workspace_file" -> {
            val path = call.stringArguments["path"] ?: return toolFailure("缺少路径")
            val known = collectFilePaths(state.tree).toSet()
            if (path !in known && !path.startsWith(state.backend.project.rootPath)) {
                return toolFailure("路径不在工作区内")
            }
            toolSuccess(if (active?.path == path) active.text else state.backend.files.readFile(path).take(20000))
        }
        "get_current_file" -> {
            if (active == null) {
                toolFailure("没有当前文件")
            } else {
                toolSuccess("path: ${active.path}\n```\n${active.text.take(20000)}\n```")
            }
        }
        "get_diagnostics" -> {
            val diagnostics = active?.session?.diagnostics.orEmpty()
            if (diagnostics.isEmpty()) toolSuccess("没有诊断信息")
            else toolSuccess(buildString { appendDiagnostics(this, diagnostics) })
        }
        "get_build_logs" -> {
            val log = state.backend.build.buildState.value.log.takeLast(160)
            if (log.isEmpty()) toolSuccess("没有构建日志")
            else toolSuccess(log.joinToString("\n") { "${it.timeLabel} ${it.level}: ${it.message}" })
        }
        "get_build_progress" -> {
            toolSuccess(formatBuildProgress(state.backend.build.buildState.value))
        }
        "start_build" -> {
            state.consoleOpen = true
            state.backend.build.runBuild()
            delay(BUILD_ACTION_SETTLE_MS)
            toolSuccess("已请求开始构建\n\n${formatBuildProgress(state.backend.build.buildState.value)}")
        }
        "stop_build" -> {
            state.consoleOpen = true
            state.backend.build.stopBuild()
            delay(BUILD_ACTION_SETTLE_MS)
            toolSuccess("已请求停止构建\n\n${formatBuildProgress(state.backend.build.buildState.value)}")
        }
        "list_build_tasks" -> {
            toolSuccess(formatRunTasks(state.backend.build.runTasks()))
        }
        "run_build_task" -> {
            val id = call.stringArguments["id"] ?: return toolFailure("缺少 id")
            val tasks = state.backend.build.runTasks()
            val task = tasks.firstOrNull { it.id == id } ?: return toolFailure("未知构建任务：$id")
            state.consoleOpen = true
            state.backend.build.runTask(task.id)
            delay(BUILD_ACTION_SETTLE_MS)
            toolSuccess("已请求构建任务 `${task.label}`\n\n${formatBuildProgress(state.backend.build.buildState.value)}")
        }
        "get_app_theme" -> {
            toolSuccess(formatAppTheme(state.backend.settings.settings()))
        }
        "set_app_theme" -> {
            val mode = call.stringArguments["themeMode"]?.takeIf { it.isNotBlank() }
            val accent = call.stringArguments["accent"]?.takeIf { it.isNotBlank() }
            applyAppThemeSettings(state, mode, accent)?.let { return toolFailure(it) }
            toolSuccess("主题已更新\n\n${formatAppTheme(state.backend.settings.settings())}")
        }
        "toggle_app_theme" -> {
            val current = state.backend.settings.settings().themeMode
            val next = if (current == "dark") "light" else "dark"
            applyAppThemeSettings(state, next, null)?.let { return toolFailure(it) }
            toolSuccess("主题已切换\n\n${formatAppTheme(state.backend.settings.settings())}")
        }
        "get_ide_logs" -> {
            val logs = state.backend.diagnostics.recentLogs().takeLast(160)
            if (logs.isEmpty()) toolSuccess("没有 IDE 日志")
            else toolSuccess(logs.joinToString("\n") { "${it.timeLabel} ${it.level}/${it.tag}: ${it.message}" })
        }
        "apply_patch" -> {
            val patch = call.stringArguments["patch"] ?: return toolFailure("缺少 patch")
            val file = active ?: return toolFailure("没有当前文件")
            val result = applyAgentPatch(file.path, file.text, patch)
            if (!result.ok) return toolFailure(result.message)
            if (result.text == file.text) {
                return toolSuccess("patch 已应用到 ${file.path}: ${result.message}")
            }
            val session = file.session
            session.replaceRange(0, session.doc.length, result.text, TextRange(result.text.length))
            toolSuccess("已应用 patch 到 ${file.path}: ${result.message}")
        }
        "open_compose_preview" -> {
            val file = active ?: return toolFailure("没有当前文件")
            val host = state.composePreviewHost ?: return toolFailure("Compose 预览不可用")
            val previews = state.backend.preview.composePreviews(file.path, file.text)
            val target = selectComposePreview(previews, call.stringArguments["previewName"], file.previewTarget)
                ?: return toolFailure("${file.path} 中没有找到 Compose @Preview")
            val visible = openComposePreviewUi(host, file, target, call.stringArguments["mode"] ?: "preview")
            val label = target.label.ifBlank { target.functionName }
            if (!visible) {
                return toolFailure("已打开 Compose 预览 `$label`，但超时前未显示")
            }
            toolSuccess("已在 ${file.viewMode.name.lowercase()} 模式打开 Compose 预览 `$label`")
        }
        "capture_compose_preview" -> {
            val file = active ?: return toolFailure("没有当前文件")
            val host = state.composePreviewHost ?: return toolFailure("Compose 预览截图不可用")
            val previews = state.backend.preview.composePreviews(file.path, file.text)
            val target = selectComposePreview(previews, call.stringArguments["previewName"], file.previewTarget)
                ?: return toolFailure("${file.path} 中没有找到 Compose @Preview")
            if (!openComposePreviewUi(host, file, target, call.stringArguments["mode"])) {
                val label = target.label.ifBlank { target.functionName }
                return toolFailure("已打开 Compose 预览 `$label`，但超时前未显示")
            }
            val dark = call.stringArguments["dark"]?.equals("true", ignoreCase = true) ?: (target.config.nightMode == true)
            val capture = host.capturePreview(file.path, target, file.text, dark)
            if (!capture.ok || capture.imageDataUrl.isNullOrBlank()) {
                return toolFailure(capture.message)
            }
            val label = target.label.ifBlank { target.functionName }
            val text = "已截取 Compose 预览 `$label` (${capture.widthPx}x${capture.heightPx}px)。"
            toolSuccess(
                text,
                listOf(
                    UiAgentContentItem(type = "input_text", text = text),
                    UiAgentContentItem(type = "input_image", imageUrl = capture.imageDataUrl, detail = "high"),
                )
            )
        }
        else -> toolFailure("未知工具：${call.name}")
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

private suspend fun openComposePreviewUi(
    host: ComposePreviewHost,
    file: OpenFile,
    target: UiComposePreview,
    mode: String?,
): Boolean {
    file.previewTarget = target.variantId
    when (mode?.lowercase()) {
        "split" -> file.viewMode = EditorViewMode.Split
        "preview" -> file.viewMode = EditorViewMode.Preview
        else -> if (file.viewMode != EditorViewMode.Preview && file.viewMode != EditorViewMode.Split) {
            file.viewMode = EditorViewMode.Preview
        }
    }
    return host.awaitPreviewVisible(file.path, target, PREVIEW_OPEN_TIMEOUT_MS)
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
            ?: return AgentPatchResult(false, currentText, "第 ${index + 1} 个 hunk 与当前文件不匹配")
        text = text.replaceRange(match.first, match.last + 1, hunk.newText)
        searchFrom = match.first + hunk.newText.length
        applied++
    }
    if (applied == 0) return AgentPatchResult(false, currentText, "patch 不包含 hunk")
    if (text == currentText) return AgentPatchResult(true, text, "文本没有变化")
    return AgentPatchResult(true, text, "已应用 $applied 个 hunk")
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
            return AgentPatchResult(false, "", "空 hunk")
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
                    else -> return AgentPatchParseResult(false, message = "无效 hunk 行：$line")
                }
            }
            inPatch && inCurrentFile && hunkStarted && line.isEmpty() -> {
                oldText.append('\n')
                newText.append('\n')
            }
        }
    }
    flushHunk()?.let { return AgentPatchParseResult(false, message = it.message) }
    if (!sawTargetFile) return AgentPatchParseResult(false, message = "patch 未指向当前文件")
    if (hunks.isEmpty()) return AgentPatchParseResult(false, message = "patch 不包含 hunk")
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

private fun formatBuildProgress(state: BuildState): String = buildString {
    val errors = state.diagnostics.count { it.severity == UiSeverity.Error }
    val warnings = state.diagnostics.count { it.severity == UiSeverity.Warning }
    append("状态: ").append(state.status.name).append('\n')
    if (state.moduleName.isNotBlank()) {
        append("模块: ").append(state.moduleName).append('\n')
    }
    append("耗时 ms: ").append(state.elapsedMs).append('\n')
    append("步骤: ").append(state.steps.count { it.status != StepStatus.Pending })
        .append('/').append(state.steps.size).append('\n')
    append("诊断: ").append(errors).append(" 个错误，").append(warnings).append(" 个警告\n")
    state.banner?.let { append("横幅: ").append(it).append('\n') }
    if (state.steps.isNotEmpty()) {
        append('\n').append("## 步骤\n")
        state.steps.takeLast(40).forEach { step ->
            append("- ").append(step.status.name).append(' ').append(step.name).append('\n')
        }
    }
    if (state.diagnostics.isNotEmpty()) {
        append('\n').append("## 诊断\n")
        state.diagnostics.takeLast(20).forEach { d ->
            append("- ").append(d.severity).append(' ')
            d.file?.let {
                append(it)
                if (d.line > 0) append(':').append(d.line)
                if (d.column > 0) append(':').append(d.column)
                append(' ')
            }
            append(d.message).append('\n')
        }
    }
    if (state.log.isNotEmpty()) {
        append('\n').append("## 最近日志\n")
        state.log.takeLast(40).forEach { line ->
            if (line.timeLabel.isNotBlank()) append(line.timeLabel).append(' ')
            append(line.level).append(": ").append(line.message).append('\n')
        }
    }
}

private fun formatRunTasks(tasks: List<RunTaskOption>): String {
    if (tasks.isEmpty()) return "没有构建任务"
    return buildString {
        tasks.forEach { task ->
            append("- id: ").append(task.id)
            append("\n  标签: ").append(task.label)
            if (task.group.isNotBlank()) append("\n  分组: ").append(task.group)
            append('\n')
        }
    }
}

private fun formatAppTheme(settings: UiSettings): String = buildString {
    append("主题模式: ").append(settings.themeMode).append('\n')
    append("强调色: ").append(themeAccentValue(settings.accent)).append('\n')
}

private fun applyAppThemeSettings(state: IdeUiState, themeMode: String?, accent: String?): String? {
    val mode = themeMode?.lowercase()
    val accentValue = accent?.lowercase()
    if (mode == null && accentValue == null) return "缺少 themeMode 或 accent"
    if (mode != null && mode !in THEME_MODES) return "无效 themeMode：$themeMode"
    if (accentValue != null && accentValue !in THEME_ACCENTS) return "无效 accent：$accent"
    mode?.let { state.backend.settings.setSetting("appearance", "themeMode", it) }
    accentValue?.let { state.backend.settings.setSetting("appearance", "accent", it) }
    state.notifySettingsChanged()
    return null
}

private fun themeAccentValue(accent: UiAccent): String =
    when (accent) {
        UiAccent.Teal -> "teal"
        UiAccent.Orange -> "orange"
        else -> "violet"
    }

private const val AUTO_COMPACT_INPUT_TOKEN_THRESHOLD = 32_000
private const val AUTO_COMPACT_TOTAL_TOKEN_THRESHOLD = 48_000
private const val COMPACT_USER_MESSAGE_MAX_TOKENS = 20_000
private const val PREVIEW_OPEN_TIMEOUT_MS = 5_000L
private const val BUILD_ACTION_SETTLE_MS = 250L
private val THEME_MODES = setOf("light", "dark", "system")
private val THEME_ACCENTS = setOf("violet", "teal", "orange")
private const val COMPACT_SUMMARY_PREFIX =
    "Another language model started to solve this problem and produced a summary of its thinking process. " +
        "You also have access to the state of the tools that were used by that language model. " +
        "Use this to build on the work that has already been done and avoid duplicating work. " +
        "Here is the summary produced by the other language model, use the information in this summary to assist with your own analysis:"
