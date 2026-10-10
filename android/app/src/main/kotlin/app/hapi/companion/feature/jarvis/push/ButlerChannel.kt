package app.hapi.companion.feature.jarvis.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import app.hapi.companion.R

/**
 * One notification channel for everything the butler says (step 15). The
 * upstream channels were created with vibration off and `ready` at DEFAULT
 * importance — on the A36 a reply posted without a buzz (10-09 「진동 오는지」).
 * Android freezes a channel's sound/vibration once created, so this is a new
 * channel rather than a retune of the old ones: HIGH (heads-up) + vibration.
 */
object ButlerChannel {
    const val ID = "jarvis_butler"

    fun ensure(context: Context, manager: NotificationManager) {
        manager.createNotificationChannel(
            NotificationChannel(ID, context.getString(R.string.jarvis_channel_butler), NotificationManager.IMPORTANCE_HIGH).apply {
                description = context.getString(R.string.jarvis_channel_butler_desc)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 250, 150, 250)
            },
        )
    }
}
