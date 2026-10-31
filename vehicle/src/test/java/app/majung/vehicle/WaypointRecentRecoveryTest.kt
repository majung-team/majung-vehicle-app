package app.majung.vehicle

import org.junit.Assert.*
import org.junit.Test

class WaypointRecentRecoveryTest {
    private val final = PickupPlace("목적지", 37.5, 127.1)
    private val missing = SdkWaypoint(PickupPlace("이름으로 추측하지 않음", 0.0, 0.0), 123, "poi", "provider", "1")
    private val recent = SdkPickupPlace(PickupPlace("확인된 장소", 37.4, 127.2), "poi", "", "1", "provider")

    @Test fun exactIdentityRecoversOnlyCoordinatesWhileKeepingWaypointEta() {
        val recovery = WaypointRecentRecovery()
        assertTrue(recovery.observe(final, listOf(missing), emptyList(), 1000))
        assertTrue(recovery.receive(listOf(recent), 1200))
        val resolved = WaypointCoordinates.resolveKnown(listOf(missing), recovery.recovered).single()
        assertEquals(recent.place, resolved.place)
        assertEquals(123, resolved.durationSeconds.toInt())
        assertFalse(recovery.observe(final, listOf(missing.copy(durationSeconds = 110)), emptyList(), 2000))
    }

    @Test fun wrongProviderSubIdBlankOrDuplicateIdentityAndInvalidCoordinateAreRejected() {
        val candidates = listOf(listOf(recent.copy(poiId = "other")), listOf(recent.copy(poiProvider = "other")),
            listOf(recent.copy(poiSubId = "2")), listOf(recent, recent),
            listOf(recent, recent.copy(place = recent.place.copy(latitude = Double.NaN))),
            listOf(recent.copy(place = recent.place.copy(latitude = 0.0, longitude = 0.0))))
        candidates.forEach { values ->
            val recovery = WaypointRecentRecovery()
            assertTrue(recovery.observe(final, listOf(missing), emptyList(), 1000))
            assertFalse(recovery.receive(values, 1200))
            assertTrue(recovery.recovered.isEmpty())
            assertFalse(recovery.observe(final, listOf(missing), emptyList(), 2000))
        }
        val recovery = WaypointRecentRecovery()
        assertFalse(recovery.observe(final, listOf(missing.copy(poiId = "")), emptyList(), 1000))
        assertFalse(recovery.observe(final, listOf(missing, missing), emptyList(), 1000))
    }

    @Test fun timeoutDisconnectAndChangedRouteCannotConsumeOldLookup() {
        for (receivedAt in listOf(999L, 9000L)) {
            val recovery = WaypointRecentRecovery()
            recovery.observe(final, listOf(missing), emptyList(), 1000)
            assertFalse(recovery.receive(listOf(recent), receivedAt))
        }
        val recovery = WaypointRecentRecovery()
        recovery.observe(final, listOf(missing), emptyList(), 1000)
        recovery.clear()
        assertFalse(recovery.receive(listOf(recent), 1100))
        recovery.observe(final, listOf(missing), emptyList(), 1200)
        assertTrue(recovery.receive(listOf(recent), 1300))
        recovery.observe(final.copy(latitude = 37.6), emptyList(), emptyList(), 1400)
        assertTrue(recovery.recovered.isEmpty())
    }

    @Test fun validSdkCoordinatesAndKnownRequestDoNotTriggerRecentLookup() {
        val recovery = WaypointRecentRecovery()
        assertFalse(recovery.observe(final, listOf(missing.copy(place = recent.place)), emptyList(), 1000))
        assertFalse(recovery.observe(final, listOf(missing), listOf(recent), 1100))
    }
}
