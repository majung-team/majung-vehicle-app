package app.majung.vehicle

/** An explicit place change preserves the final destination and every other waypoint in order. */
class PickupPlaceChange private constructor(val requested: PickupPlace, val previous: NavigationStops,
                                            val waypoints: List<PickupPlace>) {
    fun confirmed(route: NavigationStops): NavigationStop? {
        if (!route.destination.place.sameCoordinates(previous.destination.place) ||
            route.waypoints.size != waypoints.size ||
            route.waypoints.zip(waypoints).any { (actual, expected) -> !actual.usable || !actual.place.sameCoordinates(expected) }) return null
        return route.resolve(requested)?.takeIf { it.kind == NavigationStopKind.WAYPOINT }
    }

    companion object {
        fun plan(previous: NavigationStops, old: PickupPlace, next: PickupPlace): PickupPlaceChange? {
            val selected = previous.resolve(old) ?: return null
            if (!NavigationStop(next, 0, NavigationStopKind.WAYPOINT).usable ||
                previous.all.any { it.place.sameCoordinates(next) } || previous.all.any { !it.usable }) return null
            val points = if (selected.kind == NavigationStopKind.DESTINATION)
                listOf(next) + previous.waypoints.map { it.place }
            else previous.waypoints.map { if (it.place.sameCoordinates(old)) next else it.place }
            if (points.size > 2) return null
            return PickupPlaceChange(next, previous, points)
        }
    }
}
