package app.orchard.tv.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** Where the install session of an update from Control Center reports back (Updates.installBySession). */
class UpdateResult : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.getIntExtra(Updates.EXTRA_SELF, 0) == 0) {
            Updates.onSessionResult(context, intent)
            return
        }
        val pending = goAsync()
        var lease: BroadcastLease? = null
        try {
            val current = deadlines.lease { pending.finish() }
            lease = current
            try {
                current.start()
            } catch (error: Exception) {
                Log.w("AerialUpdates", "self compile: broadcast deadline unavailable", error)
            }
            Updates.onSessionResult(context, intent) { current.complete() }
        } catch (error: Exception) {
            if (lease == null) pending.finish() else lease.complete()
            Log.w("AerialUpdates", "self compile: cannot receive installer result", error)
        }
    }

    companion object {
        private val deadlines = BroadcastDeadlines()
    }
}
