package app.majung.vehicle

import java.util.UUID

/** Selected coordinates and their request identity. Never logged. */
data class SdkPickupPlace(
    val place: PickupPlace,
    val poiId: String,
    val address: String,
    val poiSubId: String,
    val poiProvider: String = ""
) {
    /** Our own place namespace; never pretend this is a navigation provider's POI ID. */
    fun withRequestIdentity(): SdkPickupPlace = if (poiId.isBlank())
        copy(poiId = "majung:" + UUID.randomUUID(), poiProvider = "majung") else this
}

internal data class SdkWaypoint(val place: PickupPlace, val durationSeconds: Long, val poiId: String)

/** Emulator 2.1 returns zero coordinates but preserves POI identity. No index/name fallback. */
internal object WaypointCoordinates {
    fun resolve(points: List<SdkWaypoint>, selected: SdkPickupPlace?): List<NavigationStop> =
        resolveKnown(points, listOfNotNull(selected))

    fun resolveKnown(points: List<SdkWaypoint>, known: List<SdkPickupPlace>): List<NavigationStop> = points.map { point ->
        val selected = known.filter { it.poiId.isNotBlank() && it.poiId == point.poiId }.singleOrNull()
        val selectedPlace = selected?.place
        val identified = selected != null && points.count { it.poiId == selected.poiId } == 1 && selectedPlace != null &&
            NavigationStop(selectedPlace, point.durationSeconds, NavigationStopKind.WAYPOINT).usable
        val zeroCoordinates = point.place.latitude == 0.0 && point.place.longitude == 0.0
        NavigationStop(if (zeroCoordinates && identified) selectedPlace!! else point.place,
            point.durationSeconds, NavigationStopKind.WAYPOINT)
    }
}
