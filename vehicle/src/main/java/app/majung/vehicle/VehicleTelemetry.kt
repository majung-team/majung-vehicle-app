package app.majung.vehicle

import java.time.Instant

/** SDK callback receipt times, not GPS acquisition times (the SDK does not supply those). */
data class VehicleSample(
    val latitude: Double,
    val longitude: Double,
    val durationSeconds: Long,
    val observedAt: Instant,
    val revision: Long
)

class VehicleTelemetry {
    private data class Fix(val latitude: Double, val longitude: Double, val elapsed: Long, val wall: Instant)
    private data class Estimate(val destination: PickupPlace, val seconds: Long, val elapsed: Long, val wall: Instant)
    private var fix: Fix? = null
    private var estimates: List<Estimate> = emptyList()
    private var revision = 0L

    fun location(latitude: Double, longitude: Double, elapsed: Long, wall: Instant) {
        fix = if (latitude.isFinite() && longitude.isFinite() && latitude in -90.0..90.0
            && longitude in -180.0..180.0) Fix(latitude, longitude, elapsed, wall) else null
        revision++
    }

    fun destination(place: PickupPlace, seconds: Long, elapsed: Long, wall: Instant) {
        destinations(listOf(NavigationStop(place, seconds, NavigationStopKind.DESTINATION)), elapsed, wall)
    }

    fun destinations(stops: List<NavigationStop>, elapsed: Long, wall: Instant) {
        estimates = stops.map { Estimate(it.place, it.durationSeconds, elapsed, wall) }
        revision++
    }

    fun invalidate() {
        fix = null
        estimates = emptyList()
        revision++
    }

    fun current(pickupPlace: PickupPlace, elapsed: Long): VehicleSample? {
        val location = fix ?: return null
        val eta = estimates.filter { it.destination.sameCoordinates(pickupPlace) }.singleOrNull() ?: return null
        if (eta.seconds < 0 || eta.destination.label.isBlank() ||
            (eta.destination.latitude == 0.0 && eta.destination.longitude == 0.0)) return null
        if (elapsed < location.elapsed || elapsed < eta.elapsed
            || elapsed - location.elapsed >= 10_000 || elapsed - eta.elapsed >= 10_000) return null
        // Keep the oldest receipt time; sending again must not make an old component fresh.
        return VehicleSample(location.latitude, location.longitude, eta.seconds,
            minOf(location.wall, eta.wall), revision)
    }
}
