package dev.ide.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.ide.ui.backend.UiComposePreview
import dev.ide.ui.editor.preview.PreviewIssue

/**
 * Renders a Compose `@Preview` from the open editor file as live UI. Platform-provided: `:ide-android` wires
 * the real on-device renderer (the interpreter composing into the IDE's own composition); the desktop
 * launcher provides the JVM counterpart. The UI only sees this interface — the interpreter/Compose-runtime
 * types stay in the platform layer.
 */
interface ComposePreviewHost {
    /**
     * Compose the [preview] variant from [path]'s live buffer [text] into the given [modifier] slot. The
     * variant carries the `@Preview` arguments to honor (size already applied to the surface by the pane; the
     * host applies the composition-level ones: `uiMode`, `locale`, `fontScale`, `@PreviewParameter`). [dark]
     * is the surface's Night toggle; the host renders night when either [dark] or the variant's `uiMode` asks
     * for it. Interpret/render problems are reported through [onProblems] (empty when clean) so the pane can
     * show them in the shared problem chip rather than over the device frame. [refreshKey] asks the host to
     * re-resolve/re-render the same preview without the pane having to dispose the platform preview view, and
     * [onBusy] reports whether the host is currently lowering/interpreting vs. settled so the pane can show a
     * loading badge.
     */
    @Composable
    fun Preview(path: String, preview: UiComposePreview, text: String, dark: Boolean, refreshKey: Int, onProblems: (List<PreviewIssue>) -> Unit, onBusy: (Boolean) -> Unit, modifier: Modifier)

    suspend fun capturePreview(path: String, preview: UiComposePreview, text: String, dark: Boolean): ComposePreviewCaptureResult =
        ComposePreviewCaptureResult(false, "Compose preview capture is not available")
}

data class ComposePreviewCaptureResult(
    val ok: Boolean,
    val message: String,
    val imageDataUrl: String? = null,
    val widthPx: Int = 0,
    val heightPx: Int = 0,
)
