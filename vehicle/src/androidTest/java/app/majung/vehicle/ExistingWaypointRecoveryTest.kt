package app.majung.vehicle

import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.RequestWaypointInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteWithWaypointsRequest
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.pleos.playground.navi.data.WaypointInfo
import ai.pleos.playground.navi.data.RecentDestinationInfo
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Synthetic SDK routes on a separate AVD. No production API, account or raw payload log. */
class ExistingWaypointRecoveryTest {
    @Test fun recoversExistingIdentityThenExplicitlyReselectsAnIdlessStop() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("existingWaypointRecovery") == "true")
        assumeTrue(BuildConfig.DEBUG && BuildConfig.PICKUP_API == "http://10.0.2.2:8080")
        val main = InstrumentationRegistry.getInstrumentation()
        val app = ApplicationProvider.getApplicationContext<VehicleApplication>()
        val reader = DestinationReader(app)
        var sdk: NaviHelper? = null
        var connected = false
        val destination = PickupPlace("SDK 시험 목적지", 37.566535, 126.977969)
        val stop = SdkPickupPlace(PickupPlace("SDK 시험 경유지", 37.5, 127.03), "", "", "0").withRequestIdentity()
        val report = JSONObject()
        fun await(label: String, condition: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + 65000
            while (SystemClock.elapsedRealtime() < deadline) {
                var done = false
                main.runOnMainSync { reader.prepareForPickup(); done = condition() }
                if (done) return
                SystemClock.sleep(100)
            }
            main.sendStatus(0, Bundle().apply { putString("phase", label); putString("failure", reader.failure?.name ?: "none"); putBoolean("guidanceMissing", reader.waitingForGuidance) })
            throw AssertionError("Existing waypoint recovery timed out: $label")
        }
        fun request(id: String, provider: String) {
            sdk!!.requestRouteWithWaypoints(RouteWithWaypointsRequest(
                RouteInfo(destination.latitude, destination.longitude, poiName = destination.label, poiId = "", address = "", poiSubId = "0"),
                listOf(RequestWaypointInfo(stop.place.latitude, stop.place.longitude, stop.place.label,
                    WaypointIndex.FIRST, id, "", "0", poiProvider = provider))))
        }
        try {
            main.runOnMainSync {
                sdk = NaviHelper(app).also { it.initialize(onConnected = {
                    Handler(Looper.getMainLooper()).post { connected = true }
                }, onFailed = {}) }
                reader.connect()
            }
            await("connection") { connected }
            main.runOnMainSync {
                sdk!!.addListener(object : NaviHelperEventListener {
                    override fun onWayPointInfo(waypointInfoList: List<WaypointInfo>) {
                        main.sendStatus(0, Bundle().apply {
                            putInt("rawCount", waypointInfoList.size)
                            putInt("rawIdentityMatches", waypointInfoList.count { it.poiId == stop.poiId })
                            putInt("rawProviderMatches", waypointInfoList.count { it.poiProvider == "majung" })
                            putInt("rawSubIdMatches", waypointInfoList.count { it.poiSubId == "0" })
                        })
                    }
                    override fun onRecentDestinationInfo(recentDestinationInfoList: List<RecentDestinationInfo>) {
                        main.sendStatus(0, Bundle().apply {
                            putInt("recentCount", recentDestinationInfoList.size)
                            putInt("recentIdentityMatches", recentDestinationInfoList.count { it.poiId == stop.poiId })
                            putInt("recentProviderMatches", recentDestinationInfoList.count { it.poiId == stop.poiId && it.poiProvider == "majung" })
                            putInt("recentSubIdMatches", recentDestinationInfoList.count { it.poiId == stop.poiId && it.poiSubId == "0" })
                        })
                    }
                })
                sdk!!.requestRoute(RouteInfo(destination.latitude, destination.longitude, poiName = destination.label, poiId = "", address = "", poiSubId = "0"))
            }
            await("initial guidance") { reader.currentStops()?.destination?.place?.sameCoordinates(destination) == true }
            main.runOnMainSync {
                sdk!!.addWaypoint(RequestWaypointInfo(stop.place.latitude, stop.place.longitude, stop.place.label,
                    WaypointIndex.FIRST, stop.poiId, "", "0", poiProvider = "majung"))
            }
            await("recent identity") { reader.currentStops()?.resolve(stop.place) != null }
            main.runOnMainSync {
                assertEquals(stop.poiId, reader.referenceFor(stop.place))
                assertTrue(reader.currentStops()!!.destination.place.sameCoordinates(destination))
                assertTrue(reader.selectVerifiedStop(stop.place))
                assertEquals(stop.place.latitude, reader.selection.current(SystemClock.elapsedRealtime())!!.latitude, 0.000001)
                report.put("existingWaypointRecoveredWithoutSavedRequest", true)
                reader.useFinalDestination()
                request("", "")
            }
            await("unknown waypoint") { reader.currentStops()?.waypoints?.singleOrNull()?.usable == false }
            lateinit var previous: NavigationStops
            main.runOnMainSync {
                previous = reader.currentStops()!!
                assertTrue(previous.destination.place.sameCoordinates(destination))
                assertFalse(previous.waypoints.single().usable)
                assertTrue(reader.reselectUnverifiedWaypoints(previous, mapOf(0 to stop), 0))
            }
            await("explicit re-selection") { reader.currentStops()?.resolve(stop.place) != null && !reader.addingWaypoint }
            main.runOnMainSync {
                assertTrue(reader.currentStops()!!.destination.place.sameCoordinates(destination))
                assertEquals(1, reader.currentStops()!!.waypoints.size)
                assertEquals(NavigationStopKind.WAYPOINT, reader.currentStops()!!.resolve(stop.place)!!.kind)
                report.put("explicitReselectionConfirmed", true).put("finalDestinationPreserved", true)
                    .put("waypointCountPreserved", true).put("result", "passed")
            }
            val second = PickupPlace("두 번째 시험 경유지", 37.48, 127.01)
            main.runOnMainSync {
                reader.useFinalDestination()
                sdk!!.requestRouteWithWaypoints(RouteWithWaypointsRequest(
                    RouteInfo(destination.latitude, destination.longitude, poiName = destination.label, poiId = "", address = "", poiSubId = "0"),
                    listOf(RequestWaypointInfo(stop.place.latitude, stop.place.longitude, stop.place.label, WaypointIndex.FIRST, "", "", "0"),
                        RequestWaypointInfo(second.latitude, second.longitude, second.label, WaypointIndex.SECOND, "", "", "0"))))
            }
            await("two unknown stops") { reader.currentStops()?.waypoints?.let { it.size == 2 && it.none { point -> point.usable } } == true }
            main.runOnMainSync {
                previous = reader.currentStops()!!
                assertTrue(reader.reselectUnverifiedWaypoints(previous, mapOf(0 to stop, 1 to SdkPickupPlace(second, "", "", "0")), 0))
            }
            await("two reselected stops") { reader.currentStops()?.let { it.resolve(stop.place) != null && it.resolve(second) != null } == true }
            main.runOnMainSync {
                val route = reader.currentStops()!!
                assertTrue(route.destination.place.sameCoordinates(destination))
                assertTrue(route.waypoints[0].place.sameCoordinates(stop.place))
                assertTrue(route.waypoints[1].place.sameCoordinates(second))
                report.put("twoUnknownStopsExplicitlyReselectedInOrder", true)
            }
            File(app.filesDir, "existing-waypoint-recovery.json").writeText(report.toString(2))
        } finally {
            main.runOnMainSync { reader.disconnect(); sdk?.release() }
        }
    }
}
