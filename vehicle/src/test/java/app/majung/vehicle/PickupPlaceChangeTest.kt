package app.majung.vehicle

import org.junit.Assert.*
import org.junit.Test

class PickupPlaceChangeTest {
    private val final = PickupPlace("최종 목적지", 37.1, 127.1)
    private val old = PickupPlace("기존 픽업", 37.2, 127.2)
    private val next = PickupPlace("새 픽업", 37.3, 127.3)
    private val other = PickupPlace("다른 경유지", 37.4, 127.4)
    private fun route(vararg stops: PickupPlace, destination: PickupPlace = final) = NavigationStops(
        NavigationStop(destination, 500, NavigationStopKind.DESTINATION),
        stops.map { NavigationStop(it, 100, NavigationStopKind.WAYPOINT) })

    @Test fun replacesOnlyThePickupAndPreservesOrderAndFinalDestination() {
        val plan = requireNotNull(PickupPlaceChange.plan(route(old, other), old, next))
        assertNotNull(plan.confirmed(route(next, other)))
        assertNull(plan.confirmed(route(other, next)))
        assertNull(plan.confirmed(route(next)))
        assertNull(plan.confirmed(route(next, other, destination = old)))
        assertNull(plan.confirmed(route(old, other)))
    }
    @Test fun finalPickupBecomesAWaypointWithoutRemovingTheFinalDestination() {
        val plan = requireNotNull(PickupPlaceChange.plan(route(other), final, next))
        assertNotNull(plan.confirmed(route(next, other)))
        assertNull(PickupPlaceChange.plan(route(old, other), final, next))
    }
    @Test fun rejectsUnknownAmbiguousAndDuplicateTargets() {
        assertNull(PickupPlaceChange.plan(route(old, old), old, next))
        assertNull(PickupPlaceChange.plan(route(old), old, final))
        assertNull(PickupPlaceChange.plan(route(old, PickupPlace("미확인", 0.0, 0.0)), old, next))
        assertNull(PickupPlaceChange.plan(route(old), other, next))
    }
}
