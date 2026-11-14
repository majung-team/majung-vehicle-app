package app.majung.vehicle.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PickupNotificationPolicyTest {
    @Test fun quickMessageStaysInNavigationWithoutFullScreenAndShowsOverlayToast() {
        val behavior = PickupNotificationPolicy.behaviorFor(PickupAlertKind.QUICK_MESSAGE)
        assertFalse(behavior.useFullScreenIntent)
        assertEquals(AlertTapBehavior.DISMISS_ONLY, behavior.tapBehavior)
        assertTrue(behavior.showOverlayToast)
    }

    @Test fun placeProposalAndRideRequestNeverStealScreenAutomatically() {
        val proposal = PickupNotificationPolicy.behaviorFor(PickupAlertKind.PLACE_PROPOSAL)
        assertFalse(proposal.useFullScreenIntent)
        assertEquals(AlertTapBehavior.OPEN_APP_SCREEN, proposal.tapBehavior)
        assertTrue(proposal.showOverlayToast)

        val rideRequest = PickupNotificationPolicy.behaviorFor(PickupAlertKind.NEW_RIDE_REQUEST)
        assertFalse(rideRequest.useFullScreenIntent)
        assertEquals(AlertTapBehavior.OPEN_APP_SCREEN, rideRequest.tapBehavior)
        assertTrue(rideRequest.showOverlayToast)
    }
}
