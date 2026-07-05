package dev.ide.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ide.ui.AgentConfig
import dev.ide.ui.AgentMessage
import dev.ide.ui.IdeUiState
import dev.ide.ui.backend.TreeNode
import dev.ide.ui.backend.UiAgentConfig
import dev.ide.ui.backend.UiAgentInputItem
import dev.ide.ui.backend.UiAgentRequest
import dev.ide.ui.backend.UiAgentTool
import dev.ide.ui.backend.UiAgentToolCall
import dev.ide.ui.backend.UiDiagnostic
import dev.ide.ui.components.BottomSheet
import dev.ide.ui.components.Chip
import dev.ide.ui.components.DialogButton
import dev.ide.ui.components.DialogField
import dev.ide.ui.components.FieldLabel
import dev.ide.ui.components.IconButtonCa
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.theme.Ca
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun AgentDock(state: IdeUiState, modifier: Modifier = Modifier) {
    AgentPanel(state = state, modifier = modifier.fillMaxSize().padding(14.dp))
}

@Composable
internal fun AgentSheets(state: IdeUiState, compact: Boolean) {
    if (compact) {
        BottomSheet(visible = state.agentOpen, onDismiss = { state.agentOpen = false }, heightFraction = 0.9f) {
            AgentPanel(state = state, modifier = Modifier.fillMaxWidth().weight(1f).padding(start = 14.dp, end = 14.dp, bottom = 14.dp))
        }
    }
    BottomSheet(visible = state.agentConfigOpen, onDismiss = { state.agentConfigOpen = false }, heightFraction = 0.72f) {
        AgentConfigSheet(
            initial = state.agentConfig,
            onSave = {
                state.saveAgentConfig(it)
                state.agentConfigOpen = false
                state.agentOpen = true
                state.consoleOpen = false
            },
            modifier = Modifier.fillMaxWidth().weight(1f).padding(start = 14.dp, end = 14.dp, bottom = 14.dp),
        )
    }
}

@Composable
private fun AgentPanel(state: IdeUiState, modifier: Modifier = Modifier) {
    val active = state.active
    val messages = state.agentMessages
    val listState = rememberLazyListState()
    val lastMessageText = messages.lastOrNull()?.text.orEmpty()

    LaunchedEffect(messages.size, lastMessageText) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    Column(modifier.fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(CaIcons.sparkle, null, Modifier.size(18.dp), tint = Ca.colors.accent)
            Column(Modifier.weight(1f)) {
                Text("AI Agent", color = Ca.colors.textPrimary, style = Ca.type.headline, fontWeight = FontWeight.SemiBold)
                Text(
                    if (state.agentConfig.configured) "${state.agentConfig.model} · ${state.agentConfig.reasoningEffort}" else "未配置",
                    color = Ca.colors.textTertiary,
                    style = Ca.type.caption2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButtonCa(CaIcons.gear, "Agent settings", { state.agentConfigOpen = true }, boxSize = 30, iconSize = 16)
            IconButtonCa(CaIcons.close, "Close Agent", { state.agentOpen = false }, boxSize = 30, iconSize = 16)
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            active?.let { Chip(it.name, fill = Ca.colors.surface2, textColor = Ca.colors.textSecondary) }
            Chip("tools", fill = Ca.colors.surface2, textColor = Ca.colors.textSecondary)
        }

        Box(Modifier.fillMaxWidth().height(1.dp).background(Ca.colors.separator))

        if (messages.isEmpty()) {
            Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("描述要处理的问题。Agent 会按需读取文件、诊断和日志，并在需要时修改当前编辑器。", color = Ca.colors.textTertiary, style = Ca.type.caption)
                Spacer(Modifier.weight(1f))
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(messages, key = { it.hashCode() }) { message -> MessageBubble(message) }
            }
        }

        Composer(
            value = state.agentPrompt,
            onValueChange = { state.agentPrompt = it },
            onSend = {
                if (state.agentSending) return@Composer
                val request = state.agentPrompt.trim()
                if (request.isEmpty()) return@Composer
                if (!state.agentConfig.configured) {
                    state.agentConfigOpen = true
                    return@Composer
                }
                messages += AgentMessage("user", request)
                state.agentPrompt = ""
                state.agentSending = true
                state.agentReceivedChars = 0
                state.agentJob = state.agentScope.launch {
                    try {
                        val text = runAgentLoop(state, request, messages) { delta ->
                            state.agentScope.launch {
                                state.agentReceivedChars += delta.length
                                appendAgentDelta(messages, delta)
                            }
                        }
                        if (text.isNotBlank()) messages += AgentMessage("agent", text)
                    } catch (e: CancellationException) {
                        messages += AgentMessage("agent", "已停止。")
                    } catch (e: Throwable) {
                        messages += AgentMessage(
                            "agent",
                            if (state.agentJob?.isCancelled == true) "已停止。" else "请求失败：${e.message ?: "unknown error"}",
                        )
                    } finally {
                        state.agentSending = false
                        state.agentJob = null
                    }
                }
            },
            onStop = {
                state.agentJob?.cancel()
            },
            sending = state.agentSending,
            receivedChars = state.agentReceivedChars,
        )
    }
}

