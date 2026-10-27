package app.majung.vehicle

import android.app.Application

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
    internal lateinit var voice: PickupVoiceGuide
        private set

    var sharingRunning: Boolean = false
        internal set
    var sharingMessage: String = "위치 권한을 허용하면 화면 밖에서도 공유할 수 있어요"
        internal set

    override fun onCreate() {
        super.onCreate()
        destination = DestinationReader(this)
        registration = VehicleRegistrationController(PickupApiClient(), EncryptedVehicleCredentialStore(this))
        pickup = PickupController(sessionStore = EncryptedPickupSessionStore(this), registration = registration)
        account = VehicleAccountController(PickupApiClient(), registration)
        enrollment = VehicleEnrollmentController(PickupApiClient(), EncryptedVehicleCredentialStore(this, pending = true), EncryptedVehicleCredentialStore(this), registration)
        enrollment.changed = { pickup.changed?.invoke() }
        voice = PickupVoiceGuide(this, pickup)
        voice.changed = { pickup.changed?.invoke() }
        registration.changed = { account.registrationChanged(); pickup.changed?.invoke() }
        registration.refresh()
        enrollment.restore()
        pickup.restoreSession()
        destination.pickupPlace = { pickup.place }
        destination.pickupArrived = pickup::arriveFromSdk
        destination.telemetryChanged = {
            if (sharingRunning) pickup.syncVehicle(destination)
        }
    }
}
