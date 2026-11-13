package app.majung.vehicle.service

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Dismisses heads-up alerts in place so tapping them never interrupts navigation. */
class NotificationDismissReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_DISMISS) return
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0)
        if (notificationId == 0) return
        context.getSystemService(NotificationManager::class.java)?.cancel(notificationId)
    }

    companion object {
        const val ACTION_DISMISS = "app.majung.vehicle.action.DISMISS_ALERT"
        const val EXTRA_NOTIFICATION_ID = "notificationId"

        fun pendingIntent(context: Context, notificationId: Int): PendingIntent {
            val intent = Intent(context, NotificationDismissReceiver::class.java).apply {
                action = ACTION_DISMISS
                putExtra(EXTRA_NOTIFICATION_ID, notificationId)
            }
            return PendingIntent.getBroadcast(
                context,
                notificationId,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
    }
}
