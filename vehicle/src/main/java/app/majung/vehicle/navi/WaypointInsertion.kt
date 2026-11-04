package app.majung.vehicle.navi


import app.majung.vehicle.*
import app.majung.vehicle.ui.*
import app.majung.vehicle.ui.sections.*
import app.majung.vehicle.navi.*
import app.majung.vehicle.domain.*
import app.majung.vehicle.data.api.*
import app.majung.vehicle.data.storage.*
import app.majung.vehicle.util.*
import app.majung.vehicle.service.*

/** Only an explicit request can bind a newly added waypoint to a pickup. */
class WaypointInsertion(val requested: PickupPlace, private val previous: NavigationStops) {
    fun preservesPrevious(route: NavigationStops): Boolean = route.preserves(previous)

    fun confirmed(route: NavigationStops): NavigationStop? {
        if (!route.preserves(previous) || route.waypoints.size != previous.waypoints.size + 1) return null
        val stop = route.resolve(requested) ?: return null
        return stop.takeIf { it.kind == NavigationStopKind.WAYPOINT }
    }
}
