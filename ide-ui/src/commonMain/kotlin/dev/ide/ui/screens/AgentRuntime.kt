package dev.ide.ui.screens

import dev.ide.ui.AgentConversationItem
import dev.ide.ui.ComposePreviewHost
import dev.ide.ui.EditorViewMode
import dev.ide.ui.IdeUiState
import dev.ide.ui.OpenFile
import dev.ide.ui.backend.BuildState
import dev.ide.ui.backend.RunTaskOption
import dev.ide.ui.backend.StepStatus
import dev.ide.ui.backend.TreeNode
import dev.ide.ui.backend.TreeViewMode
import dev.ide.ui.backend.UiAgentContentItem
import dev.ide.ui.backend.UiAgentConfig
import dev.ide.ui.backend.UiAgentInputItem
import dev.ide.ui.backend.UiAgentRequest
import dev.ide.ui.backend.UiAgentTokenUsage
import dev.ide.ui.backend.UiAgentTool
import dev.ide.ui.backend.UiAgentToolCall
import dev.ide.ui.backend.UiAgentToolParameters
import dev.ide.ui.backend.UiAgentToolProperty
import dev.ide.ui.backend.UiAccent
import dev.ide.ui.backend.UiComposePreview
import dev.ide.ui.backend.UiDiagnostic
import dev.ide.ui.backend.UiFileSymbol
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
        "Use tools to inspect the workspace, diagnostics, logs, previews, and build state. " +
        "When modifying files, use apply_patch with the Codex apply_patch format. " +
        "Patches may add, update, move, and delete files under the workspace."

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
        "List files in the current workspace. Returns absolute paths.",
    ),
    UiAgentTool(
        "glob",
        "Find workspace files by glob pattern. Matches relative and absolute paths. Supports *, ?, and **.",
        agentToolParameters(
            agentToolProperty("pattern"),
            agentToolProperty("limit", "number"),
            required = listOf("pattern"),
        ),
    ),
    UiAgentTool(
        "grep",
        "Search workspace text. Use regex=true for regular expressions; pathGlob narrows files before searching.",
        agentToolParameters(
            agentToolProperty("query"),
            agentToolProperty("pathGlob"),
            agentToolProperty("regex", "boolean"),
            agentToolProperty("caseSensitive", "boolean"),
            agentToolProperty("limit", "number"),
            required = listOf("query"),
        ),
    ),
    UiAgentTool(
        "read_file",
        "Read a workspace file or the current editor file. Supports path, startLine/endLine, offset/length, and symbol name for existing file-structure ranges.",
        agentToolParameters(
            agentToolProperty("path"),
            agentToolProperty("startLine", "number"),
            agentToolProperty("endLine", "number"),
            agentToolProperty("offset", "number"),
            agentToolProperty("length", "number"),
            agentToolProperty("symbol"),
            agentToolProperty("includeLineNumbers", "boolean"),
        ),
    ),
    UiAgentTool(
        "read_workspace_file",
        "Compatibility alias for read_file with a required path.",
        agentToolParameters(
            agentToolProperty("path"),
            agentToolProperty("startLine", "number"),
            agentToolProperty("endLine", "number"),
            agentToolProperty("offset", "number"),
            agentToolProperty("length", "number"),
            agentToolProperty("symbol"),
            required = listOf("path"),
        ),
    ),
    UiAgentTool(
        "get_current_file",
        "Compatibility alias for read_file without a path; returns the current editor buffer.",
        agentToolParameters(
            agentToolProperty("startLine", "number"),
            agentToolProperty("endLine", "number"),
            agentToolProperty("offset", "number"),
            agentToolProperty("length", "number"),
            agentToolProperty("symbol"),
        ),
    ),
    UiAgentTool(
        "get_diagnostics",
        "Return current editor diagnostics and warning/error messages.",
    ),
    UiAgentTool(
        "get_build_logs",
        "Return recent build logs.",
    ),
    UiAgentTool(
        "get_build_progress",
        "Return current build status, elapsed time, task steps, diagnostic counts, and recent build log tail.",
    ),
    UiAgentTool(
        "start_build",
        "Start the IDE's default build using the same action as the build console Run button, then return current progress.",
    ),
    UiAgentTool(
        "stop_build",
        "Stop the current IDE build using the same action as the build console Stop button, then return current progress.",
    ),
    UiAgentTool(
        "list_build_tasks",
        "List build and run tasks available in the IDE Run picker.",
    ),
    UiAgentTool(
        "run_build_task",
        "Start a specific IDE build/run task by id from list_build_tasks, then return current progress.",
        agentToolParameters(agentToolProperty("id"), required = listOf("id")),
    ),
    UiAgentTool(
        "get_app_theme",
        "Return the current app theme mode and accent.",
    ),
    UiAgentTool(
        "set_app_theme",
        "Change the IDE app theme live using existing settings. themeMode accepts light, dark, or system. accent optionally accepts violet, teal, or orange.",
        agentToolParameters(
            agentToolProperty("themeMode", description = "light, dark, or system"),
            agentToolProperty("accent", description = "violet, teal, or orange"),
        ),
    ),
    UiAgentTool(
        "toggle_app_theme",
        "Toggle the IDE app theme between explicit light and dark using the existing theme setting.",
    ),
    UiAgentTool(
        "get_ide_logs",
        "Return recent IDE logs.",
    ),
    UiAgentTool(
        "apply_patch",
        "Apply a Codex apply_patch patch to workspace files. Supports Add File, Update File, Move to, Delete File, @@ hunks, and *** End of File. Parent directories are created automatically.",
        agentToolParameters(agentToolProperty("patch"), required = listOf("patch")),
    ),
    UiAgentTool(
        "open_compose_preview",
        "Open an existing Compose @Preview in the IDE preview pane using the normal UI state path. If previewName is omitted, opens the current selected preview or the first preview.",
        agentToolParameters(
            agentToolProperty("previewName"),
            agentToolProperty("mode", description = "preview or split"),
        ),
    ),
    UiAgentTool(
        "capture_compose_preview",
        "Capture a PNG screenshot of the visible Compose @Preview in the current editor file and return it as an input_image tool output. Call open_compose_preview first if the requested preview is not already visible.",
        agentToolParameters(
            agentToolProperty("previewName"),
            agentToolProperty("dark", "boolean", "Optional night mode override."),
        ),
    ),
)