@Composable
private fun ContextToggle(label: String, selected: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    val fill = when {
        selected && enabled -> Ca.colors.accentSoft
        enabled -> Ca.colors.surface2
        else -> Ca.colors.surface2.copy(alpha = 0.5f)
    }
    val fg = when {
        selected && enabled -> Ca.colors.accent
        enabled -> Ca.colors.textSecondary
        else -> Ca.colors.textTertiary.copy(alpha = 0.55f)
    }
    Box(
        Modifier
            .height(28.dp)
            .background(fill, RoundedCornerShape(Ca.radius.pill))
            .let { if (enabled) it.clickable { onChange(!selected) } else it }
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = fg, style = Ca.type.caption, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium)
    }
}

@Composable
private fun MessageBubble(message: AgentMessage) {
    val agent = message.role == "agent"
    val tool = message.role == "tool"
    if (tool) {
        ToolMessageBubble(message)
        return
    }
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (agent) Arrangement.Start else Arrangement.End,
    ) {
        Column(
            Modifier
                .widthIn(max = if (agent) 520.dp else 360.dp)
                .background(if (agent) Ca.colors.surface2.copy(alpha = 0.62f) else Ca.colors.accentSoft, RoundedCornerShape(Ca.radius.sm))
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                if (agent) "Agent" else "You",
                color = if (agent) Ca.colors.textTertiary else Ca.colors.accent,
                style = Ca.type.caption2,
                fontWeight = FontWeight.SemiBold,
            )
            Text(message.text, color = Ca.colors.textPrimary, style = Ca.type.codeSmall)
        }
    }
}

@Composable
private fun ToolMessageBubble(message: AgentMessage) {
    var expanded by remember(message) { mutableStateOf(false) }
    val (name, output) = remember(message.text) { splitToolMessage(message.text) }
    Column(
        Modifier.fillMaxWidth()
            .background(Ca.colors.surface2, RoundedCornerShape(Ca.radius.sm))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                if (expanded) CaIcons.caretDown else CaIcons.caretRight,
                null,
                Modifier.size(13.dp),
                tint = Ca.colors.textTertiary,
            )
            Text("Tool", color = Ca.colors.textTertiary, style = Ca.type.caption2, fontWeight = FontWeight.SemiBold)
            Text(name, color = Ca.colors.textPrimary, style = Ca.type.codeSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }
        if (expanded && output.isNotBlank()) {
            Text(output, color = Ca.colors.textPrimary, style = Ca.type.codeSmall)
        }
    }
}

private fun splitToolMessage(text: String): Pair<String, String> {
    val lineEnd = text.indexOf('\n')
    if (lineEnd < 0) return text to ""
    return text.substring(0, lineEnd) to text.substring(lineEnd + 1)
}

@Composable
private fun Composer(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    sending: Boolean,
    receivedChars: Int,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (sending) {
            Text(
                "流式接收 · $receivedChars 字符",
                color = Ca.colors.textTertiary,
                style = Ca.type.caption2,
                maxLines = 1,
            )
        }
        Box(
            Modifier.fillMaxWidth().heightIn(min = 48.dp, max = 120.dp)
                .background(Ca.colors.surface2, RoundedCornerShape(Ca.radius.control))
                .border(1.dp, Ca.colors.hairline, RoundedCornerShape(Ca.radius.control)),
        ) {
            if (value.isEmpty()) {
                Text(
                    "询问或要求修改当前文件…",
                    color = Ca.colors.textTertiary,
                    style = Ca.type.footnote,
                    modifier = Modifier.padding(start = 12.dp, top = 9.dp, end = 44.dp, bottom = 12.dp),
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                textStyle = Ca.type.footnote.copy(color = Ca.colors.textPrimary),
                cursorBrush = SolidColor(Ca.colors.accent),
                modifier = Modifier.fillMaxWidth().padding(start = 12.dp, top = 9.dp, end = 44.dp, bottom = 12.dp),
            )
            val canSend = value.isNotBlank()
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 8.dp, bottom = 8.dp)
                    .size(26.dp)
                    .background(
                        when {
                            sending -> Ca.colors.error.copy(alpha = 0.16f)
                            canSend -> Ca.colors.accent
                            else -> Ca.colors.surface3
                        },
                        RoundedCornerShape(Ca.radius.pill),
                    )
                    .clickable(enabled = sending || canSend) {
                        if (sending) onStop() else onSend()
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (sending) CaIcons.stop else CaIcons.arrowUp,
                    if (sending) "Stop" else "Send",
                    Modifier.size(if (sending) 20.dp else 13.dp),
                    tint = when {
                        sending -> Ca.colors.error
                        canSend -> Ca.colors.textOnAccent
                        else -> Ca.colors.textTertiary
                    },
                )
            }
        }
    }
}

