package app.majung.vehicle

import kotlin.math.*

/** A callback index alone is insufficient: require a fresh, unique route target and nearby fix. */
internal object PickupArrivalEvidence {
    fun resolve(stops: NavigationStops?, selected: PickupPlace?, waypointIndex: Int?, eventName: String,
                sample: VehicleSample?): PickupPlace? {
        if (stops == null || selected == null || sample == null) return null
        val target = stops.resolve(selected) ?: return null
        val eventStop = if (waypointIndex == null) stops.destination else stops.waypoints.getOrNull(waypointIndex)
        if (eventStop == null || eventStop != target || eventName.isBlank() || eventName != target.place.label) return null
        if (sample.durationSeconds !in 0..60 || !nearby(sample, target.place)) return null
        return target.place
    }
    private fun nearby(sample: VehicleSample, place: PickupPlace): Boolean {
        val latitude = Math.toRadians(place.latitude - sample.latitude)
        val longitude = Math.toRadians(place.longitude - sample.longitude)
        val distance = sin(latitude / 2).pow(2) + cos(Math.toRadians(sample.latitude)) *
            cos(Math.toRadians(place.latitude)) * sin(longitude / 2).pow(2)
        return 6371000 * 2 * atan2(sqrt(distance.coerceIn(0.0, 1.0)), sqrt((1 - distance).coerceIn(0.0, 1.0))) <= 100
    }
}
