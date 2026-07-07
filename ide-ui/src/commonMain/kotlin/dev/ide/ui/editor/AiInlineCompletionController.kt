package dev.ide.ui.editor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.UiInlineCompletionRequest
import dev.ide.ui.editor.core.EditorSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

@Stable
internal class AiInlineCompletionController(
    private val session: EditorSession,
    private val backend: IdeBackend,
    private val path: String,
    private val scope: CoroutineScope,
) {
    var enabled: Boolean = false
    var baseUrl: String = ""
    var apiKey: String = ""
    var model: String = ""
    var reasoningEffort: String = "low"
    var delayMs: Int = 450

    var suggestion by mutableStateOf<AiInlineSuggestion?>(null)
        private set

    private var job: Job? = null
    private var suppressedRev = -1

    fun request() {
        job?.cancel()
        if (!enabled || baseUrl.isBlank() || apiKey.isBlank() || model.isBlank()) {
            suggestion = null
            return
        }
        val revision = session.textRevision
        val caret = session.selection.start
        if (!session.selection.collapsed || caret < 0 || caret > session.doc.length || revision == suppressedRev) {
            suggestion = null
            return
        }
        val text = session.doc.text
        if (!shouldRequestInline(text, caret)) {
            suggestion = null
            return
        }
        job = scope.launch {
            delay(delayMs.milliseconds)
            val result = runCatching {
                backend.editor.inlineCompletion(
                    UiInlineCompletionRequest(
                        baseUrl = baseUrl,
                        apiKey = apiKey,
                        model = model,
                        reasoningEffort = reasoningEffort,
                        path = path,
                        language = session.language.name.lowercase(),
                        text = text,
                        offset = caret,
                    )
                )
            }.getOrNull() ?: return@launch
            if (session.textRevision != revision || session.selection.start != caret || !session.selection.collapsed) return@launch
            val cleaned = result.text.cleanInlineSuggestion(text, caret)
            suggestion = cleaned.takeIf { it.isNotBlank() }?.let { AiInlineSuggestion(caret, revision, it) }
        }
    }

    fun dismiss() {
        job?.cancel()
        suggestion = null
    }

    fun accept(): Boolean {
        val item = suggestion ?: return false
        if (session.textRevision != item.revision || session.selection.start != item.offset || !session.selection.collapsed) {
            dismiss()
            return false
        }
        suppressedRev = item.revision + 1
        session.commitText(item.text)
        dismiss()
        return true
    }
}

internal data class AiInlineSuggestion(
    val offset: Int,
    val revision: Int,
    val text: String,
)

@Composable
internal fun rememberAiInlineCompletionController(
    path: String,
    session: EditorSession,
    backend: IdeBackend,
): AiInlineCompletionController {
    val scope = rememberCoroutineScope()
    return remember(path) { AiInlineCompletionController(session, backend, path, scope) }
}

private fun shouldRequestInline(text: String, caret: Int): Boolean {
    if (text.isEmpty()) return false
    val before = text.getOrNull(caret - 1) ?: return false
    if (before == '\n') return true
    if (before in ".=({[,:" || before.isLetterOrDigit() || before == '_' || before == ' ') return true
    return false
}

private fun String.cleanInlineSuggestion(buffer: String, offset: Int): String {
    var out = trimEnd()
    if (out.isBlank()) return ""
    val suffix = buffer.substring(offset).take(200)
    while (out.isNotEmpty() && suffix.startsWith(out)) out = out.dropLast(1)
    val next = suffix.firstOrNull()
    if (next != null && out.firstOrNull() == next) return ""
    return out.take(2_000)
}