@Composable
private fun AgentConfigSheet(initial: AgentConfig, onSave: (AgentConfig) -> Unit, modifier: Modifier = Modifier) {
    var baseUrl by remember(initial) { mutableStateOf(initial.baseUrl) }
    var apiKey by remember(initial) { mutableStateOf(initial.apiKey) }
    var model by remember(initial) { mutableStateOf(initial.model) }
    var effort by remember(initial) { mutableStateOf(initial.reasoningEffort.ifBlank { "high" }) }
    var showKey by remember { mutableStateOf(false) }
    val valid = baseUrl.isNotBlank() && apiKey.isNotBlank() && model.isNotBlank()

    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(CaIcons.sparkle, null, Modifier.size(18.dp), tint = Ca.colors.accent)
            Text("配置 AI Agent", color = Ca.colors.textPrimary, style = Ca.type.headline, fontWeight = FontWeight.SemiBold)
        }
        ConfigTextField("Base URL", baseUrl, { baseUrl = it }, "http://127.0.0.1:3021/v1")
        ConfigTextField("API Key", apiKey, { apiKey = it }, "sk-...", secret = !showKey)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            DialogButton(if (showKey) "隐藏 Key" else "显示 Key", primary = false, enabled = true, onClick = { showKey = !showKey })
        }
        ConfigTextField("Model", model, { model = it }, "gpt-5.5")
        FieldLabel("思考等级")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("low", "medium", "high").forEach { option ->
                ContextToggle(option, effort == option, true) { effort = option }
            }
        }
        Spacer(Modifier.height(2.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("配置会保存到 IDE 全局偏好。", color = Ca.colors.textTertiary, style = Ca.type.caption, modifier = Modifier.weight(1f))
            DialogButton("保存", primary = true, enabled = valid, onClick = {
                onSave(AgentConfig(baseUrl.trim(), apiKey, model.trim(), effort))
            })
        }
    }
}

