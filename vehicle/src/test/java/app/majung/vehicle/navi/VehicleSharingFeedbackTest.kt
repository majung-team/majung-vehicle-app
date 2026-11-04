package app.majung.vehicle

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VehicleSharingFeedbackTest {
    @Test fun staleAcknowledgementDoesNotClaimSharing() {
        val message = VehicleSharingFeedback.accepted(true, "STALE", 0)
        assertTrue(message.contains("공유하지 않아요"))
        assertTrue(message.contains("날짜·시간"))
    }
    @Test fun freshRequiresPositiveRemainingLifetime() {
        assertTrue(VehicleSharingFeedback.accepted(true, "FRESH", 30000).contains("공유하고 있어요"))
        assertFalse(VehicleSharingFeedback.accepted(true, "FRESH", 0).contains("공유하고 있어요"))
        assertFalse(VehicleSharingFeedback.accepted(true, "UNKNOWN", 30000).contains("공유하고 있어요"))
    }
    @Test fun missingSdkDataRequiresAcknowledgedInvalidation() {
        assertTrue(VehicleSharingFeedback.accepted(false, "WAITING", 0).contains("지웠어요"))
        assertFalse(VehicleSharingFeedback.accepted(false, "FRESH", 30000).contains("지웠어요"))
    }
    @Test fun futureAndOutOfOrderErrorsHaveDifferentGuidance() {
        assertTrue(VehicleSharingFeedback.rejected("FUTURE_SOURCE_TIME").contains("자동 날짜·시간"))
        assertTrue(VehicleSharingFeedback.rejected("STALE_EVENT").contains("이전 순서"))
        assertFalse(VehicleSharingFeedback.rejected(null).contains("공유하고 있어요"))
    }
}
