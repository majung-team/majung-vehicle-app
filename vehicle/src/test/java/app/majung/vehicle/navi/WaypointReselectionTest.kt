package app.majung.vehicle

import org.junit.Assert.*
import org.junit.Test

class WaypointReselectionTest {
    private val final = NavigationStop(PickupPlace("집", 37.6, 127.3), 900, NavigationStopKind.DESTINATION)
    private val missing = NavigationStop(PickupPlace("기존 경유지", 0.0, 0.0), 120, NavigationStopKind.WAYPOINT)
    private val known = NavigationStop(PickupPlace("유지할 경유지", 37.4, 127.1), 240, NavigationStopKind.WAYPOINT)
    private val selected = PickupPlace("운전자가 고른 장소", 37.5, 127.2)

    @Test fun replacesOnlyExplicitlySelectedUnknownStopsAndPreservesOtherStopsInOrder() {
        val before = NavigationStops(final, listOf(missing, known))
        val plan = PickupPlaceChange.reselect(before, mapOf(0 to selected), 0)!!
        assertEquals(listOf(selected, known.place), plan.waypoints)
        assertEquals(final, plan.previous.destination)
        val after = NavigationStops(final, listOf(NavigationStop(selected, 130, NavigationStopKind.WAYPOINT), known))
        assertNotNull(plan.confirmed(after))
        assertNull(plan.confirmed(NavigationStops(final, after.waypoints.reversed())))
        assertNull(plan.confirmed(NavigationStops(final.copy(place = selected), after.waypoints)))
    }

    @Test fun requiresAllUnknownStopsAndRejectsDuplicatesAndInvalidCoordinates() {
        val before = NavigationStops(final, listOf(missing, missing))
        assertNull(PickupPlaceChange.reselect(before, mapOf(0 to selected), 0))
        assertNull(PickupPlaceChange.reselect(before, mapOf(0 to selected, 1 to selected), 0))
        assertNull(PickupPlaceChange.reselect(before, mapOf(0 to selected, 1 to final.place), 0))
        assertNull(PickupPlaceChange.reselect(before, mapOf(0 to selected, 1 to selected.copy(latitude = Double.NaN)), 0))
        assertNotNull(PickupPlaceChange.reselect(before, mapOf(0 to selected, 1 to known.place), 1))
        assertNull(PickupPlaceChange.reselect(NavigationStops(final, listOf(known)), mapOf(0 to selected), 0))
    }
}
