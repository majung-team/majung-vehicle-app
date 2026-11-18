package app.majung.vehicle.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import app.majung.vehicle.MainActivity
import app.majung.vehicle.R
import app.majung.vehicle.VehicleApplication
import app.majung.vehicle.VehicleApplication.Companion.ALERT_CHANNEL
import app.majung.vehicle.VehicleApplication.Companion.ALERT_PROPOSAL_NOTIFICATION_ID
import app.majung.vehicle.VehicleApplication.Companion.ALERT_QUICK_MSG_NOTIFICATION_ID
import app.majung.vehicle.VehicleApplication.Companion.ALERT_REQUEST_NOTIFICATION_ID
import app.majung.vehicle.VehicleApplication.Companion.EXTRA_OPEN_REQUEST_ID
import app.majung.vehicle.domain.RequestedRide
import app.majung.vehicle.service.NotificationDismissReceiver
import app.majung.vehicle.service.PickupSharingService
import app.majung.vehicle.util.AlertTapBehavior
import app.majung.vehicle.util.PickupAlertKind
import app.majung.vehicle.util.PickupNotificationPolicy

internal class PickupAlerts(private val app: VehicleApplication) {
    private val pickup
        get() = app.pickup

    private val voice
        get() = app.voice

    private val activityResumed
        get() = app.activityResumed

    private var lastNotifiedProposalId: String? = null
    private var lastNotifiedReadiness: String = ""

