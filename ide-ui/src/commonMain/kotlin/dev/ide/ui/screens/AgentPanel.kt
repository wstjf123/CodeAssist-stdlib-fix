package dev.ide.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ide.ui.AgentConfig
import dev.ide.ui.AgentConversation
import dev.ide.ui.AgentConversationItem
import dev.ide.ui.IdeUiState
import dev.ide.ui.components.BottomSheet
import dev.ide.ui.components.Chip
import dev.ide.ui.components.DialogButton
import dev.ide.ui.components.DialogField
import dev.ide.ui.components.FieldLabel
import dev.ide.ui.components.IconButtonCa
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.theme.Ca
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

@Composable
internal fun AgentDock(state: IdeUiState, modifier: Modifier = Modifier) {
    AgentPanel(
        state = state,
        modifier = modifier.fillMaxSize().padding(14.dp),
        listBackground = Ca.colors.glassReg,
    )
}

@Composable
internal fun AgentSheets(state: IdeUiState, compact: Boolean) {
    if (compact) {
        BottomSheet(visible = state.agentOpen, onDismiss = { state.agentOpen = false }, heightFraction = 0.9f) {
            AgentPanel(
                state = state,
                modifier = Modifier.fillMaxWidth().weight(1f).padding(start = 14.dp, end = 14.dp, bottom = 14.dp),
                listBackground = Ca.colors.glassThick,
            )
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AgentPanel(
    state: IdeUiState,
    modifier: Modifier = Modifier,
    listBackground: Color = Color.Transparent,
) {
    val active = state.active
    val messages = state.agentMessages
    val listState = rememberLazyListState()
    val displayItems = buildAgentDisplayItems(messages)
    val scrollRevision = displayItems.sumOf { it.contentLength }
    val expandedTools = remember { mutableStateMapOf<String, Boolean>() }
    val lazyItemCount = displayItems.sumOf { if (it.isTool && expandedTools[it.key] == true) 3 else 1 } + 1

    LaunchedEffect(displayItems.size, scrollRevision) {
        if (displayItems.isNotEmpty()) {
            yield()
            listState.scrollToItem(lazyItemCount - 1)
        }
    }

    Column(modifier.fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(CaIcons.sparkle, null, Modifier.size(18.dp), tint = Ca.colors.accent)
            Column(Modifier.weight(1f)) {
                Text("AI 助手", color = Ca.colors.textPrimary, style = Ca.type.headline, fontWeight = FontWeight.SemiBold)
                Text(
                    if (state.agentConfig.configured) "${state.agentConfig.model} · ${state.agentConfig.reasoningEffort}" else "未配置",
                    color = Ca.colors.textTertiary,
                    style = Ca.type.caption2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButtonCa(CaIcons.plus, "新建对话", { state.createAgentConversation() }, boxSize = 30, iconSize = 16)
            IconButtonCa(CaIcons.docText, "对话历史", { state.agentHistoryOpen = true }, boxSize = 30, iconSize = 16)
            IconButtonCa(CaIcons.gear, "助手设置", { state.agentConfigOpen = true }, boxSize = 30, iconSize = 16)
            IconButtonCa(CaIcons.close, "关闭助手", { state.agentOpen = false }, boxSize = 30, iconSize = 16)
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            active?.let { Chip(it.name, fill = Ca.colors.surface2, textColor = Ca.colors.textSecondary) }
            Chip("工具", fill = Ca.colors.surface2, textColor = Ca.colors.textSecondary)
        }

        Box(Modifier.fillMaxWidth().height(1.dp).background(Ca.colors.separator))

        if (displayItems.isEmpty()) {
            Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("描述要处理的问题。助手会按需读取文件、诊断和日志，并在需要时修改当前编辑器。", color = Ca.colors.textTertiary, style = Ca.type.caption)
                Spacer(Modifier.weight(1f))
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) {
                var rowIndex = 0
                displayItems.forEach { item ->
                    val tool = item.isTool
                    val expanded = tool && expandedTools[item.key] == true
                    if (tool && expanded) {
                        val detailsKey = "${item.key}:details"
                        val detailsIndex = rowIndex + 1
                        rowIndex += 3
                        stickyHeader {
                            val detailsVisible by remember(detailsKey, detailsIndex) {
                                derivedStateOf {
                                    listState.firstVisibleItemIndex <= detailsIndex ||
                                        listState.layoutInfo.visibleItemsInfo.any { it.key == detailsKey }
                                }
                            }
                            Box(
                                Modifier.fillMaxWidth()
                                    .background(listBackground)
                                    .padding(bottom = if (detailsVisible) 0.dp else 8.dp),
                            ) {
                                ToolMessageHeader(
                                    message = item.message,
                                    outputItem = item.output,
                                    expanded = true,
                                    connected = detailsVisible,
                                    onToggle = { expandedTools[item.key] = false },
                                )
                            }
                        }
                        item(key = detailsKey) {
                            Box(Modifier.padding(bottom = 8.dp)) {
                                ToolMessageDetails(message = item.message, outputItem = item.output)
                            }
                        }
                        stickyHeader {
                            Spacer(Modifier.height(0.dp))
                        }
                    } else {
                        rowIndex += 1
                        item(key = item.key) {
                            Box(Modifier.padding(bottom = 8.dp)) {
                                MessageBubble(
                                    item = item,
                                    expanded = false,
                                    onToggleTool = { expandedTools[item.key] = true },
                                )
                            }
                        }
                    }
                }
                item(key = "agent:bottom") {
                    Spacer(Modifier.height(1.dp))
                }
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
                val job = state.agentScope.launch {
                    val runningJob = coroutineContext[Job] ?: return@launch
                    try {
                        val text = runAgentLoop(
                            state = state,
                            messages = messages,
                            onTextDelta = { delta ->
                                state.agentScope.launch(runningJob) {
                                    appendAgentDelta(messages, delta)
                                }
                            },
                            onStreamChars = { count ->
                                state.agentScope.launch(runningJob) {
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
                            if (state.agentJob?.isCancelled == true) "已停止。" else "请求失败：${e.message ?: "未知错误"}",
                        )
                    } finally {
                        state.agentSending = false
                        state.agentJob = null
                        state.recordAgentChanged()
                    }
                }
                state.agentJob = job
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

private data class AgentDisplayItem(
    val key: String,
    val message: AgentConversationItem,
    val output: AgentConversationItem? = null,
) {
    val contentLength: Int get() =
        message.text.length + message.argumentsJson.orEmpty().length + output?.text.orEmpty().length
    val isTool: Boolean get() =
        message.type == "function_call" || message.type == "function_call_output"
}

private fun buildAgentDisplayItems(messages: List<AgentConversationItem>): List<AgentDisplayItem> {
    val callsById = messages.asSequence()
        .filter { it.type == "function_call" }
        .mapNotNull { item -> item.callId?.let { it to item } }
        .toMap()
    val outputsById = messages.asSequence()
        .filter { it.type == "function_call_output" }
        .mapNotNull { item -> item.callId?.let { it to item } }
        .toMap()
    return messages.mapNotNull { item ->
        when (item.type) {
            "function_call" -> AgentDisplayItem(
                key = "tool:${item.callId ?: System.identityHashCode(item)}",
                message = item,
                output = item.callId?.let { outputsById[it] },
            )
            "function_call_output" -> {
                if (item.callId != null && callsById.containsKey(item.callId)) null
                else AgentDisplayItem("tool-output:${item.callId ?: System.identityHashCode(item)}", item)
            }
            else -> AgentDisplayItem("item:${System.identityHashCode(item)}", item)
        }
    }
}

@Composable
private fun MessageBubble(item: AgentDisplayItem, expanded: Boolean, onToggleTool: () -> Unit) {
    val message = item.message
    when (message.type) {
        "compaction" -> Unit
        "function_call" -> ToolMessageBubble(message, item.output, expanded, onToggleTool)
        "function_call_output" -> ToolMessageBubble(message, null, expanded, onToggleTool)
        "message" -> if (message.text.isNotBlank()) TextMessageBubble(message)
    }
}

@Composable
private fun TextMessageBubble(message: AgentConversationItem) {
    val agent = message.role == "assistant"
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
                if (agent) "助手" else "你",
                color = if (agent) Ca.colors.textTertiary else Ca.colors.accent,
                style = Ca.type.caption2,
                fontWeight = FontWeight.SemiBold,
            )
            Text(message.text, color = Ca.colors.textPrimary, style = Ca.type.codeSmall)
        }
    }
}

private fun toolMessageState(message: AgentConversationItem, outputItem: AgentConversationItem?): ToolMessageState {
    val name = message.name ?: "工具"
    val output = outputItem?.text ?: message.text
    val arguments = message.argumentsJson.orEmpty()
    val running = message.type == "function_call" && outputItem == null
    val failed = (outputItem ?: message).toolSuccess == false
    return ToolMessageState(name, output, arguments, running, failed)
}

private data class ToolMessageState(
    val name: String,
    val output: String,
    val arguments: String,
    val running: Boolean,
    val failed: Boolean,
)

@Composable
private fun ToolMessageBubble(
    message: AgentConversationItem,
    outputItem: AgentConversationItem?,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth()
            .background(Ca.colors.surface2, RoundedCornerShape(Ca.radius.sm))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ToolMessageHeaderContent(toolMessageState(message, outputItem), expanded, onToggle)
        if (expanded) ToolMessageDetailsContent(toolMessageState(message, outputItem))
    }
}

@Composable
private fun ToolMessageHeader(
    message: AgentConversationItem,
    outputItem: AgentConversationItem?,
    expanded: Boolean,
    connected: Boolean = false,
    onToggle: () -> Unit,
) {
    Box(
        Modifier.fillMaxWidth()
            .background(
                Ca.colors.surface2,
                RoundedCornerShape(
                    topStart = Ca.radius.sm,
                    topEnd = Ca.radius.sm,
                    bottomStart = if (connected) 0.dp else Ca.radius.sm,
                    bottomEnd = if (connected) 0.dp else Ca.radius.sm,
                ),
            )
            .padding(10.dp),
    ) {
        ToolMessageHeaderContent(toolMessageState(message, outputItem), expanded, onToggle)
    }
}

@Composable
private fun ToolMessageHeaderContent(state: ToolMessageState, expanded: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onToggle() },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            if (expanded) CaIcons.caretDown else CaIcons.caretRight,
            null,
            Modifier.size(13.dp),
            tint = Ca.colors.textTertiary,
        )
        Text("工具", color = Ca.colors.textTertiary, style = Ca.type.caption2, fontWeight = FontWeight.SemiBold)
        Text(state.name, color = Ca.colors.textPrimary, style = Ca.type.codeSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (state.running) {
            Text("运行中", color = Ca.colors.accent, style = Ca.type.caption2, fontWeight = FontWeight.SemiBold)
        } else if (state.failed) {
            Text("失败", color = Ca.colors.error, style = Ca.type.caption2, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun ToolMessageDetails(message: AgentConversationItem, outputItem: AgentConversationItem?) {
    Box(
        Modifier.fillMaxWidth()
            .background(
                Ca.colors.surface2,
                RoundedCornerShape(
                    topStart = 0.dp,
                    topEnd = 0.dp,
                    bottomStart = Ca.radius.sm,
                    bottomEnd = Ca.radius.sm,
                ),
            )
            .padding(10.dp),
    ) {
        ToolMessageDetailsContent(toolMessageState(message, outputItem))
    }
}

@Composable
private fun ToolMessageDetailsContent(state: ToolMessageState) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (state.arguments.isNotBlank()) {
            Text(state.arguments, color = Ca.colors.textSecondary, style = Ca.type.codeSmall)
        }
        if (state.output.isNotBlank()) {
            Text(state.output, color = Ca.colors.textPrimary, style = Ca.type.codeSmall)
        }
    }
}

@Composable
private fun AgentHistorySheet(state: IdeUiState, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(CaIcons.docText, null, Modifier.size(18.dp), tint = Ca.colors.accent)
            Text("对话历史", color = Ca.colors.textPrimary, style = Ca.type.headline, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            IconButtonCa(CaIcons.plus, "新建对话", {
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
                "${conversation.items.count { it.type == "message" && it.text.isNotBlank() }} 条消息",
                color = Ca.colors.textTertiary,
                style = Ca.type.caption2,
                maxLines = 1,
            )
        }
        IconButtonCa(CaIcons.close, "删除对话", onDelete, boxSize = 28, iconSize = 14)
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
                    if (sending) "停止" else "发送",
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
            Text("配置 AI 助手", color = Ca.colors.textPrimary, style = Ca.type.headline, fontWeight = FontWeight.SemiBold)
        }
        ConfigTextField("接口地址", baseUrl, { baseUrl = it }, "http://127.0.0.1:3021/v1")
        ConfigTextField("API 密钥", apiKey, { apiKey = it }, "sk-...", secret = !showKey)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            DialogButton(if (showKey) "隐藏密钥" else "显示密钥", primary = false, enabled = true, onClick = { showKey = !showKey })
        }
        ConfigTextField("模型", model, { model = it }, "gpt-5.5")
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
