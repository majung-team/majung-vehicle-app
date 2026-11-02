package app.majung.vehicle

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper

class VehicleApplication : Application() {
    lateinit var destination: DestinationReader
        private set
    lateinit var registration: VehicleRegistrationController
        private set
    lateinit var enrollment: VehicleEnrollmentController
        private set
    lateinit var account: VehicleAccountController
        private set
    lateinit var pickup: PickupController
        private set
    internal lateinit var requestFlow: RequestPickupFlow
        private set
    internal lateinit var people: VehiclePeopleController
        private set
    internal lateinit var voice: PickupVoiceGuide
        private set

    internal lateinit var sharingNotice: SharingNotice
        private set

    var sharingRunning: Boolean = false
        internal set
    var activityResumed: Boolean = false
        internal set
    var sharingMessage: String = "위치 권한을 허용하면 화면 밖에서도 공유할 수 있어요"
        internal set

    private val backgroundHandler = Handler(Looper.getMainLooper())
    private val backgroundPoller = object : Runnable {
        override fun run() {
            if (!activityResumed && registration.vehicleToken != null && account.state.optString("status") == "LINKED") {
                account.refresh(background = true)
            }
            backgroundHandler.postDelayed(this, 6000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        sharingNotice = SharingNotice(this)
        destination = DestinationReader(this)
        registration = VehicleRegistrationController(PickupApiClient(), EncryptedVehicleCredentialStore(this))
        pickup = PickupController(sessionStore = EncryptedPickupSessionStore(this), registration = registration)
        account = VehicleAccountController(PickupApiClient(), registration)
        people = VehiclePeopleController(PickupApiClient(),{ registration.vehicleToken },{
            account.state.takeIf { it.optString("status")=="LINKED" }?.optString("majungId")?.takeIf { it.isNotBlank() }
        })
        requestFlow = RequestPickupFlow(object : RequestPickupFlow.Host {
            override val authority: Any? get() = registration.vehicleToken?.let { token ->
                account.state.takeIf { it.optString("status") == "LINKED" }?.optString("majungId")?.let { token to it }
            }
            override val requestsFresh get() = account.requestsFresh
            override val pickupStatus get() = pickup.status
            override val hasPickup get() = pickup.pickupId != null
            override val pickupBusy get() = pickup.busy
            override val sharingRunning get() = this@VehicleApplication.sharingRunning
            override val routeFailure get() = destination.failure?.message
            override val pickupMessage get() = pickup.message
            override fun request(id: String): RequestedRide? = account.requests.firstOrNull { it.optString("requestId") == id }?.let { item ->
                item.optJSONObject("place")?.let { point ->
                    val place = PickupPlace(point.optString("label"), point.optDouble("latitude"), point.optDouble("longitude"))
                    if (!NavigationStop(place, 0, NavigationStopKind.WAYPOINT).usable) null
                    else RequestedRide(id, item.optString("nickname"), place, item.optString("status"))
                }
            }
            override fun canStart(id: String) = pickup.canStartRequested(id)
            override fun hasOtherActiveRequest(id: String) = account.requests.any {
                it.optString("status") == "ACTIVE" && it.optString("requestId") != id
            }
            override fun refreshRequests() { account.refresh() }
            override fun prepareRoute(retry: Boolean) {
                if (retry) destination.prepareRequestedRoute() else destination.prepareForPickup()
            }
            override fun stops() = destination.currentStops()
            override fun select(place: PickupPlace) = destination.selectVerifiedStop(place)
            override fun insert(place: PickupPlace) = destination.addPickupWaypoint(place)
            override fun clearEnded() { pickup.prepareNextPickup() }
            override fun start(ride: RequestedRide) { pickup.startRequested(ride.id, ride.place, destination.referenceFor(ride.place)) }
        }, android.os.SystemClock::elapsedRealtime)
        enrollment = VehicleEnrollmentController(PickupApiClient(), EncryptedVehicleCredentialStore(this, pending = true), EncryptedVehicleCredentialStore(this), registration)
        enrollment.changed = { pickup.changed?.invoke() }
        voice = PickupVoiceGuide(this, pickup)
        voice.changed = { pickup.changed?.invoke() }
        registration.changed = { account.registrationChanged(); if (registration.vehicleToken != null) account.refresh(); pickup.changed?.invoke() }
        registration.refresh()
        enrollment.restore()
        pickup.restoreSession()
        destination.pickupPlace = { pickup.place }
        destination.pickupArrived = pickup::arriveFromSdk
        destination.telemetryChanged = {
            if (sharingRunning) {
                destination.checkProximityArrival()
                pickup.syncVehicle(destination)
            }
            pickup.changed?.invoke()
        }
        account.onNewPendingRide = { ride -> notifyNewRide(ride) }
        pickup.onQuickMessageReceived = { message -> notifyQuickMessage(message) }
        backgroundHandler.post(backgroundPoller)
    }

    private fun notifyQuickMessage(text: String) {
        val passenger = pickup.participantName ?: "탑승자"
        val notifications = getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= 26) {
            notifications.createNotificationChannel(NotificationChannel(
                ALERT_CHANNEL, "픽업 알림", NotificationManager.IMPORTANCE_HIGH
            ))
        }
        val openApp = PendingIntent.getActivity(
            this, 3,
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val alert = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, ALERT_CHANNEL)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(this)
        }.setSmallIcon(R.drawable.ic_sharing)
            .setContentTitle("마중 · ${passenger}의 메시지")
            .setContentText("“$text”")
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .build()
        runCatching { notifications.notify(ALERT_QUICK_MSG_NOTIFICATION_ID, alert) }
        android.widget.Toast.makeText(
            this,
            "$passenger: “$text”",
            android.widget.Toast.LENGTH_LONG
        ).show()
        voice.speak("${passenger}님이 ‘$text’라고 전했어요.")
    }

    private fun notifyNewRide(ride: RequestedRide) {
        val notifications = getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= 26) {
            notifications.createNotificationChannel(NotificationChannel(
                ALERT_CHANNEL, "픽업 요청 알림", NotificationManager.IMPORTANCE_HIGH
            ))
        }
        val openApp = PendingIntent.getActivity(
            this, 2,
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra(EXTRA_OPEN_REQUEST_ID, ride.id)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val alert = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, ALERT_CHANNEL)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(this)
        }.setSmallIcon(R.drawable.ic_sharing)
            .setContentTitle("마중 · 새로운 픽업 요청")
            .setContentText("${ride.nickname}님이 ‘${ride.place.label}’에서 마중을 요청했어요. 눌러서 확인해주세요.")
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .build()
        runCatching { notifications.notify(ALERT_REQUEST_NOTIFICATION_ID, alert) }
        if (!activityResumed) {
            android.widget.Toast.makeText(
                this,
                "마중: ${ride.nickname}님이 ‘${ride.place.label}’에서 마중을 요청했어요.",
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
        voice.speak("${ride.nickname}님이 ${ride.place.label}에서 마중을 요청했어요.")
    }

    companion object {
        const val ALERT_CHANNEL = "pickup-alerts"
        const val ALERT_REQUEST_NOTIFICATION_ID = 34
        const val ALERT_QUICK_MSG_NOTIFICATION_ID = 35
        const val EXTRA_OPEN_REQUEST_ID = "open_request_id"
    }
}
