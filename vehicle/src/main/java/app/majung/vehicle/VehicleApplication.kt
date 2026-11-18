package app.majung.vehicle

import android.app.Application
import android.app.NotificationManager
import app.majung.vehicle.data.api.PickupApiClient
import app.majung.vehicle.data.storage.EncryptedPickupSessionStore
import app.majung.vehicle.data.storage.EncryptedVehicleCredentialStore
import app.majung.vehicle.feature.account.controller.VehicleAccountController
import app.majung.vehicle.feature.account.controller.VehiclePeopleController
import app.majung.vehicle.feature.pickup.controller.PickupController
import app.majung.vehicle.feature.pickup.flow.ApplicationRequestPickupHost
import app.majung.vehicle.feature.pickup.flow.RequestPickupFlow
import app.majung.vehicle.feature.vehicle.controller.VehicleEnrollmentController
import app.majung.vehicle.feature.vehicle.controller.VehicleRegistrationController
import app.majung.vehicle.navi.DestinationReader
import app.majung.vehicle.notification.PickupAlerts
import app.majung.vehicle.notification.voice.PickupVoiceGuide
import app.majung.vehicle.service.BackgroundPickupPoller
import app.majung.vehicle.service.PickupSharingService
import app.majung.vehicle.util.SharingNotice

class VehicleApplication : Application() {
    private val alerts by lazy { PickupAlerts(this) }
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

    private val backgroundPoller by lazy { BackgroundPickupPoller(this) }

    override fun onCreate() {
        super.onCreate()
        sharingNotice = SharingNotice(this)
        destination = DestinationReader(this)
        registration =
            VehicleRegistrationController(PickupApiClient(), EncryptedVehicleCredentialStore(this))
        pickup =
            PickupController(
                sessionStore = EncryptedPickupSessionStore(this),
                registration = registration,
            )
        account = VehicleAccountController(PickupApiClient(), registration)
        people =
            VehiclePeopleController(
                PickupApiClient(),
                { registration.vehicleToken },
                {
                    account.state
                        .takeIf { it.optString("status") == "LINKED" }
                        ?.optString("majungId")
                        ?.takeIf { it.isNotBlank() }
                },
            )
        requestFlow =
            RequestPickupFlow(
                ApplicationRequestPickupHost(this),
                android.os.SystemClock::elapsedRealtime,
            )
        enrollment =
            VehicleEnrollmentController(
                PickupApiClient(),
                EncryptedVehicleCredentialStore(this, pending = true),
                EncryptedVehicleCredentialStore(this),
                registration,
            )
        enrollment.changed = { pickup.changed?.invoke() }
        voice = PickupVoiceGuide(this, pickup)
        voice.changed = { pickup.changed?.invoke() }
        registration.changed = {
            account.registrationChanged()
            if (registration.vehicleToken != null) account.refresh()
            pickup.changed?.invoke()
        }
        registration.refresh()
        enrollment.restore()
        pickup.restoreSession()
        destination.pickupPlace = { pickup.place }
        destination.pickupArrived = pickup::arriveFromSdk
        destination.telemetryChanged = {
            if (
                sharingRunning ||
                    (!activityResumed &&
                        sharingNotice.accepted &&
                        !pickup.needsSharingConfirmation &&
                        pickup.status in setOf("ACTIVE", "ARRIVED") &&
                        PickupSharingService.hasLocationPermission(this))
            ) {
                destination.checkProximityArrival()
                pickup.syncVehicle(destination)
            }
            pickup.changed?.invoke()
        }
        account.onNewPendingRide = { ride -> alerts.notifyNewRide(ride) }
        pickup.onQuickMessageReceived = { message -> alerts.notifyQuickMessage(message) }
        pickup.addObserver {
            if (sharingNotice.accepted && !pickup.needsSharingConfirmation) {
                pickup.checkAutoConfirmProposal(destination)
                notifyPassengerEvents()
                if (
                    !activityResumed &&
                        !serviceRunning &&
                        !sharingRunning &&
                        pickup.status in setOf("ACTIVE", "ARRIVED") &&
                        PickupSharingService.hasLocationPermission(this)
                ) {
                    destination.restorePickupReference(pickup.place, pickup.navigationReference)
                    destination.connect()
                    destination.pollTelemetry()
                    pickup.syncVehicle(destination)
                }
            }
        }
        backgroundPoller.start()
    }

    fun ensureAlertChannel(notifications: NotificationManager) =
        alerts.ensureAlertChannel(notifications)

    internal fun notifyPassengerEvents() = alerts.notifyPassengerEvents()

    companion object {
        const val ALERT_CHANNEL = "pickup-alerts-v2"
        const val ALERT_PROPOSAL_NOTIFICATION_ID = 33
        const val ALERT_REQUEST_NOTIFICATION_ID = 34
        const val ALERT_QUICK_MSG_NOTIFICATION_ID = 35
        const val EXTRA_OPEN_REQUEST_ID = "open_request_id"
    }
}
