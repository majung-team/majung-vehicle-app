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
        /** The driver explicitly reselects every unknown stop before rebuilding the route. */
        fun reselect(previous: NavigationStops, replacements: Map<Int, PickupPlace>, selectedIndex: Int): PickupPlaceChange? {
            val unknown = previous.waypoints.indices.filter { !previous.waypoints[it].usable }.toSet()
            if (previous.waypoints.size !in 1..2 || !previous.destination.usable || unknown.isEmpty() || replacements.keys != unknown || selectedIndex !in unknown) return null
            val points = previous.waypoints.mapIndexed { index, stop -> replacements[index] ?: stop.place }
            if (points.any { !NavigationStop(it, 0, NavigationStopKind.WAYPOINT).usable }) return null
            val all = listOf(previous.destination.place) + points
            if (all.any { point -> all.count { it.sameCoordinates(point) } != 1 }) return null
            return PickupPlaceChange(points[selectedIndex], previous, points)
        }

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
