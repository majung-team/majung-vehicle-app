package app.majung.vehicle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class NavigationStopsTest {
    private val end = NavigationStop(PickupPlace("최종 목적지", 37.0, 127.0), 1800, NavigationStopKind.DESTINATION)
    private val pickup = NavigationStop(PickupPlace("픽업 장소", 37.1, 127.1), 600, NavigationStopKind.WAYPOINT)
    private val other = NavigationStop(PickupPlace("다른 경유지", 37.2, 127.2), 1000, NavigationStopKind.WAYPOINT)

    @Test fun waypointEtaBelongsToCoordinatesEvenWhenOrderChangesOrNamesRepeat() {
        val buffer = VehicleTelemetry()
        val now = Instant.parse("2026-10-23T00:00:00Z")
        buffer.destinations(listOf(end.copy(place = end.place.copy(label = pickup.place.label)), other, pickup), 1000, now)
        buffer.location(36.9, 126.9, 1000, now)
        assertEquals(600, buffer.current(pickup.place, 1001)!!.durationSeconds)
        assertEquals(1000, buffer.current(other.place, 1001)!!.durationSeconds)
        buffer.destinations(listOf(end, pickup, other), 2000, now.plusSeconds(1))
        assertEquals(600, buffer.current(pickup.place, 2001)!!.durationSeconds)
    }

    @Test fun missingDuplicateOrExpiredWaypointNeverFallsBackToFinalEta() {
        val buffer = VehicleTelemetry()
        val now = Instant.parse("2026-10-23T00:00:00Z")
        buffer.location(36.9, 126.9, 1000, now)
        buffer.destinations(listOf(end, pickup, pickup.copy(place = pickup.place.copy(latitude = 37.1000005))), 1000, now)
        assertNull(buffer.current(pickup.place, 1001))
        buffer.destinations(listOf(end), 1000, now)
        assertNull(buffer.current(pickup.place, 1001))
        buffer.destinations(listOf(end, pickup), 1000, now)
        assertNull(buffer.current(pickup.place, 11000))
        buffer.invalidate()
        buffer.location(36.9, 126.9, 12000, now.plusSeconds(11))
        assertNull(buffer.current(pickup.place, 12001))
    }

    @Test fun insertionRequiresOriginalDestinationPreviousStopsAndUniqueRequestedCoordinates() {
        val original = NavigationStops(end, listOf(other))
        val request = WaypointInsertion(pickup.place, original)
        assertEquals(pickup, request.confirmed(NavigationStops(end, listOf(pickup, other))))
        assertNull(request.confirmed(NavigationStops(end.copy(place = pickup.place), listOf(pickup, other))))
        assertNull(request.confirmed(NavigationStops(end, listOf(pickup))))
        assertNull(request.confirmed(NavigationStops(end, listOf(pickup, pickup, other))))
        assertNull(request.confirmed(NavigationStops(end, listOf(pickup.copy(place = pickup.place.copy(latitude = 0.0, longitude = 0.0)), other))))
    }

    @Test fun unknownWaypointDoesNotBecomeAnEstimateOrPreventExplicitFinalDestinationUse() {
        val unknown = pickup.copy(place = PickupPlace("", 0.0, 0.0))
        val stops = NavigationStops(end, listOf(unknown))
        assertEquals(end, stops.resolve(end.place))
        assertNull(stops.resolve(unknown.place))
        assertNull(WaypointInsertion(pickup.place, NavigationStops(end, emptyList())).confirmed(stops))
        val buffer = VehicleTelemetry()
        val now = Instant.parse("2026-10-23T00:00:00Z")
        buffer.destinations(stops.all, 1000, now)
        buffer.location(36.9, 126.9, 1000, now)
        assertNull(buffer.current(unknown.place, 1001))
        assertEquals(1800, buffer.current(end.place, 1001)!!.durationSeconds)
    }

    @Test fun routeChangeRequiresAnotherPairOfValidatedSnapshots() {
        val gate = RouteValidation()
        fun cycle(now: Long, points: List<PickupPlace>): Boolean {
            assertTrue(gate.requestState(now))
            assertTrue(gate.receiveState(true, now + 1))
            return gate.receiveRoute(points, now + 2)
        }
        assertFalse(cycle(0, listOf(end.place, pickup.place, other.place)))
        assertTrue(cycle(5000, listOf(end.place, pickup.place, other.place)))
        assertFalse(cycle(10000, listOf(end.place, other.place, pickup.place)))
        assertFalse(gate.ready)
        assertTrue(cycle(15000, listOf(end.place, other.place, pickup.place)))
        assertFalse(cycle(20000, listOf(end.place, other.place)))
        assertFalse(gate.ready)
    }
}
