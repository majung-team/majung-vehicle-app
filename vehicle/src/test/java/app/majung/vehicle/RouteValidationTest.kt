package app.majung.vehicle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteValidationTest {
    private val place = PickupPlace("확정 장소", 37.0, 127.0)

    private fun cycle(gate: RouteValidation, now: Long, destination: PickupPlace? = place): Boolean {
        assertTrue(gate.requestState(now))
        assertTrue(gate.receiveState(true, now + 1))
        return gate.receiveDestination(destination, now + 2)
    }

    @Test fun missingCompletionCanRecoverOnlyAfterTwoMatchingValidatedResponses() {
        val gate = RouteValidation()
        assertFalse(cycle(gate, 0))
        assertFalse(gate.ready)
        assertTrue(cycle(gate, 5000))
        gate.invalidate() // a new route start or error immediately closes sharing
        assertFalse(gate.ready)
        assertFalse(cycle(gate, 10000))
        assertTrue(cycle(gate, 15000))
    }

    @Test fun timeAloneAndUnsolicitedOrExpiredCallbacksCannotRecover() {
        val gate = RouteValidation()
        assertFalse(gate.receiveState(true, 0))
        assertFalse(gate.receiveDestination(place, 100000))
        assertTrue(gate.requestState(100000))
        assertFalse(gate.receiveState(true, 104000))
        assertFalse(gate.ready)
        assertFalse(cycle(gate, 110000))
        assertTrue(gate.requestState(115000))
        assertTrue(gate.receiveState(true, 115001))
        assertFalse(gate.receiveDestination(place, 119001))
        assertFalse(gate.ready)
    }

    @Test fun interruptedOrFailedValidationDiscardsEarlierEvidence() {
        val gate = RouteValidation()
        assertFalse(cycle(gate, 0))
        assertTrue(gate.requestState(5000))
        assertFalse(gate.receiveState(false, 5001))
        assertFalse(cycle(gate, 10000))
        assertFalse(cycle(gate, 15000, null))
        assertFalse(cycle(gate, 20000))
        gate.invalidate()
        assertFalse(gate.receiveDestination(place, 20003))
        assertFalse(cycle(gate, 25000))
    }

    @Test fun differentDestinationNeedsNewConfirmationAndCannotUseOldPickupTelemetry() {
        val gate = RouteValidation()
        assertFalse(cycle(gate, 0))
        val other = place.copy(latitude = 38.0)
        assertFalse(cycle(gate, 5000, other))
        assertTrue(cycle(gate, 10000, other))
        assertFalse(cycle(gate, 15000, place))
        assertFalse(gate.ready)
        assertTrue(cycle(gate, 20000, place))
    }

    @Test fun destinationAndWaypointHaveSeparateDeadlinesAndDuplicateDestinationIsIgnored() {
        val gate = RouteValidation()
        assertTrue(gate.requestState(0))
        assertTrue(gate.receiveState(true, 1))
        assertTrue(gate.beginWaypointResponse(3000))
        assertFalse(gate.beginWaypointResponse(3001))
        assertFalse(gate.receiveRoute(listOf(place), 6000))
        assertTrue(gate.requestState(8000))
        assertTrue(gate.receiveState(true, 8001))
        assertTrue(gate.beginWaypointResponse(11000))
        assertTrue(gate.receiveRoute(listOf(place), 14000))
    }

    @Test fun duplicatePollDoesNotResetInFlightResponseButTimeoutClosesReadyGate() {
        val gate = RouteValidation()
        cycle(gate, 0)
        assertTrue(cycle(gate, 5000))
        assertTrue(gate.requestState(10000))
        assertFalse(gate.requestState(10001))
        assertTrue(gate.requestState(15000))
        assertFalse(gate.ready)
        assertTrue(gate.receiveState(true, 15001))
        assertFalse(gate.receiveDestination(place, 15002))
    }
    @Test fun confirmationScheduleStartsAtResponseEvenWhenSecondResponseWouldBeFaster() {
        val gate=RouteValidation()
        assertTrue(gate.requestState(0))
        assertTrue(gate.receiveState(true,100))
        assertFalse(gate.receiveDestination(place,900))
        assertEquals(900L,gate.confirmationDelay(5000))
        assertEquals(0L,gate.confirmationDelay(5900))
        assertTrue(gate.requestState(5900))
        assertTrue(gate.receiveState(true,5901))
        assertTrue(gate.receiveDestination(place,5902))
        assertEquals(0L,gate.confirmationDelay(5903))
        gate.invalidate()
        assertEquals(0L,gate.confirmationDelay(6000))
    }

}
