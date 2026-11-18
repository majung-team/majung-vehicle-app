package app.majung.vehicle.domain

import app.majung.vehicle.VehicleApplication
import app.majung.vehicle.navi.NavigationStop
import app.majung.vehicle.navi.NavigationStopKind
import app.majung.vehicle.navi.PickupPlace

/** Adapts application-owned controllers to the independently tested request preparation flow. */
internal class ApplicationRequestPickupHost(private val app: VehicleApplication) :
    RequestPickupFlow.Host {
    private val registration
        get() = app.registration

    private val account
        get() = app.account

    private val pickup
        get() = app.pickup

    private val destination
        get() = app.destination

    override val authority: Any?
        get() =
            registration.vehicleToken?.let { token ->
                account.state
                    .takeIf { it.optString("status") == "LINKED" }
                    ?.optString("majungId")
                    ?.let { token to it }
            }

    override val requestsFresh
        get() = account.requestsFresh

    override val pickupStatus
        get() = pickup.status

    override val hasPickup
        get() = pickup.pickupId != null

    override val pickupBusy
        get() = pickup.busy

    override val sharingRunning
        get() = app.sharingRunning

    override val routeFailure
        get() = destination.failure?.message

    override val confirmedNoGuidance
        get() = destination.confirmedNoGuidance

    override val pickupMessage
        get() = pickup.message

    override fun request(id: String): RequestedRide? =
        account.requests
            .firstOrNull { it.optString("requestId") == id }
            ?.let { item ->
                item.optJSONObject("place")?.let { point ->
                    val place =
                        PickupPlace(
                            point.optString("label"),
                            point.optDouble("latitude"),
                            point.optDouble("longitude"),
                        )
                    if (!NavigationStop(place, 0, NavigationStopKind.WAYPOINT).usable) null
                    else
                        RequestedRide(
                            id,
                            item.optString("nickname"),
                            place,
                            item.optString("status"),
                        )
                }
            }

    override fun canStart(id: String) = pickup.canStartRequested(id)

    override fun hasOtherActiveRequest(id: String) =
        account.requests.any {
            it.optString("status") == "ACTIVE" && it.optString("requestId") != id
        }

    override fun refreshRequests() {
        account.refresh()
    }

    override fun prepareRoute(retry: Boolean) {
        if (retry) destination.prepareRequestedRoute() else destination.prepareForPickup()
    }

    override fun stops() = destination.currentStops()

    override fun select(place: PickupPlace) = destination.selectVerifiedStop(place)

    override fun addWaypoint(place: PickupPlace) = destination.addPickupWaypoint(place)

    override fun setDestination(place: PickupPlace) = destination.navigateToPickup(place)

    override fun clearEnded() {
        pickup.prepareNextPickup()
    }

    override fun start(ride: RequestedRide) {
        pickup.startRequested(ride.id, ride.place, destination.referenceFor(ride.place))
    }
}
