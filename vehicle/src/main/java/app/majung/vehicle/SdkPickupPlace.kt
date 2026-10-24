package app.majung.vehicle

/** Identity and coordinates received together from the navigation place list. Never logged. */
data class SdkPickupPlace(
    val place: PickupPlace,
    val poiId: String,
    val address: String,
    val poiSubId: String
)

internal data class SdkWaypoint(val place: PickupPlace, val durationSeconds: Long, val poiId: String)

/** Emulator 2.1 returns zero coordinates but preserves POI identity. No index/name fallback. */
internal object WaypointCoordinates {
    fun resolve(points: List<SdkWaypoint>, selected: SdkPickupPlace?): List<NavigationStop> = points.map { point ->
        val selectedPlace = selected?.place
        val identified = selected != null && selected.poiId.isNotBlank() && point.poiId == selected.poiId &&
            points.count { it.poiId == selected.poiId } == 1 && selectedPlace != null &&
            NavigationStop(selectedPlace, point.durationSeconds, NavigationStopKind.WAYPOINT).usable
        val zeroCoordinates = point.place.latitude == 0.0 && point.place.longitude == 0.0
        NavigationStop(if (zeroCoordinates && identified) selectedPlace!! else point.place,
            point.durationSeconds, NavigationStopKind.WAYPOINT)
    }
}
