package dev.ide.ui.platform

/**
 * Optional host-provided trace hook for UI actions that need to be correlated with platform logs.
 * Android wires this to the UI-thread watchdog file; desktop leaves it unset.
 */
object UiTrace {
    @Volatile
    var sink: ((String) -> Unit)? = null

    fun mark(message: String) {
        sink?.invoke(message)
    }
}
