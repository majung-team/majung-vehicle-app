package app.majung.vehicle

import kotlin.math.abs

enum class NavigationStopKind { DESTINATION, WAYPOINT }

data class NavigationStop(val place: PickupPlace, val durationSeconds: Long, val kind: NavigationStopKind) {
    val usable: Boolean get() = place.label.isNotBlank() && place.label.length <= 120 &&
        place.latitude.isFinite() && place.longitude.isFinite() &&
        place.latitude in -90.0..90.0 && place.longitude in -180.0..180.0 &&
        (place.latitude != 0.0 || place.longitude != 0.0) && durationSeconds >= 0
}

internal fun PickupPlace.sameCoordinates(other: PickupPlace): Boolean =
    abs(latitude - other.latitude) <= 0.000001 && abs(longitude - other.longitude) <= 0.000001

/** Coordinates bind a pickup to a stop. An index or a repeated name is not an identity. */
class NavigationStops(val destination: NavigationStop, val waypoints: List<NavigationStop>) {
    val all: List<NavigationStop> = listOf(destination) + waypoints

    fun resolve(place: PickupPlace): NavigationStop? = all.filter { it.place.sameCoordinates(place) }.singleOrNull()?.takeIf { it.usable }

    fun preserves(previous: NavigationStops): Boolean =
        destination.place.sameCoordinates(previous.destination.place) &&
            previous.waypoints.all { old -> waypoints.count { it.place.sameCoordinates(old.place) } == 1 }
}
