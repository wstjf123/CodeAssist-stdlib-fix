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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
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
import dev.ide.ui.IdeUiState
import dev.ide.ui.backend.BuildLogLine
import dev.ide.ui.backend.TreeNode
import dev.ide.ui.backend.UiAgentConfig
import dev.ide.ui.backend.UiAgentRequest
import dev.ide.ui.backend.UiDiagnostic
import dev.ide.ui.backend.UiLogEntry
import dev.ide.ui.components.BottomSheet
import dev.ide.ui.components.Chip
import dev.ide.ui.components.DialogButton
import dev.ide.ui.components.DialogField
import dev.ide.ui.components.FieldLabel
import dev.ide.ui.components.IconButtonCa
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.theme.Ca
import kotlinx.coroutines.launch

private data class AgentMessage(val role: String, val text: String)

@Composable
internal fun AgentDock(state: IdeUiState, modifier: Modifier = Modifier) {
    AgentPanel(state = state, modifier = modifier.fillMaxSize().padding(14.dp))
}

@Composable
internal fun AgentSheets(state: IdeUiState, compact: Boolean) {
    if (compact) {
        BottomSheet(visible = state.agentOpen, onDismiss = { state.agentOpen = false }, heightFraction = 0.9f) {
            AgentPanel(state = state, modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 14.dp, bottom = 14.dp))
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
            modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 14.dp, bottom = 14.dp),
        )
    }
}