    fun ensureAlertChannel(notifications: NotificationManager) {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel =
                NotificationChannel(ALERT_CHANNEL, "픽업 중요 알림", NotificationManager.IMPORTANCE_HIGH)
                    .apply {
                        description = "탑승자 음성 메시지, 픽업 요청 및 장소 변경 긴급 알림"
                        enableVibration(true)
                        vibrationPattern = longArrayOf(0, 300, 150, 300)
                        enableLights(true)
                        lightColor = android.graphics.Color.GREEN
                        lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                        setBypassDnd(true)
                    }
            notifications.createNotificationChannel(channel)
        }
    }

    @Suppress("DEPRECATION")
    internal fun buildAlertNotification(
        title: String,
        text: String,
        contentIntent: PendingIntent,
        action: Notification.Action? = null,
        fullScreenIntent: PendingIntent? = null,
    ): Notification {
        val builder =
            if (Build.VERSION.SDK_INT >= 26) {
                    Notification.Builder(app, ALERT_CHANNEL)
                } else {
                    @Suppress("DEPRECATION") Notification.Builder(app)
                }
                .setSmallIcon(R.drawable.ic_sharing)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(contentIntent)
                .setAutoCancel(true)
                .setOnlyAlertOnce(false)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setPriority(Notification.PRIORITY_MAX)
                .setDefaults(Notification.DEFAULT_ALL)
                .setVibrate(longArrayOf(0, 300, 150, 300))
                .setCategory(Notification.CATEGORY_MESSAGE)
        if (fullScreenIntent != null) {
            builder.setFullScreenIntent(fullScreenIntent, true)
        }
        if (action != null) {
            builder.addAction(action)
        }
        return builder.build()
    }

    private fun openMainActivityPendingIntent(
        requestCode: Int,
        requestId: String? = null,
    ): PendingIntent =
        PendingIntent.getActivity(
            app,
            requestCode,
            Intent(app, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                if (requestId != null) {
                    putExtra(EXTRA_OPEN_REQUEST_ID, requestId)
                }
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    fun notifyQuickMessage(text: String) {
        val passenger = pickup.participantName ?: "탑승자"
        val notifications = app.getSystemService(NotificationManager::class.java) ?: return
        ensureAlertChannel(notifications)
        val behavior = PickupNotificationPolicy.behaviorFor(PickupAlertKind.QUICK_MESSAGE)
        val tapIntent =
            when (behavior.tapBehavior) {
                AlertTapBehavior.DISMISS_ONLY ->
                    NotificationDismissReceiver.pendingIntent(app, ALERT_QUICK_MSG_NOTIFICATION_ID)
                AlertTapBehavior.OPEN_APP_SCREEN -> openMainActivityPendingIntent(3)
            }
        val confirmAction =
            Notification.Action.Builder(
                    android.graphics.drawable.Icon.createWithResource(app, R.drawable.ic_sharing),
                    "확인",
                    tapIntent,
                )
                .build()
        val alert =
            buildAlertNotification(
                title = "마중 · ${passenger}의 메시지",
                text = "“$text”",
                contentIntent = tapIntent,
                action = confirmAction,
                fullScreenIntent =
                    if (behavior.useFullScreenIntent) openMainActivityPendingIntent(3) else null,
            )
        runCatching {
            notifications.cancel(ALERT_QUICK_MSG_NOTIFICATION_ID)
            notifications.notify(ALERT_QUICK_MSG_NOTIFICATION_ID, alert)
        }
        if (behavior.showOverlayToast && !activityResumed) {
            android.widget.Toast.makeText(
                    app,
                    "$passenger: “$text”",
                    android.widget.Toast.LENGTH_LONG,
                )
                .show()
        }
        voice.speak("${passenger}님이 ‘$text’라고 전했어요.")
    }

    fun notifyNewRide(ride: RequestedRide) {
        val notifications = app.getSystemService(NotificationManager::class.java) ?: return
        ensureAlertChannel(notifications)
        val behavior = PickupNotificationPolicy.behaviorFor(PickupAlertKind.NEW_RIDE_REQUEST)
        val openApp = openMainActivityPendingIntent(2, requestId = ride.id)
        val tapIntent =
            when (behavior.tapBehavior) {
                AlertTapBehavior.DISMISS_ONLY ->
                    NotificationDismissReceiver.pendingIntent(app, ALERT_REQUEST_NOTIFICATION_ID)
                AlertTapBehavior.OPEN_APP_SCREEN -> openApp
            }
        val openAction =
            Notification.Action.Builder(
                    android.graphics.drawable.Icon.createWithResource(app, R.drawable.ic_sharing),
                    "요청 확인",
                    tapIntent,
                )
                .build()
        val alert =
            buildAlertNotification(
                title = "마중 · 새로운 픽업 요청",
                text = "${ride.nickname}님이 ‘${ride.place.label}’에서 마중을 요청했어요. 눌러서 확인해주세요.",
                contentIntent = tapIntent,
                action = openAction,
                fullScreenIntent = if (behavior.useFullScreenIntent) openApp else null,
            )
        runCatching {
            notifications.cancel(ALERT_REQUEST_NOTIFICATION_ID)
            notifications.notify(ALERT_REQUEST_NOTIFICATION_ID, alert)
        }
        if (behavior.showOverlayToast && !activityResumed) {
            android.widget.Toast.makeText(
                    app,
                    "마중: ${ride.nickname}님이 ‘${ride.place.label}’에서 마중을 요청했어요.",
                    android.widget.Toast.LENGTH_LONG,
                )
                .show()
        }
        voice.speak("${ride.nickname}님이 ${ride.place.label}에서 마중을 요청했어요.")
    }

    @Suppress("DEPRECATION")
    internal fun notifyPassengerEvents() {
        val notifications = app.getSystemService(NotificationManager::class.java) ?: return
        ensureAlertChannel(notifications)
        val proposal = pickup.proposalId
        val proposedPlace = pickup.placeProposal
        if (pickup.status != "ACTIVE" || proposal == null || proposedPlace == null) {
            lastNotifiedProposalId = null
            runCatching { notifications.cancel(ALERT_PROPOSAL_NOTIFICATION_ID) }
        } else if (proposal != lastNotifiedProposalId) {
            lastNotifiedProposalId = proposal
            val behavior = PickupNotificationPolicy.behaviorFor(PickupAlertKind.PLACE_PROPOSAL)
            val openApp = openMainActivityPendingIntent(1)
            val tapIntent =
                when (behavior.tapBehavior) {
                    AlertTapBehavior.DISMISS_ONLY ->
                        NotificationDismissReceiver.pendingIntent(
                            app,
                            ALERT_PROPOSAL_NOTIFICATION_ID,
                        )
                    AlertTapBehavior.OPEN_APP_SCREEN -> openApp
                }
            val acceptIntent =
                Intent(app, PickupSharingService::class.java).apply {
                    action = PickupSharingService.ACTION_ACCEPT_PROPOSAL
                    putExtra(PickupSharingService.EXTRA_PROPOSAL_ID, proposal)
                }
            val acceptPending =
                PendingIntent.getService(
                    app,
                    2,
                    acceptIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            val acceptAction =
                Notification.Action.Builder(
                        android.graphics.drawable.Icon.createWithResource(
                            app,
                            R.drawable.ic_sharing,
                        ),
                        "수락 및 내비 변경",
                        acceptPending,
                    )
                    .build()

            val alert =
                buildAlertNotification(
                    title = "마중 · 장소 변경 요청",
                    text = "탑승자가 ‘${proposedPlace.label}’(으)로 장소 변경을 요청했어요.",
                    contentIntent = tapIntent,
                    action = acceptAction,
                    fullScreenIntent = if (behavior.useFullScreenIntent) openApp else null,
                )
            runCatching {
                notifications.cancel(ALERT_PROPOSAL_NOTIFICATION_ID)
                notifications.notify(ALERT_PROPOSAL_NOTIFICATION_ID, alert)
            }
            if (behavior.showOverlayToast && !activityResumed) {
                android.widget.Toast.makeText(
                        app,
                        "마중: 탑승자가 ‘${proposedPlace.label}’(으)로 장소 변경을 요청했어요.",
                        android.widget.Toast.LENGTH_LONG,
                    )
                    .show()
            }
        }
        val readiness = pickup.readiness
        if (pickup.status == "ACTIVE" && readiness == "READY" && lastNotifiedReadiness != "READY") {
            lastNotifiedReadiness = "READY"
            if (!activityResumed) {
                android.widget.Toast.makeText(
                        app,
                        "마중: 탑승자가 탑승 준비를 마쳤어요.",
                        android.widget.Toast.LENGTH_LONG,
                    )
                    .show()
            }
        } else if (readiness != "READY") {
            lastNotifiedReadiness = readiness
        }
    }
}
