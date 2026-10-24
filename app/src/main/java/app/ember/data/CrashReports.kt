package app.ember.data

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.DateFormat
import java.util.Date

/**
 * Remembers why Ember last closed unexpectedly, so the details can be shared from Settings.
 * Java crashes are caught with their full stack; native crashes (inside libraries such as
 * the torrent engine) come from Android's record of how the app exited.
 */
object CrashReports {

    private fun file(context: Context) = File(context.filesDir, "last-crash.txt")
    private fun javaFile(context: Context) = File(context.filesDir, "last-java-crash.txt")

    /** Call once at startup. */
    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            try {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                javaFile(app).writeText("Thread: ${thread.name}\n$sw")
            } catch (t: Throwable) {
            }
            previous?.uncaughtException(thread, e)
        }
        try {
            collect(app)
        } catch (t: Throwable) {
        }
    }

    /** Turns the most recent crash (if it's new) into a readable report. */
    private fun collect(context: Context) {
        if (Build.VERSION.SDK_INT < 30) return
        val am = context.getSystemService(ActivityManager::class.java) ?: return
        val last = am.getHistoricalProcessExitReasons(context.packageName, 0, 5).firstOrNull() ?: return
        val crash = last.reason == ApplicationExitInfo.REASON_CRASH || last.reason == ApplicationExitInfo.REASON_CRASH_NATIVE ||
            last.reason == ApplicationExitInfo.REASON_ANR
        if (!crash) return
        val prefs = context.getSharedPreferences("crash_reports", Context.MODE_PRIVATE)
        if (prefs.getLong("seen", 0) >= last.timestamp) return
        prefs.edit().putLong("seen", last.timestamp).apply()
        val kind = when (last.reason) {
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "native crash"
            ApplicationExitInfo.REASON_ANR -> "froze (not responding)"
            else -> "crash"
        }
        val version = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        } catch (e: Exception) {
            "?"
        }
        val java = javaFile(context).takeIf { it.exists() && last.reason == ApplicationExitInfo.REASON_CRASH }?.readText().orEmpty()
        val report = buildString {
            appendLine("Ember $version · Android ${Build.VERSION.RELEASE} · ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("When: ${DateFormat.getDateTimeInstance().format(Date(last.timestamp))}")
            appendLine("What: $kind (signal/status ${last.status})")
            last.description?.let { appendLine("Details: $it") }
            if (java.isNotBlank()) {
                appendLine()
                appendLine(java.take(6000))
            }
        }
        file(context).writeText(report)
        javaFile(context).delete()
    }

    fun latest(context: Context): String? = file(context).takeIf { it.exists() }?.readText()

    fun clear(context: Context) {
        file(context).delete()
    }
}
