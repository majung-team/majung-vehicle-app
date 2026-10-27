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
    internal lateinit var requestFlow: RequestPickupFlow
        private set
    internal lateinit var people: VehiclePeopleController
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
            if (sharingRunning) pickup.syncVehicle(destination)
        }
    }
}
