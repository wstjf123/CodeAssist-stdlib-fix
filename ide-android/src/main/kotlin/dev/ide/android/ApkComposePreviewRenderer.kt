package dev.ide.android

import androidx.compose.runtime.Composable
import androidx.compose.runtime.currentComposer
import dev.ide.interp.compose.ComposableAbi
import java.lang.reflect.InvocationTargetException

/**
 * Renders a Compose preview from already-compiled APK bytecode. The target is the Kotlin file facade
 * (`com.example.MainActivityKt`) and the Compose compiler generated method shape is invoked through the same
 * ABI bridge the interpreter uses for library composables.
 */
class ApkComposePreviewRenderer(
    private val loader: ClassLoader,
) {
    @Composable
    fun Render(facadeFqn: String, functionName: String): Throwable? {
        val composer = currentComposer
        return try {
            ComposableAbi.startGroup(composer, "$facadeFqn#$functionName".hashCode())
            try {
                ComposableAbi.call(
                    ownerFqn = facadeFqn,
                    method = functionName,
                    originalArgs = emptyList(),
                    composer = composer,
                    declaredParamCount = 0,
                    loader = loader,
                )
            } finally {
                ComposableAbi.endGroup(composer)
            }
            null
        } catch (t: Throwable) {
            unwrap(t)
        }
    }

    private fun unwrap(t: Throwable): Throwable =
        if (t is InvocationTargetException && t.targetException != null) t.targetException else t
}
