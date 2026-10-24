package app.majung.vehicle

import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class PickupArrivalEvidenceTest {
    private val place = PickupPlace("약속 장소", 37.0, 127.0)
    private val other = PickupPlace("다른 장소", 38.0, 128.0)
    private val target = NavigationStop(place, 10, NavigationStopKind.WAYPOINT)
    private val stops = NavigationStops(NavigationStop(other, 120, NavigationStopKind.DESTINATION), listOf(target))
    private val sample = VehicleSample(37.0, 127.0, 10, Instant.EPOCH, 1)
    @Test fun arrivalRequiresCurrentPickupRouteNameAndFreshNearbySample() {
        assertEquals(place, PickupArrivalEvidence.resolve(stops, place, 0, place.label, sample))
        assertNull(PickupArrivalEvidence.resolve(stops, place, 1, place.label, sample))
        assertNull(PickupArrivalEvidence.resolve(stops, place, -1, place.label, sample))
        assertNull(PickupArrivalEvidence.resolve(stops, place, null, place.label, sample))
        assertNull(PickupArrivalEvidence.resolve(stops, place, 0, other.label, sample))
        assertNull(PickupArrivalEvidence.resolve(null, place, 0, place.label, sample))
        assertNull(PickupArrivalEvidence.resolve(stops, place, 0, place.label, null))
        assertNull(PickupArrivalEvidence.resolve(stops, place, 0, place.label, sample.copy(latitude = 38.0)))
        assertNull(PickupArrivalEvidence.resolve(stops, place, 0, place.label, sample.copy(durationSeconds = 120)))
        val duplicated = NavigationStops(stops.destination, listOf(target, target))
        assertNull(PickupArrivalEvidence.resolve(duplicated, place, 0, place.label, sample))
    }
}