@Composable
private fun AgentPanel(state: IdeUiState, modifier: Modifier = Modifier) {
    val buildState by state.backend.build.buildState.collectAsState()
    val active = state.active
    val diagnostics = active?.session?.diagnostics.orEmpty()
    val buildLog = buildState.log.takeLast(80)
    val ideLogs = remember(state.backend, state.logsOpen, state.agentOpen) {
        state.backend.diagnostics.recentLogs().takeLast(80)
    }
    var includeFile by remember { mutableStateOf(true) }
    var includeDiagnostics by remember { mutableStateOf(true) }
    var includeWorkspace by remember { mutableStateOf(false) }
    var includeBuildLog by remember { mutableStateOf(false) }
    var includeIdeLog by remember { mutableStateOf(false) }
    var prompt by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val messages = remember { mutableStateListOf<AgentMessage>() }

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

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("上下文", color = Ca.colors.textTertiary, style = Ca.type.caption2, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                ContextToggle("当前文件", includeFile, active != null) { includeFile = it }
                ContextToggle("诊断 ${diagnostics.size}", includeDiagnostics, diagnostics.isNotEmpty()) { includeDiagnostics = it }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                ContextToggle("工作区文件", includeWorkspace, true) { includeWorkspace = it }
                ContextToggle("构建日志", includeBuildLog, buildLog.isNotEmpty()) { includeBuildLog = it }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                ContextToggle("IDE 日志", includeIdeLog, ideLogs.isNotEmpty()) { includeIdeLog = it }
            }
            active?.let { Chip(it.name, fill = Ca.colors.surface2, textColor = Ca.colors.textSecondary) }
        }

        Box(Modifier.fillMaxWidth().height(1.dp).background(Ca.colors.separator))

        if (messages.isEmpty()) {
            Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ToolRow("读取当前文件", active?.path ?: "没有打开文件", active != null)
                ToolRow("读取警告报错", "${diagnostics.size} 条编辑器诊断", diagnostics.isNotEmpty())
                ToolRow("查看构建日志", "${buildLog.size} 行最近日志", buildLog.isNotEmpty())
                ToolRow("查看 IDE 日志", "${ideLogs.size} 条最近记录", ideLogs.isNotEmpty())
                Spacer(Modifier.weight(1f))
                Text(
                    "发送时会携带选中的上下文；回复可插入到当前编辑器光标处。",
                    color = Ca.colors.textTertiary,
                    style = Ca.type.caption,
                )
            }
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(messages) { message -> MessageBubble(message) }
            }
        }

        Composer(
            value = prompt,
            onValueChange = { prompt = it },
            onSend = {
                val request = prompt.trim()
                if (request.isEmpty()) return@Composer
                if (!state.agentConfig.configured) {
                    state.agentConfigOpen = true
                    return@Composer
                }
                val context = buildAgentContext(
                    state = state,
                    includeFile = includeFile,
                    includeDiagnostics = includeDiagnostics,
                    includeWorkspace = includeWorkspace,
                    includeBuildLog = includeBuildLog,
                    includeIdeLog = includeIdeLog,
                    buildLog = buildLog,
                    ideLogs = ideLogs,
                )
                messages += AgentMessage("user", request)
                prompt = ""
                sending = true
                scope.launch {
                    val result = runCatching {
                        state.backend.agent.respond(
                            UiAgentRequest(
                                config = UiAgentConfig(
                                    baseUrl = state.agentConfig.baseUrl,
                                    apiKey = state.agentConfig.apiKey,
                                    model = state.agentConfig.model,
                                    reasoningEffort = state.agentConfig.reasoningEffort,
                                ),
                                prompt = request,
                                context = context,
                                stream = true,
                            )
                        )
                    }
                    val text = result.fold(
                        onSuccess = { it.text },
                        onFailure = { e -> "请求失败：${e.message ?: "unknown error"}" },
                    )
                    messages += AgentMessage("agent", text)
                    sending = false
                }
            },
            onInsert = {
                val text = messages.lastOrNull { it.role == "agent" }?.text ?: return@Composer
                active?.session?.let { session ->
                    val start = session.selection.min
                    session.replaceRange(start, session.selection.max, text, TextRange(start + text.length))
                }
            },
            canInsert = active != null && messages.any { it.role == "agent" },
            sending = sending,
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
private fun ToolRow(title: String, detail: String, enabled: Boolean) {
    Row(
        Modifier.fillMaxWidth().background(Ca.colors.surface2, RoundedCornerShape(Ca.radius.sm)).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(if (enabled) CaIcons.check else CaIcons.info, null, Modifier.size(15.dp), tint = if (enabled) Ca.colors.success else Ca.colors.textTertiary)
        Column(Modifier.weight(1f)) {
            Text(title, color = Ca.colors.textPrimary, style = Ca.type.footnote, fontWeight = FontWeight.SemiBold)
            Text(detail, color = Ca.colors.textTertiary, style = Ca.type.caption2, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun MessageBubble(message: AgentMessage) {
    val agent = message.role == "agent"
    Column(
        Modifier.fillMaxWidth().background(if (agent) Ca.colors.surface2 else Ca.colors.accentSoft, RoundedCornerShape(Ca.radius.sm)).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(if (agent) "Agent" else "You", color = if (agent) Ca.colors.textTertiary else Ca.colors.accent, style = Ca.type.caption2, fontWeight = FontWeight.SemiBold)
        Text(message.text, color = Ca.colors.textPrimary, style = Ca.type.codeSmall)
    }
}

@Composable
private fun Composer(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onInsert: () -> Unit,
    canInsert: Boolean,
    sending: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            Modifier.fillMaxWidth().heightIn(min = 42.dp, max = 120.dp)
                .background(Ca.colors.surface2, RoundedCornerShape(Ca.radius.control))
                .border(1.dp, Ca.colors.hairline, RoundedCornerShape(Ca.radius.control))
                .padding(horizontal = 12.dp, vertical = 9.dp),
        ) {
            if (value.isEmpty()) Text("询问或要求修改当前文件…", color = Ca.colors.textTertiary, style = Ca.type.footnote)
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                textStyle = Ca.type.footnote.copy(color = Ca.colors.textPrimary),
                cursorBrush = SolidColor(Ca.colors.accent),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            DialogButton("插入回复", primary = false, enabled = canInsert, onClick = onInsert)
            Spacer(Modifier.weight(1f))
            DialogButton(if (sending) "发送中" else "发送", primary = true, enabled = value.isNotBlank() && !sending, onClick = onSend)
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

private fun buildAgentContext(
    state: IdeUiState,
    includeFile: Boolean,
    includeDiagnostics: Boolean,
    includeWorkspace: Boolean,
    includeBuildLog: Boolean,
    includeIdeLog: Boolean,
    buildLog: List<BuildLogLine>,
    ideLogs: List<UiLogEntry>,
): String {
    val out = StringBuilder()
    fun appendLine(value: String = "") {
        out.append(value).append('\n')
    }
    val active = state.active
    if (includeFile && active != null) {
        appendLine("## 当前文件")
        appendLine(active.path)
        appendLine("```")
        appendLine(active.text.take(12000))
        appendLine("```")
    }
    if (includeDiagnostics && active != null) {
        appendDiagnostics(out, active.session.diagnostics)
    }
    if (includeWorkspace) {
        appendLine("## 工作区文件")
        collectFilePaths(state.tree).take(200).forEach { appendLine(it) }
    }
    if (includeBuildLog && buildLog.isNotEmpty()) {
        appendLine("## 构建日志")
        buildLog.forEach { entry ->
            out.append(entry.timeLabel).append(' ').append(entry.level).append(": ").append(entry.message).append('\n')
        }
    }
    if (includeIdeLog && ideLogs.isNotEmpty()) {
        appendLine("## IDE 日志")
        ideLogs.forEach { entry ->
            out.append(entry.timeLabel).append(' ').append(entry.level).append('/').append(entry.tag).append(": ").append(entry.message).append('\n')
            entry.stackTrace?.takeIf { it.isNotBlank() }?.let { out.append(it.take(1200)).append('\n') }
        }
    }
    return out.toString().trim()
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
