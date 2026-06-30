package com.bitchat.android.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Debug-only command surface, driven from a host machine over adb:
 *
 *   adb shell am broadcast -n com.bitchat.droid.debug/com.bitchat.android.debug.DebugCommandReceiver \
 *       --es cmd "candidates"
 *
 * Output is written to logcat under tag [TAG]. The receiver is registered ONLY in the debug source set
 * (app/src/debug/AndroidManifest.xml), so it does not exist in release builds. Commands that need live
 * mesh / coordinator / wallet context are delegated to a [Host] that the active ChatViewModel registers
 * while it is alive (see ChatViewModel.init / onCleared). Money-path commands stay behind the app's
 * existing network guards and operate on the selected network only.
 */
object DebugConsole {
    const val TAG = "DbgConsole"

    interface Host {
        /** Run [cmd] with [args]; return human-readable output. Async work may log under [TAG] itself. */
        fun handle(cmd: String, args: List<String>): String
    }

    @Volatile private var host: Host? = null

    fun setHost(h: Host?) { host = h }

    /** Compare-and-clear so a newer ViewModel that already re-registered is not clobbered. */
    fun clearHostIfCurrent(h: Host?) { if (host === h) host = null }

    fun dispatch(raw: String?) {
        val line = raw?.trim().orEmpty()
        if (line.isEmpty()) { Log.d(TAG, "empty cmd (try: help)"); return }
        val parts = line.split(Regex("\\s+"))
        val cmd = parts[0].lowercase()
        val args = parts.drop(1)
        val h = host
        if (h == null) {
            Log.d(TAG, "no host registered — open the app's chat screen so a ViewModel is alive")
            return
        }
        val out = try {
            h.handle(cmd, args)
        } catch (e: Exception) {
            "ERR ${e.javaClass.simpleName}: ${e.message}"
        }
        // Log each line separately so multi-line dumps stay readable in logcat.
        out.lineSequence().forEach { if (it.isNotEmpty()) Log.d(TAG, it) }
    }
}

/** Component-targeted via `am broadcast -n …`; exported only in the debug manifest. */
class DebugCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        DebugConsole.dispatch(intent.getStringExtra("cmd"))
    }
}
