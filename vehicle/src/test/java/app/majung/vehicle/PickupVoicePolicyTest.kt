package app.majung.vehicle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PickupVoicePolicyTest {
    @Test fun repeatedPollingAndReadinessTogglingDoNotRepeatAnnouncements() {
        val policy = PickupVoicePolicy()
        assertNull(policy.observe("one", "ACTIVE", "PREPARING", true))
        assertEquals(PickupVoicePolicy.Notice.READY, policy.observe("one", "ACTIVE", "READY", true))
        repeat(20) { assertNull(policy.observe("one", "ACTIVE", "READY", true)) }
        assertNull(policy.observe("one", "ACTIVE", "PREPARING", true))
        assertNull(policy.observe("one", "ACTIVE", "READY", true))
        assertEquals(PickupVoicePolicy.Notice.ARRIVED, policy.observe("one", "ARRIVED", "READY", true))
        assertNull(policy.observe("one", "ARRIVED", "READY", true))
    }

    @Test fun restoredSnapshotsAndNewPickupDoNotReplayOldEvents() {
        val policy = PickupVoicePolicy()
        assertNull(policy.observe("one", "ARRIVED", "READY", true))
        assertNull(policy.observe("one", "COMPLETED", "READY", true))
        assertNull(policy.observe("two", "ACTIVE", "PREPARING", true))
        assertEquals(PickupVoicePolicy.Notice.READY, policy.observe("two", "ACTIVE", "READY", true))
        assertNull(policy.observe("two", "CANCELLED", "PREPARING", true))
        assertNull(policy.observe(null, "", "", true))
    }

    @Test fun disabledOfflineAndUnconfirmedChangesAreNeverReplayed() {
        val policy = PickupVoicePolicy()
        assertNull(policy.observe("one", "ACTIVE", "PREPARING", false))
        assertNull(policy.observe("one", "ACTIVE", "READY", false))
        assertNull(policy.observe("one", "ACTIVE", "READY", true))
        assertNull(policy.observe("one", "ARRIVED", "READY", false))
        assertNull(policy.observe("one", "ARRIVED", "READY", true))
    }

    @Test fun arrivalTakesPriorityWhenReadinessAlsoChanges() {
        val policy = PickupVoicePolicy()
        policy.observe("one", "ACTIVE", "PREPARING", true)
        assertEquals(PickupVoicePolicy.Notice.ARRIVED, policy.observe("one", "ARRIVED", "READY", true))
    }
}
