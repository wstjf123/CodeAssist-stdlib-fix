package dev.ide.android

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Background watchdog for stalls on Android's main thread. The watchdog thread does not depend on
 * the UI looper to report; if the heartbeat stops, it dumps the main-thread stack and non-daemon
 * threads to a file under CodeAssist's app storage.
 */
object UiThreadWatchdog {
    private const val HEARTBEAT_MS = 250L
    private const val STALL_MS = 2_500L
    private const val REPORT_THROTTLE_MS = 5_000L
    private const val MAX_LOG_BYTES = 2L * 1024L * 1024L

    private val started = AtomicBoolean(false)
    private val lastBeat = AtomicLong(System.currentTimeMillis())
    private val lastReport = AtomicLong(0L)
    private val lock = Any()
    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    @Volatile
    private var logFile: File? = null

    fun start(context: Context) {
        if (!started.compareAndSet(false, true)) return
        val appContext = context.applicationContext
        logFile = File(AndroidIde.appHomeDir(appContext), "logs/ui-watchdog.log").also { file ->
            file.parentFile?.mkdirs()
            rotateIfNeeded(file)
            append(file, "watchdog started pid=${android.os.Process.myPid()} thread=${Thread.currentThread().name}")
        }

        Handler(Looper.getMainLooper()).post(object : Runnable {
            override fun run() {
                lastBeat.set(System.currentTimeMillis())
                Handler(Looper.getMainLooper()).postDelayed(this, HEARTBEAT_MS)
            }
        })

        Thread(::watchLoop, "ui-watchdog").apply {
            isDaemon = true
            start()
        }
    }

    fun mark(message: String) {
        val file = logFile ?: return
        append(file, "mark $message")
    }

    private fun watchLoop() {
        while (true) {
            Thread.sleep(500L)
            val now = System.currentTimeMillis()
            val stalledFor = now - lastBeat.get()
            if (stalledFor < STALL_MS) continue
            val previous = lastReport.get()
            if (now - previous < REPORT_THROTTLE_MS) continue
            if (!lastReport.compareAndSet(previous, now)) continue
            dumpStall(stalledFor)
        }
    }

    private fun dumpStall(stalledFor: Long) {
        val file = logFile ?: return
        val mainThread = Looper.getMainLooper().thread
        val stacks = Thread.getAllStackTraces()
        val text = buildString {
            appendLine("main thread stalled for ${stalledFor}ms")
            appendLine("main=${mainThread.name} state=${mainThread.state} id=${mainThread.id}")
            appendStack(mainThread.stackTrace)
            appendLine()
            appendLine("non-daemon threads:")
            stacks.keys
                .filter { !it.isDaemon || it == mainThread }
                .sortedWith(compareBy<Thread> { if (it == mainThread) 0 else 1 }.thenBy { it.name })
                .forEach { thread ->
                    appendLine("\"${thread.name}\" id=${thread.id} state=${thread.state} daemon=${thread.isDaemon}")
                    appendStack(stacks[thread].orEmpty())
                    appendLine()
                }
        }
        append(file, text)
    }

    private fun StringBuilder.appendStack(stack: Array<out StackTraceElement>) {
        if (stack.isEmpty()) {
            appendLine("  <no Java stack>")
            return
        }
        for (frame in stack) appendLine("  at $frame")
    }

    private fun append(file: File, message: String) {
        synchronized(lock) {
            rotateIfNeeded(file)
            file.parentFile?.mkdirs()
            FileOutputStream(file, true).bufferedWriter().use { writer ->
                writer.append('[')
                writer.append(timeFormat.format(Date()))
                writer.append("] ")
                writer.append(message)
                if (!message.endsWith('\n')) writer.newLine()
            }
        }
    }

    private fun rotateIfNeeded(file: File) {
        if (!file.exists() || file.length() <= MAX_LOG_BYTES) return
        val old = File(file.parentFile, "${file.name}.1")
        if (old.exists()) old.delete()
        file.renameTo(old)
    }
}
