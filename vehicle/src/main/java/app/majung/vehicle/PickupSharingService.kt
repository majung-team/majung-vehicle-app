package app.majung.vehicle

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper

/** Owns SDK polling and publishing for an approved pickup, independently of the screen. */
class PickupSharingService : Service() {
    private val app: VehicleApplication get() = application as VehicleApplication
    private val handler = Handler(Looper.getMainLooper())
    private var ticks = 0
    private var started = false
    private var lastNotifiedProposalId: String? = null
    private var lastNotifiedReadiness: String = ""
    private val stateChanged: () -> Unit = {
        if (!shouldShare()) stopSelf() else notifyPassengerEvents()
    }
    private val tick = object : Runnable {
        override fun run() {
            if (!shouldShare() || !hasLocationPermission(this@PickupSharingService)) {
                stopSelf()
                return
            }
            app.destination.restorePickupReference(app.pickup.place, app.pickup.navigationReference)
            if (ticks % 3 == 0) app.pickup.refresh()
            if (ticks % 6 == 0) app.account.refresh(background = true)
            if (ticks % 5 == 0) {
                app.destination.connect()
                app.destination.pollTelemetry()
            }
            app.pickup.syncVehicle(app.destination)
            ticks++
            handler.postDelayed(this, 1000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!shouldShare() || !hasLocationPermission(this)) {
            app.sharingMessage = "위치 권한과 픽업 상태를 확인한 뒤 공유를 시작해주세요"
            stopSelf()
            return START_NOT_STICKY
        }
        if (started) return START_NOT_STICKY
        try {
            val notifications = getSystemService(NotificationManager::class.java)
            notifications.createNotificationChannel(NotificationChannel(
                CHANNEL, "픽업 위치 공유", NotificationManager.IMPORTANCE_LOW
            ))
            notifications.createNotificationChannel(NotificationChannel(
                ALERT_CHANNEL, "픽업 요청 알림", NotificationManager.IMPORTANCE_HIGH
            ))
            val openApp = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val notification = Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_sharing)
                .setContentTitle("마중 · 픽업 공유 서비스")
                .setContentText("공유 상태는 앱에서 확인해주세요. 탑승 확인·취소 시 서비스가 종료돼요.")
                .setContentIntent(openApp)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setOnlyAlertOnce(true)
                .build()
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            started = true
            lastNotifiedProposalId = app.pickup.proposalId
            lastNotifiedReadiness = app.pickup.readiness
            app.sharingRunning = true
            app.sharingMessage = "화면 밖 공유 서비스 실행 중 · 실제 공유 상태는 전송 결과에서 확인"
            app.pickup.addObserver(stateChanged)
            handler.post(tick)
            app.pickup.changed?.invoke()
        } catch (_: SecurityException) {
            app.sharingMessage = "위치 권한을 확인한 뒤 화면에서 공유를 다시 시작해주세요"
            stopSelf()
        } catch (_: IllegalStateException) {
            app.sharingMessage = "공유를 시작하지 못했어요. 화면에서 다시 시작해주세요"
            stopSelf()
        }
        // Force-stop and process death require reopening the app and checking the saved pickup.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        app.pickup.removeObserver(stateChanged)
        runCatching { getSystemService(NotificationManager::class.java)?.cancel(ALERT_NOTIFICATION_ID) }
        app.sharingRunning = false
        app.sharingMessage = "위치·ETA 전송이 멈췄어요. 진행 중인 픽업은 화면에서 다시 시작해주세요"
        app.destination.disconnect()
        stopForeground(STOP_FOREGROUND_REMOVE)
        app.pickup.changed?.invoke()
        super.onDestroy()
    }

    private fun notifyPassengerEvents() {
        val notifications = getSystemService(NotificationManager::class.java) ?: return
        val proposal = app.pickup.proposalId
        val proposedPlace = app.pickup.placeProposal
        if (app.pickup.status != "ACTIVE" || proposal == null || proposedPlace == null) {
            lastNotifiedProposalId = null
            notifications.cancel(ALERT_NOTIFICATION_ID)
        } else if (proposal != lastNotifiedProposalId) {
            lastNotifiedProposalId = proposal
            val openApp = PendingIntent.getActivity(
                this, 1,
                Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val alert = Notification.Builder(this, ALERT_CHANNEL)
                .setSmallIcon(R.drawable.ic_sharing)
                .setContentTitle("마중 · 장소 변경 요청")
                .setContentText("탑승자가 ‘${proposedPlace.label}’(으)로 장소 변경을 요청했어요. 눌러서 확인해주세요.")
                .setContentIntent(openApp)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_MESSAGE)
                .build()
            runCatching { notifications.notify(ALERT_NOTIFICATION_ID, alert) }
            if (!app.activityResumed) {
                android.widget.Toast.makeText(
                    this,
                    "마중: 탑승자가 ‘${proposedPlace.label}’(으)로 장소 변경을 요청했어요.",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
        }
        val readiness = app.pickup.readiness
        if (app.pickup.status == "ACTIVE" && readiness == "READY" && lastNotifiedReadiness != "READY") {
            lastNotifiedReadiness = "READY"
            if (!app.activityResumed) {
                android.widget.Toast.makeText(
                    this,
                    "마중: 탑승자가 탑승 준비를 마쳤어요.",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
        } else if (readiness != "READY") {
            lastNotifiedReadiness = readiness
        }
    }

    private fun shouldShare(): Boolean = app.sharingNotice.accepted && !app.pickup.needsSharingConfirmation && app.pickup.status in setOf("ACTIVE", "ARRIVED")

    companion object {
        private const val CHANNEL = "pickup-sharing"
        private const val ALERT_CHANNEL = "pickup-alerts"
        private const val NOTIFICATION_ID = 32
        private const val ALERT_NOTIFICATION_ID = 33

        fun hasLocationPermission(context: Context): Boolean =
            context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }
}
