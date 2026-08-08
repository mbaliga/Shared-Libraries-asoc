package dev.aarso.diagnostics.trigger

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.util.Log
import dev.aarso.diagnostics.Diagnostics
import dev.aarso.diagnostics.core.Trigger

/**
 * The trigger that closes the loop from Termux: start a capture, run a scripted interaction, stop,
 * and pull the file into the repo — without touching the screen, which matters when the interaction
 * itself is what you are measuring. It is also the only workable trigger for an IME, a launcher or
 * a wallpaper, where a floating overlay would cover or steal from the surface under test.
 *
 *   adb shell am broadcast -a dev.aarso.diagnostics.START --es label "pond-high"
 *   adb shell am broadcast -a dev.aarso.diagnostics.MARK  --es name "scene-switch"
 *   adb shell am broadcast -a dev.aarso.diagnostics.SNAP
 *   adb shell am broadcast -a dev.aarso.diagnostics.STOP
 *   adb shell am broadcast -a dev.aarso.diagnostics.PULL
 *   adb shell am broadcast -a dev.aarso.diagnostics.RECOVER
 *
 * SECURITY: ships only in diagnostics-android, which is debugImplementation-scoped, so it cannot
 * exist in a release build. It ALSO re-checks FLAG_DEBUGGABLE at runtime. A receiver any installed
 * app could trigger would be a real vulnerability rather than a theoretical one, so both guards
 * stay even though either alone would nominally do.
 */
class DiagnosticsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if ((context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) == 0) return

        when (intent.action) {
            ACTION_START -> {
                Diagnostics.startSession(intent.getStringExtra("label"), Trigger.ADB_BROADCAST)
                Log.i(TAG, "capture started, profile=${Diagnostics.profile.id}")
            }
            ACTION_MARK -> {
                val name = intent.getStringExtra("name") ?: "adb-mark"
                Diagnostics.mark(name); Log.i(TAG, "mark: $name")
            }
            ACTION_SNAP -> Log.i(TAG, "snapshot: " +
                (Diagnostics.snapshot(intent.getStringExtra("label"))?.absolutePath ?: "no active session"))
            ACTION_STOP -> {
                val f = Diagnostics.endSession()?.let { Diagnostics.export(it) }
                Log.i(TAG, "REPORT_PATH=${f?.absolutePath ?: "none"}")
            }
            ACTION_PULL -> Diagnostics.listReports().take(5)
                .forEach { Log.i(TAG, "REPORT_PATH=${it.absolutePath}") }
            ACTION_RECOVER -> {
                val recovered = Diagnostics.recoverAbandonedSessions()
                if (recovered.isEmpty()) Log.i(TAG, "no abandoned sessions")
                else recovered.forEach { Log.i(TAG, "RECOVERED_PATH=${it.absolutePath}") }
            }
        }
    }

    companion object {
        private const val TAG = "Diag"
        const val ACTION_START = "dev.aarso.diagnostics.START"
        const val ACTION_MARK = "dev.aarso.diagnostics.MARK"
        const val ACTION_SNAP = "dev.aarso.diagnostics.SNAP"
        const val ACTION_STOP = "dev.aarso.diagnostics.STOP"
        const val ACTION_PULL = "dev.aarso.diagnostics.PULL"
        const val ACTION_RECOVER = "dev.aarso.diagnostics.RECOVER"

        /** Registered in code, not the manifest, so an app can decline it via Config. */
        fun register(context: Context) {
            val filter = IntentFilter().apply {
                addAction(ACTION_START); addAction(ACTION_MARK); addAction(ACTION_SNAP)
                addAction(ACTION_STOP); addAction(ACTION_PULL); addAction(ACTION_RECOVER)
            }
            val receiver = DiagnosticsReceiver()
            if (android.os.Build.VERSION.SDK_INT >= 33)
                context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            else @Suppress("UnspecifiedRegisterReceiverFlag") context.registerReceiver(receiver, filter)
        }
    }
}
