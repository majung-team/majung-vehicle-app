package app.majung.vehicle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationPlaceIdentityTest {
    private val place = SdkPickupPlace(PickupPlace("같은 이름", 37.4, 127.1), "", "", "0")

    @Test fun missingProviderIdentityGetsUniqueAppNamespaceWithoutChangingCoordinates() {
        val first = place.withRequestIdentity()
        val second = place.withRequestIdentity()
        assertTrue(first.poiId.startsWith("majung:"))
        assertEquals("majung", first.poiProvider)
        assertNotEquals(first.poiId, second.poiId)
        assertEquals(place.place, first.place)
        assertEquals(first, first.withRequestIdentity())
    }

    @Test fun realProviderIdentityIsNeverReplaced() {
        val known = place.copy(poiId = "provider-id", poiProvider = "provider")
        assertEquals(known, known.withRequestIdentity())
    }

    @Test fun idlessPlaceOnlyRecoversWhenSdkEchoesTheExactIssuedIdentity() {
        val issued = place.withRequestIdentity()
        val zero = PickupPlace("같은 이름", 0.0, 0.0)
        val matched = WaypointCoordinates.resolve(listOf(SdkWaypoint(zero, 420, issued.poiId)), issued).single()
        assertEquals(place.place, matched.place)
        assertEquals(420L, matched.durationSeconds)
        listOf("", "majung:other").forEach { id ->
            assertFalse(WaypointCoordinates.resolve(listOf(SdkWaypoint(zero, 420, id)), issued).single().usable)
        }
        assertFalse(WaypointCoordinates.resolve(listOf(SdkWaypoint(zero, 420, issued.poiId),
            SdkWaypoint(zero, 900, issued.poiId)), issued).any { it.usable })
    }

    @Test fun multipleOwnedWaypointsKeepTheirOwnCoordinatesAndEtasAfterReordering() {
        val first = place.withRequestIdentity()
        val second = place.copy(place = place.place.copy(latitude = 37.5)).withRequestIdentity()
        val zero = PickupPlace("같은 이름", 0.0, 0.0)
        val result = WaypointCoordinates.resolveKnown(listOf(SdkWaypoint(zero, 900, second.poiId),
            SdkWaypoint(zero, 420, first.poiId)), listOf(first, second))
        assertEquals(second.place, result[0].place)
        assertEquals(900L, result[0].durationSeconds)
        assertEquals(first.place, result[1].place)
        assertEquals(420L, result[1].durationSeconds)
    }
}
