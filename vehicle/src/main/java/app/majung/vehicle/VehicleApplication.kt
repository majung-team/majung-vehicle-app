package app.majung.vehicle


import app.majung.vehicle.*
import app.majung.vehicle.ui.*
import app.majung.vehicle.ui.sections.*
import app.majung.vehicle.navi.*
import app.majung.vehicle.domain.*
import app.majung.vehicle.data.api.*
import app.majung.vehicle.data.storage.*
import app.majung.vehicle.util.*
import app.majung.vehicle.service.*

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
    var serviceRunning: Boolean = false
        internal set
    var activityResumed: Boolean = false
        internal set
    var sharingMessage: String = "위치 권한을 허용하면 화면 밖에서도 공유할 수 있어요"
        internal set

    private var backgroundTicks = 0
    private var lastNotifiedProposalId: String? = null
    private var lastNotifiedReadiness: String = ""
    private val backgroundHandler = Handler(Looper.getMainLooper())
    private val backgroundPoller = object : Runnable {
        override fun run() {
            if (!activityResumed) {
                if (backgroundTicks % 2 == 0 && registration.vehicleToken != null && account.state.optString("status") == "LINKED") {
                    account.refresh(background = true)
                }
                if (!serviceRunning && sharingNotice.accepted && !pickup.needsSharingConfirmation &&
                    pickup.status in setOf("INVITING", "WAITING_APPROVAL", "ACTIVE", "ARRIVED")) {
                    pickup.refresh()
                    if (PickupSharingService.hasLocationPermission(this@VehicleApplication)) {
                        destination.restorePickupReference(pickup.place, pickup.navigationReference)
                        destination.connect()
                        destination.pollTelemetry()
                        if (pickup.status in setOf("ACTIVE", "ARRIVED")) {
                            pickup.checkAutoConfirmProposal(destination)
                            pickup.syncVehicle(destination)
                        }
                    }
                }
            }
            backgroundTicks++
            backgroundHandler.postDelayed(this, 3000)
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
            override val confirmedNoGuidance get() = destination.confirmedNoGuidance
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
            override fun addWaypoint(place: PickupPlace) = destination.addPickupWaypoint(place)
            override fun setDestination(place: PickupPlace) = destination.navigateToPickup(place)
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
            if (sharingRunning || (!activityResumed && sharingNotice.accepted && !pickup.needsSharingConfirmation &&
                pickup.status in setOf("ACTIVE", "ARRIVED") && PickupSharingService.hasLocationPermission(this))) {
                destination.checkProximityArrival()
                pickup.syncVehicle(destination)
            }
            pickup.changed?.invoke()
        }
        account.onNewPendingRide = { ride -> notifyNewRide(ride) }
        pickup.onQuickMessageReceived = { message -> notifyQuickMessage(message) }
        pickup.addObserver {
            if (sharingNotice.accepted && !pickup.needsSharingConfirmation) {
                pickup.checkAutoConfirmProposal(destination)
                notifyPassengerEvents()
                if (!activityResumed && !serviceRunning && !sharingRunning &&
                    pickup.status in setOf("ACTIVE", "ARRIVED") &&
                    PickupSharingService.hasLocationPermission(this)) {
                    destination.restorePickupReference(pickup.place, pickup.navigationReference)
                    destination.connect()
                    destination.pollTelemetry()
                    pickup.syncVehicle(destination)
                }
            }
        }
        backgroundHandler.post(backgroundPoller)
    }

    fun ensureAlertChannel(notifications: NotificationManager) {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                ALERT_CHANNEL, "픽업 중요 알림", NotificationManager.IMPORTANCE_HIGH
            ).apply {
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
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, ALERT_CHANNEL)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(this)
        }.setSmallIcon(R.drawable.ic_sharing)
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

    private fun openMainActivityPendingIntent(requestCode: Int, requestId: String? = null): PendingIntent =
        PendingIntent.getActivity(
            this,
            requestCode,
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                if (requestId != null) {
                    putExtra(EXTRA_OPEN_REQUEST_ID, requestId)
                }
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun notifyQuickMessage(text: String) {
        val passenger = pickup.participantName ?: "탑승자"
        val notifications = getSystemService(NotificationManager::class.java) ?: return
        ensureAlertChannel(notifications)
        val behavior = PickupNotificationPolicy.behaviorFor(PickupAlertKind.QUICK_MESSAGE)
        val tapIntent = when (behavior.tapBehavior) {
            AlertTapBehavior.DISMISS_ONLY ->
                NotificationDismissReceiver.pendingIntent(this, ALERT_QUICK_MSG_NOTIFICATION_ID)
            AlertTapBehavior.OPEN_APP_SCREEN -> openMainActivityPendingIntent(3)
        }
        val confirmAction = Notification.Action.Builder(
            android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_sharing),
            "확인",
            tapIntent,
        ).build()
        val alert = buildAlertNotification(
            title = "마중 · ${passenger}의 메시지",
            text = "“$text”",
            contentIntent = tapIntent,
            action = confirmAction,
            fullScreenIntent = if (behavior.useFullScreenIntent) openMainActivityPendingIntent(3) else null,
        )
        runCatching {
            notifications.cancel(ALERT_QUICK_MSG_NOTIFICATION_ID)
            notifications.notify(ALERT_QUICK_MSG_NOTIFICATION_ID, alert)
        }
        if (behavior.showOverlayToast && !activityResumed) {
            android.widget.Toast.makeText(
                this,
                "$passenger: “$text”",
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
        voice.speak("${passenger}님이 ‘$text’라고 전했어요.")
    }

    private fun notifyNewRide(ride: RequestedRide) {
        val notifications = getSystemService(NotificationManager::class.java) ?: return
        ensureAlertChannel(notifications)
        val behavior = PickupNotificationPolicy.behaviorFor(PickupAlertKind.NEW_RIDE_REQUEST)
        val openApp = openMainActivityPendingIntent(2, requestId = ride.id)
        val tapIntent = when (behavior.tapBehavior) {
            AlertTapBehavior.DISMISS_ONLY ->
                NotificationDismissReceiver.pendingIntent(this, ALERT_REQUEST_NOTIFICATION_ID)
            AlertTapBehavior.OPEN_APP_SCREEN -> openApp
        }
        val openAction = Notification.Action.Builder(
            android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_sharing),
            "요청 확인",
            tapIntent,
        ).build()
        val alert = buildAlertNotification(
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
                this,
                "마중: ${ride.nickname}님이 ‘${ride.place.label}’에서 마중을 요청했어요.",
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
        voice.speak("${ride.nickname}님이 ${ride.place.label}에서 마중을 요청했어요.")
    }

    @Suppress("DEPRECATION")
    internal fun notifyPassengerEvents() {
        val notifications = getSystemService(NotificationManager::class.java) ?: return
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
            val tapIntent = when (behavior.tapBehavior) {
                AlertTapBehavior.DISMISS_ONLY ->
                    NotificationDismissReceiver.pendingIntent(this, ALERT_PROPOSAL_NOTIFICATION_ID)
                AlertTapBehavior.OPEN_APP_SCREEN -> openApp
            }
            val acceptIntent = Intent(this, PickupSharingService::class.java).apply {
                action = PickupSharingService.ACTION_ACCEPT_PROPOSAL
                putExtra(PickupSharingService.EXTRA_PROPOSAL_ID, proposal)
            }
            val acceptPending = PendingIntent.getService(
                this, 2, acceptIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val acceptAction = Notification.Action.Builder(
                android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_sharing),
                "수락 및 내비 변경",
                acceptPending
            ).build()

            val alert = buildAlertNotification(
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
                    this,
                    "마중: 탑승자가 ‘${proposedPlace.label}’(으)로 장소 변경을 요청했어요.",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
        }
        val readiness = pickup.readiness
        if (pickup.status == "ACTIVE" && readiness == "READY" && lastNotifiedReadiness != "READY") {
            lastNotifiedReadiness = "READY"
            if (!activityResumed) {
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

    companion object {
        const val ALERT_CHANNEL = "pickup-alerts-v2"
        const val ALERT_PROPOSAL_NOTIFICATION_ID = 33
        const val ALERT_REQUEST_NOTIFICATION_ID = 34
        const val ALERT_QUICK_MSG_NOTIFICATION_ID = 35
        const val EXTRA_OPEN_REQUEST_ID = "open_request_id"
    }
}
