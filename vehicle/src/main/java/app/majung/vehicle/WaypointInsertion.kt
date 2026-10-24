package app.majung.vehicle

/** Only an explicit request can bind a newly added waypoint to a pickup. */
class WaypointInsertion(val requested: PickupPlace, private val previous: NavigationStops) {
    fun preservesPrevious(route: NavigationStops): Boolean = route.preserves(previous)

    fun confirmed(route: NavigationStops): NavigationStop? {
        if (!route.preserves(previous) || route.waypoints.size != previous.waypoints.size + 1) return null
        val stop = route.resolve(requested) ?: return null
        return stop.takeIf { it.kind == NavigationStopKind.WAYPOINT }
    }
}