@Composable
private fun ConfigTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    secret: Boolean = false,
) {
    FieldLabel(label)
    if (!secret) {
        DialogField(value = value, onValueChange = onValueChange, placeholder = placeholder, focusRequester = null, onSubmit = {}, onCancel = {})
        return
    }
    Box(
        Modifier.fillMaxWidth().height(40.dp)
            .background(Ca.colors.surface2, RoundedCornerShape(Ca.radius.control))
            .border(1.dp, Ca.colors.hairline, RoundedCornerShape(Ca.radius.control))
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.isEmpty()) Text(placeholder, color = Ca.colors.textTertiary, style = Ca.type.footnote)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = Ca.type.footnote.copy(color = Ca.colors.textPrimary),
            cursorBrush = SolidColor(Ca.colors.accent),
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private suspend fun runAgentLoop(
    state: IdeUiState,
    request: String,
    messages: MutableList<AgentMessage>,
    onTextDelta: (String) -> Unit,
): String {
    val config = UiAgentConfig(
        baseUrl = state.agentConfig.baseUrl,
        apiKey = state.agentConfig.apiKey,
        model = state.agentConfig.model,
        reasoningEffort = state.agentConfig.reasoningEffort,
    )
    val tools = agentTools()
    val input = mutableListOf(
        UiAgentInputItem(
            type = "message",
            role = "user",
            content = buildString {
                append("You are an AI coding agent embedded in CodeAssist IDE. ")
                append("Use tools to inspect the workspace, diagnostics, logs, and to modify the currently edited buffer when needed. ")
                append("When modifying code, prefer replace_current_selection for focused edits and replace_current_file only when a whole-file rewrite is necessary.")
                append("\n\nUser request:\n").append(request)
            },
        )
    )
    repeat(8) {
        var receivedTextDelta = false
        val response = state.backend.agent.respond(
            UiAgentRequest(
                config = config,
                input = input.toList(),
                tools = tools,
            )
        ) { delta ->
            if (!isUsefulAgentText(delta)) return@respond
            receivedTextDelta = true
            onTextDelta(delta)
        }
        if (response.toolCalls.isEmpty()) {
            if (receivedTextDelta && isUsefulAgentText(response.text)) return ""
            if (isUsefulAgentText(response.text)) return response.text
            return "完成。"
        }
        response.toolCalls.forEach { call ->
            val output = executeAgentTool(state, call)
            messages += AgentMessage("tool", "${call.name}\n$output")
            input.addToolOutput(call, output)
        }
    }
    return "工具调用次数过多，已停止。"
}

private fun isUsefulAgentText(text: String): Boolean {
    val value = text.trim()
    if (value.isEmpty() || value == "null") return false
    if (Regex("""(?:\{\}|\[\])+\s*""").matches(value)) return false
    return true
}

private fun appendAgentDelta(messages: MutableList<AgentMessage>, delta: String) {
    val last = messages.lastOrNull()
    val msg = if (last?.role == "agent") last else AgentMessage("agent", "").also { messages += it }
    msg.text += delta
}

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
        "replace_current_selection",
        "Replace the current editor selection, or insert at the caret if the selection is empty.",
        """{"type":"object","properties":{"text":{"type":"string"}},"required":["text"],"additionalProperties":false}""",
    ),
    UiAgentTool(
        "replace_current_file",
        "Replace the entire current editor buffer with the supplied text.",
        """{"type":"object","properties":{"text":{"type":"string"}},"required":["text"],"additionalProperties":false}""",
    ),
)

private fun executeAgentTool(state: IdeUiState, call: UiAgentToolCall): String {
    val active = state.active
    return runCatching {
        when (call.name) {
            "list_workspace_files" -> collectFilePaths(state.tree).take(500).joinToString("\n")
            "read_workspace_file" -> {
                val path = call.stringArguments["path"] ?: return "missing path"
                val known = collectFilePaths(state.tree).toSet()
                if (path !in known && !path.startsWith(state.backend.project.rootPath)) return "path is outside workspace"
                if (active?.path == path) active.text else state.backend.files.readFile(path).take(20000)
            }
            "get_current_file" -> {
                if (active == null) "no current file"
                else "path: ${active.path}\n```\n${active.text.take(20000)}\n```"
            }
            "get_diagnostics" -> {
                val diagnostics = active?.session?.diagnostics.orEmpty()
                if (diagnostics.isEmpty()) "no diagnostics"
                else buildString { appendDiagnostics(this, diagnostics) }
            }
            "get_build_logs" -> {
                val log = state.backend.build.buildState.value.log.takeLast(160)
                if (log.isEmpty()) "no build logs"
                else log.joinToString("\n") { "${it.timeLabel} ${it.level}: ${it.message}" }
            }
            "get_ide_logs" -> {
                val logs = state.backend.diagnostics.recentLogs().takeLast(160)
                if (logs.isEmpty()) "no IDE logs"
                else logs.joinToString("\n") { "${it.timeLabel} ${it.level}/${it.tag}: ${it.message}" }
            }
            "replace_current_selection" -> {
                val text = call.stringArguments["text"] ?: return "missing text"
                val session = active?.session ?: return "no current file"
                val start = session.selection.min
                session.replaceRange(start, session.selection.max, text, TextRange(start + text.length))
                "updated ${active.path} at selection"
            }
            "replace_current_file" -> {
                val text = call.stringArguments["text"] ?: return "missing text"
                val session = active?.session ?: return "no current file"
                session.replaceRange(0, session.doc.length, text, TextRange(text.length.coerceAtMost(session.doc.length + text.length)))
                "replaced ${active.path}"
            }
            else -> "unknown tool: ${call.name}"
        }
    }.getOrElse { "tool failed: ${it.message ?: "unknown error"}" }
}

private fun MutableList<UiAgentInputItem>.addToolOutput(call: UiAgentToolCall, output: String) {
    add(
        UiAgentInputItem(
            type = "function_call",
            callId = call.callId,
            name = call.name,
            argumentsJson = call.argumentsJson,
        )
    )
    add(
        UiAgentInputItem(
            type = "function_call_output",
            callId = call.callId,
            output = output,
        )
    )
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
