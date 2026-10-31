package app.majung.vehicle

/** Recent places are a coordinate source only for an exact, unambiguous SDK identity. */
internal class WaypointRecentRecovery {
    private data class PointKey(val place: PickupPlace, val id: String, val provider: String, val subId: String)
    private data class RouteKey(val destination: PickupPlace, val points: List<PointKey>)
    private var key: RouteKey? = null
    private var attempted = false
    private var waiting = false
    private var requestedAt = 0L
    private var points = emptyList<SdkWaypoint>()
    var recovered = emptyList<SdkPickupPlace>()
        private set

    fun observe(destination: PickupPlace, route: List<SdkWaypoint>, known: List<SdkPickupPlace>, now: Long): Boolean {
        val next = RouteKey(destination, route.map { PointKey(it.place, it.poiId, it.poiProvider, it.poiSubId) })
        if (next != key) { clear(); key = next }
        points = route.toList()
        val resolved = WaypointCoordinates.resolveKnown(route, known + recovered)
        val missing = route.indices.any { index ->
            val point = route[index]
            !resolved[index].usable && point.place.latitude == 0.0 && point.place.longitude == 0.0 &&
                point.poiId.isNotBlank() && route.count { it.poiId == point.poiId } == 1
        }
        if (!missing || attempted) return false
        attempted = true
        waiting = true
        requestedAt = now
        return true
    }

    fun receive(recent: List<SdkPickupPlace>, now: Long): Boolean {
        if (!waiting) return false
        waiting = false
        if (now - requestedAt !in 0 until 8000) return false
        recovered = points.mapNotNull { point ->
            if (point.place.latitude != 0.0 || point.place.longitude != 0.0 || point.poiId.isBlank() ||
                points.count { it.poiId == point.poiId } != 1) return@mapNotNull null
            recent.filter { it.poiId == point.poiId && it.poiProvider == point.poiProvider &&
                it.poiSubId == point.poiSubId }.singleOrNull()?.takeIf {
                NavigationStop(it.place, point.durationSeconds, NavigationStopKind.WAYPOINT).usable
            }
        }
        return recovered.isNotEmpty()
    }

    fun expire(now: Long) {
        if (waiting && now - requestedAt >= 8000) waiting = false
    }

    fun clear() {
        key = null
        attempted = false
        waiting = false
        points = emptyList()
        recovered = emptyList()
    }
}