private fun agentToolParameters(
    vararg properties: UiAgentToolProperty,
    required: List<String> = emptyList(),
): UiAgentToolParameters =
    UiAgentToolParameters(properties.toList(), required)

private fun agentToolProperty(
    name: String,
    type: String = "string",
    description: String? = null,
): UiAgentToolProperty =
    UiAgentToolProperty(name, type, description)

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
    val allFiles = collectFilePaths(state.backend.files.fileTree(TreeViewMode.AllFiles))
    return AgentToolContext(
        rootPath = state.backend.project.rootPath,
        filePaths = allFiles,
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
        "glob",
        "grep",
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
        "glob" -> {
            val pattern = call.stringArguments["pattern"] ?: return toolFailure("缺少 pattern")
            val limit = call.intArgument("limit", 200, 1, 1000)
            val matches = globWorkspaceFiles(context.rootPath, context.filePaths, pattern).take(limit)
            toolSuccess(if (matches.isEmpty()) "没有匹配文件" else matches.joinToString("\n"))
        }
        "grep" -> {
            val query = call.stringArguments["query"] ?: return toolFailure("缺少 query")
            val limit = call.intArgument("limit", 200, 1, 1000)
            val matches = grepWorkspaceFiles(
                rootPath = context.rootPath,
                filePaths = context.filePaths,
                readFile = { path ->
                    if (path == context.activePath) context.activeText.orEmpty() else context.readFile(path)
                },
                query = query,
                pathGlob = call.stringArguments["pathGlob"],
                regex = call.booleanArgument("regex"),
                caseSensitive = call.booleanArgument("caseSensitive"),
                limit = limit,
            )
            toolSuccess(if (matches.isEmpty()) "没有匹配项" else matches.joinToString("\n"))
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
            toolSuccess(collectFilePaths(state.backend.files.fileTree(TreeViewMode.AllFiles)).take(500).joinToString("\n"))
        }
        "glob" -> {
            val pattern = call.stringArguments["pattern"] ?: return toolFailure("缺少 pattern")
            val files = collectFilePaths(state.backend.files.fileTree(TreeViewMode.AllFiles))
            val limit = call.intArgument("limit", 200, 1, 1000)
            val matches = globWorkspaceFiles(state.backend.project.rootPath, files, pattern).take(limit)
            toolSuccess(if (matches.isEmpty()) "没有匹配文件" else matches.joinToString("\n"))
        }
        "grep" -> {
            val query = call.stringArguments["query"] ?: return toolFailure("缺少 query")
            val files = collectFilePaths(state.backend.files.fileTree(TreeViewMode.AllFiles))
            val limit = call.intArgument("limit", 200, 1, 1000)
            val matches = grepWorkspaceFiles(
                rootPath = state.backend.project.rootPath,
                filePaths = files,
                readFile = { path -> if (active?.path == path) active.text else state.backend.files.readFile(path) },
                query = query,
                pathGlob = call.stringArguments["pathGlob"],
                regex = call.booleanArgument("regex"),
                caseSensitive = call.booleanArgument("caseSensitive"),
                limit = limit,
            )
            toolSuccess(if (matches.isEmpty()) "没有匹配项" else matches.joinToString("\n"))
        }
        "read_file", "read_workspace_file", "get_current_file" -> {
            readAgentFile(state, call)
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
            val result = applyAgentPatch(state, patch)
            if (!result.ok) return toolFailure(result.message)
            toolSuccess(result.message)
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

private data class AgentPatchResult(val ok: Boolean, val message: String)

private sealed class AgentPatchHunk {
    abstract val path: String

    data class AddFile(override val path: String, val contents: String) : AgentPatchHunk()
    data class DeleteFile(override val path: String) : AgentPatchHunk()
    data class UpdateFile(
        override val path: String,
        val movePath: String?,
        val chunks: List<AgentPatchChunk>,
    ) : AgentPatchHunk()
}

private data class AgentPatchChunk(
    val changeContext: String?,
    val oldLines: List<String>,
    val newLines: List<String>,
    val isEndOfFile: Boolean,
)

private data class AgentPatchParseResult(
    val ok: Boolean,
    val hunks: List<AgentPatchHunk> = emptyList(),
    val message: String = "",
)

private sealed class AgentAppliedChange {
    abstract val path: String

    data class Add(override val path: String, val text: String) : AgentAppliedChange()
    data class Delete(override val path: String) : AgentAppliedChange()
    data class Update(override val path: String, val text: String) : AgentAppliedChange()
    data class Move(val from: String, override val path: String, val text: String) : AgentAppliedChange()
}

private fun applyAgentPatch(state: IdeUiState, patch: String): AgentPatchResult {
    val parsed = parseAgentPatch(patch)
    if (!parsed.ok) return AgentPatchResult(false, parsed.message)
    if (parsed.hunks.isEmpty()) return AgentPatchResult(false, "No files were modified.")

    val rootPath = normalizeWorkspacePath(state.backend.project.rootPath)
    val filePaths = collectFilePaths(state.backend.files.fileTree(TreeViewMode.AllFiles)).map(::normalizeWorkspacePath).toMutableSet()
    val changes = ArrayList<AgentAppliedChange>()
    val added = ArrayList<String>()
    val modified = ArrayList<String>()
    val deleted = ArrayList<String>()

    fun fail(message: String): AgentPatchResult {
        if (changes.isNotEmpty()) {
            syncOpenFilesAfterPatch(state, changes)
            state.refreshTree()
        }
        return AgentPatchResult(false, message)
    }

    for (hunk in parsed.hunks) {
        when (hunk) {
            is AgentPatchHunk.AddFile -> {
                val path = resolveAgentPatchPath(rootPath, hunk.path)
                    ?: return fail("路径不在工作区内：${hunk.path}")
                ensurePatchCanTouchOpenFile(state, path)?.let { return fail(it) }
                if (!state.backend.files.writeFile(path, hunk.contents)) {
                    return fail("Failed to write file $path")
                }
                filePaths += path
                changes += AgentAppliedChange.Add(path, hunk.contents)
                added += displayWorkspacePath(rootPath, path)
            }
            is AgentPatchHunk.DeleteFile -> {
                val path = resolveAgentPatchPath(rootPath, hunk.path)
                    ?: return fail("路径不在工作区内：${hunk.path}")
                if (path !in filePaths) return fail("Failed to delete file $path")
                ensurePatchCanTouchOpenFile(state, path)?.let { return fail(it) }
                if (!state.backend.files.deletePath(path)) {
                    return fail("Failed to delete file $path")
                }
                filePaths -= path
                changes += AgentAppliedChange.Delete(path)
                deleted += displayWorkspacePath(rootPath, path)
            }
            is AgentPatchHunk.UpdateFile -> {
                val path = resolveAgentPatchPath(rootPath, hunk.path)
                    ?: return fail("路径不在工作区内：${hunk.path}")
                if (path !in filePaths) return fail("Failed to read file to update $path")
                ensurePatchCanTouchOpenFile(state, path)?.let { return fail(it) }
                val oldText = state.openFiles.firstOrNull { normalizeWorkspacePath(it.path) == path }?.text
                    ?: state.backend.files.readFile(path)
                val newText = deriveAgentPatchText(oldText, hunk.chunks)
                    ?: return fail("Failed to apply update hunk to $path")
                if (hunk.movePath == null) {
                    if (!state.backend.files.writeFile(path, newText)) {
                        return fail("Failed to write file $path")
                    }
                    changes += AgentAppliedChange.Update(path, newText)
                    modified += displayWorkspacePath(rootPath, path)
                } else {
                    val movePath = resolveAgentPatchPath(rootPath, hunk.movePath)
                        ?: return fail("路径不在工作区内：${hunk.movePath}")
                    ensurePatchCanTouchOpenFile(state, movePath)?.let { return fail(it) }
                    if (!state.backend.files.writeFile(movePath, newText)) {
                        return fail("Failed to write file $movePath")
                    }
                    filePaths += movePath
                    if (!state.backend.files.deletePath(path)) {
                        syncOpenFilesAfterPatch(state, listOf(AgentAppliedChange.Update(movePath, newText)))
                        state.refreshTree()
                        return fail("Failed to remove original $path")
                    }
                    filePaths -= path
                    changes += AgentAppliedChange.Move(path, movePath, newText)
                    modified += displayWorkspacePath(rootPath, movePath)
                }
            }
        }
    }

    syncOpenFilesAfterPatch(state, changes)
    state.refreshTree()
    return AgentPatchResult(true, buildPatchSummary(added, modified, deleted))
}

private fun parseAgentPatch(patch: String): AgentPatchParseResult {
    val lines = unwrapAgentPatch(patch).replace("\r\n", "\n").replace('\r', '\n').lines()
    if (lines.firstOrNull()?.trim() != "*** Begin Patch") {
        return AgentPatchParseResult(false, message = "Invalid patch: The first line of the patch must be '*** Begin Patch'")
    }
    if (lines.lastOrNull()?.trim() != "*** End Patch") {
        return AgentPatchParseResult(false, message = "Invalid patch: The last line of the patch must be '*** End Patch'")
    }

    val hunks = ArrayList<AgentPatchHunk>()
    var mode = "started"
    var updatePath: String? = null
    var movePath: String? = null
    val chunks = ArrayList<AgentPatchChunk>()
    var chunkContext: String? = null
    var oldLines = ArrayList<String>()
    var newLines = ArrayList<String>()
    var endOfFile = false
    var chunkStarted = false
    var addPath: String? = null
    val addContents = StringBuilder()

    fun flushChunk(): String? {
        if (!chunkStarted) return null
        if (oldLines.isEmpty() && newLines.isEmpty()) return "Update hunk does not contain any lines"
        chunks += AgentPatchChunk(chunkContext, oldLines.toList(), newLines.toList(), endOfFile)
        chunkContext = null
        oldLines = ArrayList()
        newLines = ArrayList()
        endOfFile = false
        chunkStarted = false
        return null
    }

    fun flushMode(): String? {
        when (mode) {
            "add" -> {
                val path = addPath ?: return "Add file hunk is missing a path"
                hunks += AgentPatchHunk.AddFile(path, addContents.toString())
                addPath = null
                addContents.clear()
            }
            "update" -> {
                flushChunk()?.let { return it }
                val path = updatePath ?: return "Update file hunk is missing a path"
                if (chunks.isEmpty()) return "Update file hunk for path '$path' is empty"
                hunks += AgentPatchHunk.UpdateFile(path, movePath, chunks.toList())
                updatePath = null
                movePath = null
                chunks.clear()
            }
            "delete", "started" -> Unit
        }
        mode = "started"
        return null
    }

    lines.forEachIndexed { index, rawLine ->
        if (index == 0) return@forEachIndexed
        val lineNumber = index + 1
        val trimmed = rawLine.trim()
        val updateLine = rawLine.trimEnd()
        if (trimmed == "*** End Patch") {
            flushMode()?.let { return AgentPatchParseResult(false, message = "Invalid patch hunk on line $lineNumber: $it") }
            mode = "ended"
            return@forEachIndexed
        }

        when {
            mode == "started" && trimmed.startsWith("*** Environment ID:") -> Unit
            trimmed.startsWith("*** Add File: ") -> {
                flushMode()?.let { return AgentPatchParseResult(false, message = "Invalid patch hunk on line $lineNumber: $it") }
                addPath = trimmed.removePrefix("*** Add File: ").trim()
                mode = "add"
            }
            trimmed.startsWith("*** Delete File: ") -> {
                flushMode()?.let { return AgentPatchParseResult(false, message = "Invalid patch hunk on line $lineNumber: $it") }
                hunks += AgentPatchHunk.DeleteFile(trimmed.removePrefix("*** Delete File: ").trim())
                mode = "delete"
            }
            trimmed.startsWith("*** Update File: ") -> {
                flushMode()?.let { return AgentPatchParseResult(false, message = "Invalid patch hunk on line $lineNumber: $it") }
                updatePath = trimmed.removePrefix("*** Update File: ").trim()
                mode = "update"
            }
            mode == "add" -> {
                if (!rawLine.startsWith("+")) {
                    return AgentPatchParseResult(false, message = "Invalid patch hunk on line $lineNumber: '$trimmed' is not a valid hunk header")
                }
                addContents.append(rawLine.drop(1)).append('\n')
            }
            mode == "delete" -> {
                return AgentPatchParseResult(false, message = "Invalid patch hunk on line $lineNumber: '$trimmed' is not a valid hunk header")
            }
            mode == "update" -> {
                if (endOfFile && updateLine.isNotEmpty() && updateLine != "@@" && !updateLine.startsWith("@@ ")) {
                    return AgentPatchParseResult(false, message = "Invalid patch hunk on line $lineNumber: Expected update hunk to start with a @@ context marker, got: '$rawLine'")
                }
                when {
                    chunks.isEmpty() && movePath == null && updateLine.startsWith("*** Move to: ") -> {
                        movePath = updateLine.removePrefix("*** Move to: ").trim()
                    }
                    updateLine == "@@" || updateLine.startsWith("@@ ") -> {
                        flushChunk()?.let { return AgentPatchParseResult(false, message = "Invalid patch hunk on line $lineNumber: $it") }
                        chunkContext = updateLine.removePrefix("@@").trim().takeIf { it.isNotEmpty() }
                        chunkStarted = true
                    }
                    updateLine == "*** End of File" -> {
                        if (!chunkStarted || oldLines.isEmpty() && newLines.isEmpty()) {
                            return AgentPatchParseResult(false, message = "Invalid patch hunk on line $lineNumber: Update hunk does not contain any lines")
                        }
                        endOfFile = true
                    }
                    rawLine.isEmpty() -> {
                        chunkStarted = true
                        oldLines += ""
                        newLines += ""
                    }
                    rawLine.startsWith(" ") -> {
                        chunkStarted = true
                        oldLines += rawLine.drop(1)
                        newLines += rawLine.drop(1)
                    }
                    rawLine.startsWith("+") -> {
                        chunkStarted = true
                        newLines += rawLine.drop(1)
                    }
                    rawLine.startsWith("-") -> {
                        chunkStarted = true
                        oldLines += rawLine.drop(1)
                    }
                    oldLines.isNotEmpty() || newLines.isNotEmpty() -> {
                        return AgentPatchParseResult(false, message = "Invalid patch hunk on line $lineNumber: Expected update hunk to start with a @@ context marker, got: '$rawLine'")
                    }
                    else -> {
                        return AgentPatchParseResult(false, message = "Invalid patch hunk on line $lineNumber: Unexpected line found in update hunk: '$rawLine'")
                    }
                }
            }
            trimmed.isNotEmpty() -> {
                return AgentPatchParseResult(
                    false,
                    message = "Invalid patch hunk on line $lineNumber: '$trimmed' is not a valid hunk header. Valid hunk headers: '*** Add File: {path}', '*** Delete File: {path}', '*** Update File: {path}'",
                )
            }
        }
    }
    if (mode != "ended") return AgentPatchParseResult(false, message = "Invalid patch: The last line of the patch must be '*** End Patch'")
    return AgentPatchParseResult(true, hunks)
}

private fun unwrapAgentPatch(patch: String): String {
    val trimmed = patch.trim()
    val lines = trimmed.lines()
    if (lines.size >= 4 && (lines.first() == "<<EOF" || lines.first() == "<<'EOF'" || lines.first() == "<<\"EOF\"") && lines.last().endsWith("EOF")) {
        return lines.drop(1).dropLast(1).joinToString("\n").trim()
    }
    return trimmed
}

private fun deriveAgentPatchText(currentText: String, chunks: List<AgentPatchChunk>): String? {
    val originalLines = currentText.split('\n').toMutableList()
    if (originalLines.lastOrNull()?.isEmpty() == true) originalLines.removeAt(originalLines.lastIndex)
    val replacements = ArrayList<Triple<Int, Int, List<String>>>()
    var lineIndex = 0

    chunks.forEach { chunk ->
        chunk.changeContext?.let { context ->
            val contextIndex = seekAgentPatchSequence(originalLines, listOf(context), lineIndex, eof = false)
                ?: return null
            lineIndex = contextIndex + 1
        }
        if (chunk.oldLines.isEmpty()) {
            val insertionIndex = if (originalLines.lastOrNull()?.isEmpty() == true) originalLines.lastIndex else originalLines.size
            replacements += Triple(insertionIndex, 0, chunk.newLines)
            return@forEach
        }

        var pattern = chunk.oldLines
        var newLines = chunk.newLines
        var found = seekAgentPatchSequence(originalLines, pattern, lineIndex, chunk.isEndOfFile)
        if (found == null && pattern.lastOrNull()?.isEmpty() == true) {
            pattern = pattern.dropLast(1)
            if (newLines.lastOrNull()?.isEmpty() == true) newLines = newLines.dropLast(1)
            found = seekAgentPatchSequence(originalLines, pattern, lineIndex, chunk.isEndOfFile)
        }
        val start = found ?: return null
        replacements += Triple(start, pattern.size, newLines)
        lineIndex = start + pattern.size
    }

    val newContentLines = originalLines.toMutableList()
    replacements.sortedByDescending { it.first }.forEach { (start, oldLength, newLines) ->
        repeat(oldLength) { if (start < newContentLines.size) newContentLines.removeAt(start) }
        newLines.forEachIndexed { index, line -> newContentLines.add(start + index, line) }
    }
    if (newContentLines.lastOrNull()?.isNotEmpty() != false) newContentLines += ""
    return newContentLines.joinToString("\n")
}

private fun seekAgentPatchSequence(lines: List<String>, pattern: List<String>, start: Int, eof: Boolean): Int? {
    if (pattern.isEmpty()) return start.coerceIn(0, lines.size)
    if (pattern.size > lines.size) return null
    val searchStart = if (eof && lines.size >= pattern.size) lines.size - pattern.size else start.coerceIn(0, lines.size)
    val searchEnd = lines.size - pattern.size
    fun search(matches: (String, String) -> Boolean): Int? {
        for (i in searchStart..searchEnd) {
            var ok = true
            for (j in pattern.indices) {
                if (!matches(lines[i + j], pattern[j])) {
                    ok = false
                    break
                }
            }
            if (ok) return i
        }
        return null
    }
    return search { a, b -> a == b }
        ?: search { a, b -> a.trimEnd() == b.trimEnd() }
        ?: search { a, b -> a.trim() == b.trim() }
        ?: search { a, b -> normalizePatchLine(a) == normalizePatchLine(b) }
}

private fun normalizePatchLine(line: String): String =
    line.trim().map { ch ->
        when (ch) {
            '\u2010', '\u2011', '\u2012', '\u2013', '\u2014', '\u2015', '\u2212' -> '-'
            '\u2018', '\u2019', '\u201A', '\u201B' -> '\''
            '\u201C', '\u201D', '\u201E', '\u201F' -> '"'
            '\u00A0', '\u2002', '\u2003', '\u2004', '\u2005', '\u2006', '\u2007',
            '\u2008', '\u2009', '\u200A', '\u202F', '\u205F', '\u3000' -> ' '
            else -> ch
        }
    }.joinToString("")

private suspend fun readAgentFile(state: IdeUiState, call: UiAgentToolCall): AgentToolResult {
    val rootPath = normalizeWorkspacePath(state.backend.project.rootPath)
    val requested = call.stringArguments["path"]
    val active = state.active
    val path = if (requested.isNullOrBlank()) {
        active?.path ?: return toolFailure("没有当前文件")
    } else {
        resolveAgentPatchPath(rootPath, requested) ?: return toolFailure("路径不在工作区内：$requested")
    }
    val normalizedPath = normalizeWorkspacePath(path)
    val known = collectFilePaths(state.backend.files.fileTree(TreeViewMode.AllFiles)).map(::normalizeWorkspacePath).toSet()
    if (normalizedPath !in known && normalizedPath != normalizeWorkspacePath(active?.path.orEmpty())) {
        return toolFailure("文件不存在或不在工作区内：$path")
    }
    val text = state.openFiles.firstOrNull { normalizeWorkspacePath(it.path) == normalizedPath }?.text
        ?: state.backend.files.readFile(normalizedPath)
    val symbol = call.stringArguments["symbol"]?.takeIf { it.isNotBlank() }
    val range = if (symbol != null) {
        val structure = runCatching { state.backend.editor.fileStructure(normalizedPath, text) }.getOrDefault(emptyList())
        symbolRange(text, structure, symbol) ?: return toolFailure("未找到符号：$symbol")
    } else {
        requestedRange(text, call)
    }
    val includeLineNumbers = call.booleanArgument("includeLineNumbers", default = true)
    val body = formatFileRead(normalizedPath, text, range.first, range.last + 1, includeLineNumbers)
    return toolSuccess(body)
}

private fun requestedRange(text: String, call: UiAgentToolCall): IntRange {
    val offset = call.stringArguments["offset"]?.toIntOrNull()
    val length = call.stringArguments["length"]?.toIntOrNull()
    if (offset != null) {
        val start = offset.coerceIn(0, text.length)
        val end = (start + (length ?: 20000).coerceAtLeast(0)).coerceIn(start, text.length)
        return start until end
    }
    val starts = lineStarts(text)
    val startLine = call.stringArguments["startLine"]?.toIntOrNull()?.coerceAtLeast(1)
    val endLine = call.stringArguments["endLine"]?.toIntOrNull()?.coerceAtLeast(1)
    if (startLine != null || endLine != null) {
        val first = (startLine ?: 1).coerceAtMost(starts.size)
        val last = (endLine ?: (first + 199)).coerceAtLeast(first).coerceAtMost(starts.size)
        return lineOffsetRange(text, starts, first, last)
    }
    return 0 until text.length.coerceAtMost(20000)
}

private fun symbolRange(text: String, structure: List<UiFileSymbol>, symbol: String): IntRange? {
    val match = structure.firstOrNull { it.name == symbol }
        ?: structure.firstOrNull { it.name.contains(symbol, ignoreCase = true) }
        ?: return null
    val starts = lineStarts(text)
    val startLine = lineNumberForOffset(starts, match.nameOffset).coerceAtLeast(1)
    val endLine = lineNumberForOffset(starts, match.endOffset).coerceAtLeast(startLine)
    return lineOffsetRange(text, starts, startLine, endLine)
}

private fun formatFileRead(path: String, text: String, start: Int, end: Int, includeLineNumbers: Boolean): String {
    val clippedStart = start.coerceIn(0, text.length)
    val clippedEnd = end.coerceIn(clippedStart, text.length)
    val content = text.substring(clippedStart, clippedEnd)
    if (!includeLineNumbers) return "path: $path\n```\n$content\n```"
    val firstLine = lineNumberForOffset(lineStarts(text), clippedStart)
    val numbered = content.lineSequence().mapIndexed { index, line ->
        "${firstLine + index}: $line"
    }.joinToString("\n")
    return "path: $path\nlines: $firstLine-${firstLine + content.lineSequence().count().coerceAtLeast(1) - 1}\n```\n$numbered\n```"
}

private fun lineStarts(text: String): List<Int> {
    val starts = ArrayList<Int>()
    starts += 0
    text.forEachIndexed { index, ch -> if (ch == '\n' && index + 1 <= text.length) starts += index + 1 }
    return starts
}

private fun lineNumberForOffset(starts: List<Int>, offset: Int): Int {
    var low = 0
    var high = starts.lastIndex
    while (low <= high) {
        val mid = (low + high) ushr 1
        if (starts[mid] <= offset) low = mid + 1 else high = mid - 1
    }
    return high.coerceAtLeast(0) + 1
}

private fun lineOffsetRange(text: String, starts: List<Int>, startLine: Int, endLine: Int): IntRange {
    val start = starts[(startLine - 1).coerceIn(0, starts.lastIndex)]
    val end = if (endLine < starts.size) starts[endLine] else text.length
    return start until end
}

private fun globWorkspaceFiles(rootPath: String, filePaths: List<String>, pattern: String): List<String> {
    val regex = globToRegex(pattern.replace('\\', '/'))
    val root = normalizeWorkspacePath(rootPath)
    return filePaths
        .map(::normalizeWorkspacePath)
        .filter { path ->
            val rel = displayWorkspacePath(root, path)
            regex.matches(path) || regex.matches(rel)
        }
        .sorted()
}

private fun grepWorkspaceFiles(
    rootPath: String,
    filePaths: List<String>,
    readFile: (String) -> String,
    query: String,
    pathGlob: String?,
    regex: Boolean,
    caseSensitive: Boolean,
    limit: Int,
): List<String> {
    val options = if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
    val needle = runCatching { Regex(if (regex) query else Regex.escape(query), options) }.getOrNull() ?: return listOf("无效正则：$query")
    val root = normalizeWorkspacePath(rootPath)
    val files = if (pathGlob.isNullOrBlank()) filePaths.map(::normalizeWorkspacePath)
    else globWorkspaceFiles(root, filePaths, pathGlob)
    val out = ArrayList<String>()
    for (path in files.sorted()) {
        val text = runCatching { readFile(path) }.getOrDefault("")
        text.lineSequence().forEachIndexed { index, line ->
            val match = needle.find(line)
            if (match != null) {
                out += "${displayWorkspacePath(root, path)}:${index + 1}:${match.range.first + 1}: $line"
                if (out.size >= limit) return out
            }
        }
    }
    return out
}

private fun globToRegex(pattern: String): Regex {
    val out = StringBuilder("^")
    var i = 0
    while (i < pattern.length) {
        when (val ch = pattern[i]) {
            '*' -> {
                if (i + 1 < pattern.length && pattern[i + 1] == '*') {
                    out.append(".*")
                    i++
                } else {
                    out.append("[^/]*")
                }
            }
            '?' -> out.append("[^/]")
            '.', '(', ')', '+', '|', '^', '$', '@', '%', '{', '}', '[', ']', '\\' -> out.append('\\').append(ch)
            else -> out.append(ch)
        }
        i++
    }
    out.append('$')
    return Regex(out.toString())
}

private fun resolveAgentPatchPath(rootPath: String, path: String): String? {
    val normalizedRoot = normalizeWorkspacePath(rootPath).trimEnd('/')
    val raw = path.trim().replace('\\', '/')
    if (raw.isEmpty()) return null
    val resolved = if (raw.startsWith("/")) normalizeWorkspacePath(raw) else normalizeWorkspacePath("$normalizedRoot/$raw")
    return resolved.takeIf { it == normalizedRoot || it.startsWith("$normalizedRoot/") }
}

private fun normalizeWorkspacePath(path: String): String {
    val absolute = path.replace('\\', '/')
    val prefix = if (absolute.startsWith("/")) "/" else ""
    val parts = ArrayList<String>()
    absolute.split('/').forEach { part ->
        when {
            part.isEmpty() || part == "." -> Unit
            part == ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
            else -> parts += part
        }
    }
    return prefix + parts.joinToString("/")
}

private fun displayWorkspacePath(rootPath: String, path: String): String {
    val root = normalizeWorkspacePath(rootPath).trimEnd('/')
    val normalized = normalizeWorkspacePath(path)
    return normalized.removePrefix("$root/").takeIf { it != normalized } ?: normalized
}

private fun ensurePatchCanTouchOpenFile(state: IdeUiState, path: String): String? {
    val file = state.openFiles.firstOrNull { normalizeWorkspacePath(it.path) == path } ?: return null
    return if (file.modified) "文件有未保存修改，拒绝覆盖：$path" else null
}

private fun syncOpenFilesAfterPatch(state: IdeUiState, changes: List<AgentAppliedChange>) {
    changes.forEach { change ->
        when (change) {
            is AgentAppliedChange.Add -> updateOpenFileAfterPatch(state, change.path, change.text)
            is AgentAppliedChange.Update -> updateOpenFileAfterPatch(state, change.path, change.text)
            is AgentAppliedChange.Delete -> closeOpenFileAfterPatch(state, change.path)
            is AgentAppliedChange.Move -> {
                closeOpenFileAfterPatch(state, change.from)
                updateOpenFileAfterPatch(state, change.path, change.text)
            }
        }
    }
    state.activeIndex = state.activeIndex.coerceAtMost(state.openFiles.lastIndex)
}

private fun updateOpenFileAfterPatch(state: IdeUiState, path: String, text: String) {
    val index = state.openFiles.indexOfFirst { normalizeWorkspacePath(it.path) == path }
    if (index < 0) return
    val name = path.substringAfterLast('/').substringAfterLast('\\')
    state.openFiles[index] = OpenFile(path, name, text)
    state.backend.editor.updateDocument(path, text)
}

private fun closeOpenFileAfterPatch(state: IdeUiState, path: String) {
    val index = state.openFiles.indexOfFirst { normalizeWorkspacePath(it.path) == path }
    if (index >= 0) state.openFiles.removeAt(index)
}

private fun buildPatchSummary(added: List<String>, modified: List<String>, deleted: List<String>): String = buildString {
    append("Success. Updated the following files:\n")
    added.forEach { append("A ").append(it).append('\n') }
    modified.forEach { append("M ").append(it).append('\n') }
    deleted.forEach { append("D ").append(it).append('\n') }
}

private fun UiAgentToolCall.intArgument(name: String, default: Int, min: Int, max: Int): Int =
    stringArguments[name]?.toIntOrNull()?.coerceIn(min, max) ?: default

private fun UiAgentToolCall.booleanArgument(name: String, default: Boolean = false): Boolean =
    when (stringArguments[name]?.lowercase()) {
        "true", "1", "yes", "y" -> true
        "false", "0", "no", "n" -> false
        else -> default
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
