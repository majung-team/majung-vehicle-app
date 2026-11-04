package app.majung.vehicle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class WaypointCoordinatesTest {
    private val selected = SdkPickupPlace(PickupPlace("선택한 장소", 37.4, 127.1), "sdk-poi", "", "0")
    private fun missing(id: String, seconds: Long = 420) = SdkWaypoint(PickupPlace("응답 이름", 0.0, 0.0), seconds, id)

    @Test fun exactUniqueSdkIdentityRecoversCoordinatesAndUsesWaypointEta() {
        val other = SdkWaypoint(PickupPlace("다른 경유지", 37.5, 127.2), 900, "other")
        val result = WaypointCoordinates.resolve(listOf(other, missing("sdk-poi")), selected)
        assertEquals(other.place, result[0].place)
        assertEquals(selected.place, result[1].place)
        assertEquals(420L, result[1].durationSeconds)
        assertEquals(NavigationStopKind.WAYPOINT, result[1].kind)
    }

    @Test fun missingDifferentOrDuplicateIdentityCannotRecoverCoordinates() {
        listOf(listOf(missing("")), listOf(missing("different")),
            listOf(missing("sdk-poi"), missing("sdk-poi"))).forEach { points ->
            WaypointCoordinates.resolve(points, selected).forEach { assertFalse(it.usable) }
        }
        assertFalse(WaypointCoordinates.resolve(listOf(missing("sdk-poi")), null).single().usable)
        assertFalse(WaypointCoordinates.resolve(listOf(missing("")), selected.copy(poiId = "")).single().usable)
    }

    @Test fun validDifferentCoordinatesAreNeverOverwrittenAndInvalidSourceCannotRecover() {
        val actual = SdkWaypoint(PickupPlace("이동된 장소", 37.6, 127.3), 420, "sdk-poi")
        assertEquals(actual.place, WaypointCoordinates.resolve(listOf(actual), selected).single().place)
        val invalid = selected.copy(place = selected.place.copy(latitude = Double.NaN))
        assertFalse(WaypointCoordinates.resolve(listOf(missing("sdk-poi")), invalid).single().usable)
        assertFalse(WaypointCoordinates.resolve(listOf(missing("sdk-poi", -1)), selected).single().usable)
    }
}
