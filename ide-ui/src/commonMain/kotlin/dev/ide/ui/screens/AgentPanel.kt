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
import dev.ide.ui.AgentConversation
import dev.ide.ui.AgentConversationItem
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
    BottomSheet(visible = state.agentHistoryOpen, onDismiss = { state.agentHistoryOpen = false }, heightFraction = 0.6f) {
        AgentHistorySheet(
            state = state,
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
            IconButtonCa(CaIcons.plus, "New conversation", { state.createAgentConversation() }, boxSize = 30, iconSize = 16)
            IconButtonCa(CaIcons.docText, "Conversation history", { state.agentHistoryOpen = true }, boxSize = 30, iconSize = 16)
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
                messages += AgentConversationItem("message", "user", request)
                state.recordAgentChanged()
                state.agentPrompt = ""
                state.agentSending = true
                state.agentReceivedChars = 0
                state.agentJob = state.agentScope.launch {
                    try {
                        val text = runAgentLoop(
                            state = state,
                            messages = messages,
                            onTextDelta = { delta ->
                                state.agentScope.launch {
                                    appendAgentDelta(messages, delta)
                                }
                            },
                            onStreamChars = { count ->
                                state.agentScope.launch {
                                    state.agentReceivedChars += count
                                }
                            },
                        )
                        if (text.isNotBlank()) messages += AgentConversationItem("message", "assistant", text)
                    } catch (e: CancellationException) {
                        messages += AgentConversationItem("message", "assistant", "已停止。")
                    } catch (e: Throwable) {
                        messages += AgentConversationItem(
                            "message",
                            "assistant",
                            if (state.agentJob?.isCancelled == true) "已停止。" else "请求失败：${e.message ?: "unknown error"}",
                        )
                    } finally {
                        state.agentSending = false
                        state.agentJob = null
                        state.recordAgentChanged()
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
private fun MessageBubble(message: AgentConversationItem) {
    val agent = message.role == "assistant"
    val tool = message.type == "function_call_output"
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
private fun ToolMessageBubble(message: AgentConversationItem) {
    var expanded by remember(message) { mutableStateOf(false) }
    val name = message.name ?: "tool"
    val output = message.text
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

@Composable
private fun AgentHistorySheet(state: IdeUiState, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(CaIcons.docText, null, Modifier.size(18.dp), tint = Ca.colors.accent)
            Text("对话历史", color = Ca.colors.textPrimary, style = Ca.type.headline, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            IconButtonCa(CaIcons.plus, "New conversation", {
                state.createAgentConversation()
                state.agentHistoryOpen = false
            }, boxSize = 30, iconSize = 16)
        }
        if (state.agentConversations.isEmpty()) {
            Text("暂无历史。", color = Ca.colors.textTertiary, style = Ca.type.caption)
        } else {
            LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(state.agentConversations, key = { it.id }) { conversation ->
                    AgentConversationRow(
                        conversation = conversation,
                        active = conversation.id == state.activeAgentConversationId,
                        onSelect = { state.selectAgentConversation(conversation.id) },
                        onDelete = { state.deleteAgentConversation(conversation.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun AgentConversationRow(
    conversation: AgentConversation,
    active: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .background(if (active) Ca.colors.accentSoft else Ca.colors.surface2, RoundedCornerShape(Ca.radius.sm))
            .clickable { onSelect() }
            .padding(start = 10.dp, top = 8.dp, end = 6.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                conversation.title,
                color = if (active) Ca.colors.accent else Ca.colors.textPrimary,
                style = Ca.type.footnote,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${conversation.items.count { it.type == "message" }} 条消息",
                color = Ca.colors.textTertiary,
                style = Ca.type.caption2,
                maxLines = 1,
            )
        }
        IconButtonCa(CaIcons.close, "Delete conversation", onDelete, boxSize = 28, iconSize = 14)
    }
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
            if (receivedTextDelta && isUsefulAgentText(response.text)) return ""
            if (isUsefulAgentText(response.text)) return response.text
            return "完成。"
        }
        response.toolCalls.forEach { call ->
            messages += AgentConversationItem(
                type = "function_call",
                callId = call.callId,
                name = call.name,
                argumentsJson = call.argumentsJson,
            )
            val output = executeAgentTool(state, call)
            messages += AgentConversationItem(
                type = "function_call_output",
                text = output,
                callId = call.callId,
                name = call.name,
            )
            state.recordAgentChanged()
        }
    }
    return "工具调用次数过多，已停止。"
}

private fun agentInstructions(): String =
    "You are an AI coding agent embedded in CodeAssist IDE. " +
        "Use tools to inspect the workspace, diagnostics, logs, and to modify the currently edited buffer when needed. " +
        "When modifying code, use apply_patch with a unified patch for the current editor file."

private fun compactAgentInput(messages: List<AgentConversationItem>): List<UiAgentInputItem> {
    val (dropped, retainedItems) = splitTailFromLastUserMessages(messages, userMessageCount = 10)
    val retained = retainedItems.toMutableList()
    truncateAssistantText(retained, maxChars = 12000)
    val input = ArrayList<UiAgentInputItem>()
    buildCompactedSummary(dropped)?.let { summary ->
        input += UiAgentInputItem(
            type = "message",
            role = "developer",
            content = summary,
        )
    }
    input += retained.mapNotNull { item ->
        when (item.type) {
            "message" -> UiAgentInputItem(
                type = "message",
                role = item.role ?: "user",
                content = item.text,
            )
            "function_call" -> UiAgentInputItem(
                type = "function_call",
                callId = item.callId,
                name = item.name,
                argumentsJson = item.argumentsJson,
            )
            "function_call_output" -> UiAgentInputItem(
                type = "function_call_output",
                callId = item.callId,
                output = item.text,
            )
            else -> null
        }
    }
    return input
}

private fun splitTailFromLastUserMessages(
    items: List<AgentConversationItem>,
    userMessageCount: Int,
): Pair<List<AgentConversationItem>, List<AgentConversationItem>> {
    if (userMessageCount <= 0) return items to emptyList()
    val latestUser = items.indexOfLast { it.type == "message" && it.role == "user" }
    if (latestUser < 0) return items to emptyList()
    val throughLatestUser = items.take(latestUser + 1)
    var seen = 0
    var start = latestUser
    for (i in throughLatestUser.indices.reversed()) {
        val item = throughLatestUser[i]
        if (item.type == "message" && item.role == "user") {
            seen++
            start = i
            if (seen >= userMessageCount) break
        }
    }
    return items.take(start) to normalizeToolPairs(items.drop(start))
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
                lines += "Tool output: ${item.name.orEmpty()} ${item.text.oneLineForSummary(220)}"
            }
        }
    }
    if (lines.isEmpty()) return null
    return "Earlier conversation was compacted. Preserve these facts and decisions:\n" +
        lines.takeLast(80).joinToString("\n")
}

private fun String.oneLineForSummary(limit: Int = 360): String {
    val value = trim().replace(Regex("\\s+"), " ")
    return if (value.length <= limit) value else value.take(limit) + "..."
}

private fun normalizeToolPairs(items: List<AgentConversationItem>): List<AgentConversationItem> {
    val callIds = items.asSequence()
        .filter { it.type == "function_call" }
        .mapNotNull { it.callId }
        .toSet()
    return items.filter { item ->
        item.type != "function_call_output" || item.callId in callIds
    }
}

private fun truncateAssistantText(items: MutableList<AgentConversationItem>, maxChars: Int) {
    var remaining = maxChars
    val iterator = items.listIterator()
    while (iterator.hasNext()) {
        val item = iterator.next()
        if (item.type != "message" || item.role != "assistant") continue
        if (remaining <= 0) {
            iterator.remove()
            continue
        }
        if (item.text.length > remaining) {
            iterator.set(AgentConversationItem("message", "assistant", item.text.take(remaining) + "\n... [truncated]"))
            remaining = 0
        } else {
            remaining -= item.text.length
        }
    }
}

private fun isUsefulAgentText(text: String): Boolean {
    val value = text.trim()
    if (value.isEmpty() || value == "null") return false
    if (Regex("""(?:\{\}|\[\])+\s*""").matches(value)) return false
    return true
}

private fun appendAgentDelta(messages: MutableList<AgentConversationItem>, delta: String) {
    val last = messages.lastOrNull()
    val msg = if (last?.type == "message" && last.role == "assistant") {
        last
    } else {
        AgentConversationItem("message", "assistant", "").also { messages += it }
    }
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
        "apply_patch",
        "Apply a unified patch to the current editor file. The patch must use *** Begin Patch, *** Update File: <current path>, @@ hunks, and *** End Patch.",
        """{"type":"object","properties":{"patch":{"type":"string"}},"required":["patch"],"additionalProperties":false}""",
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
            "apply_patch" -> {
                val patch = call.stringArguments["patch"] ?: return "missing patch"
                val file = active ?: return "no current file"
                val result = applyAgentPatch(file.path, file.text, patch)
                if (!result.ok) return result.message
                if (result.text == file.text) return "patch applied to ${file.path}: ${result.message}"
                val session = file.session
                session.replaceRange(0, session.doc.length, result.text, TextRange(result.text.length))
                "applied patch to ${file.path}: ${result.message}"
            }
            else -> "unknown tool: ${call.name}"
        }
    }.getOrElse { "tool failed: ${it.message ?: "unknown error"}" }
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
        text = text.replaceRange(match.first, match.second, hunk.newText)
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
