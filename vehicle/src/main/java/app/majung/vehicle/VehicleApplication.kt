package app.majung.vehicle

import android.app.Application

class VehicleApplication : Application() {
    lateinit var destination: DestinationReader
        private set
    lateinit var pickup: PickupController
        private set

    var sharingRunning: Boolean = false
        internal set
    var sharingMessage: String = "위치 권한을 허용하면 화면 밖에서도 공유할 수 있어요"
        internal set

    override fun onCreate() {
        super.onCreate()
        destination = DestinationReader(this)
        pickup = PickupController(sessionStore = EncryptedPickupSessionStore(this))
        pickup.restoreSession()
        destination.telemetryChanged = {
            if (sharingRunning) pickup.syncVehicle(destination)
        }
    }
}
