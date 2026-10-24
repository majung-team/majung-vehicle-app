package app.majung.vehicle

import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.RecentDestinationInfo
import ai.pleos.playground.navi.data.RequestWaypointInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteWithWaypointsRequest
import ai.pleos.playground.navi.data.WaypointInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import java.io.File

class WaypointSdkProbeTest {
    @Test fun inspectWaypointSupport() {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("probeWaypoint") == "true")
        org.junit.Assert.assertTrue(Build.MODEL.contains("emulator", true))
        val main = InstrumentationRegistry.getInstrumentation()
        val app = ApplicationProvider.getApplicationContext<VehicleApplication>()
        var helper: NaviHelper? = null
        var connected = false
        val rows = JSONArray()
        val requestMode = InstrumentationRegistry.getArguments().getString("waypointRequestMode") ?: "add"
        require(requestMode in setOf("add", "route"))
        var phase = "before"
        var recentPoi: RecentDestinationInfo? = null
        val useRecentPoi = InstrumentationRegistry.getArguments().getString("useRecentPoi") == "true"
        val useAppIdentity = InstrumentationRegistry.getArguments().getString("useAppIdentity") == "true"
        var requestedId = ""
        var requestedLatitude = 37.48544722
        var requestedLongitude = 127.03636666
        var originalDestination: Pair<Double,Double>? = null
        ActivityScenario.launch(MainActivity::class.java).use {
            try {
                main.runOnMainSync {
                    helper = NaviHelper(app)
                    helper!!.initialize(onConnected = { Handler(Looper.getMainLooper()).post { connected = true } }, onFailed = {})
                }
                val end = SystemClock.elapsedRealtime() + 15000
                while (!connected && SystemClock.elapsedRealtime() < end) SystemClock.sleep(100)
                check(connected)
                main.runOnMainSync {
                    helper!!.addListener(object : NaviHelperEventListener {
                        override fun onWayPointInfo(waypointInfoList: List<WaypointInfo>) {
                            rows.put(JSONObject().put("phase", phase).put("event", "waypoints").put("count", waypointInfoList.size)
                                .put("requestedPlaceMatches", waypointInfoList.count { kotlin.math.abs(it.latitude - requestedLatitude) < 0.000001 && kotlin.math.abs(it.longitude - requestedLongitude) < 0.000001 })
                                .put("requestedPoiIdMatches", if (requestedId.isNotBlank()) waypointInfoList.count { it.poiId == requestedId } else 0)
                                .put("responseIdentifiedPlaces", waypointInfoList.count { it.poiId.isNotBlank() })
                                .put("zeroCoordinates", waypointInfoList.count { it.latitude == 0.0 && it.longitude == 0.0 })
                                .put("displayMatches", waypointInfoList.count { it.displayLatitude?.let { v -> kotlin.math.abs(v - requestedLatitude) < 0.000001 } == true && it.displayLongitude?.let { v -> kotlin.math.abs(v - requestedLongitude) < 0.000001 } == true })
                                .put("coordinateDifferenceMeters", JSONArray(waypointInfoList.map { kotlin.math.hypot((it.latitude - requestedLatitude)*111000, (it.longitude - requestedLongitude)*88000).toInt() }))
                                .put("seconds", JSONArray(waypointInfoList.map { it.remainTime })))
                        }
                        override fun onRecentDestinationInfo(recentDestinationInfoList: List<RecentDestinationInfo>) {
                            if (recentPoi == null) recentPoi = recentDestinationInfoList.firstOrNull {
                                it.poiId.isNotBlank() && it.latitude != 0.0 && it.longitude != 0.0
                            }
                            rows.put(JSONObject().put("phase", phase).put("event", "recent").put("count", recentDestinationInfoList.size)
                                .put("identifiedPlaceCount", recentDestinationInfoList.count { it.poiId.isNotBlank() })
                                .put("requestedIdentityAndCoordinatesMatch", requestedId.isNotBlank() && recentDestinationInfoList.any {
                                    it.poiId == requestedId && kotlin.math.abs(it.latitude - requestedLatitude) < 0.000001 &&
                                        kotlin.math.abs(it.longitude - requestedLongitude) < 0.000001
                                }))
                        }
                        override fun onDestinationInfo(destinationInfo: DestinationInfo) {
                            if (phase == "before" && destinationInfo.remainTime > 0) originalDestination = destinationInfo.latitude to destinationInfo.longitude
                            rows.put(JSONObject().put("phase", phase).put("event", "destination").put("seconds", destinationInfo.remainTime)
                                .put("originalDestinationPreserved", originalDestination?.let { kotlin.math.abs(it.first - destinationInfo.latitude) < 0.000001 && kotlin.math.abs(it.second - destinationInfo.longitude) < 0.000001 }))
                        }
                    })
                    helper!!.requestRoute(RouteInfo(latitude=37.41289678, longitude=127.09383464, poiName="SDK 종점", poiId="", address="", poiSubId="0"))
                }
                repeat(12) { index ->
                    main.runOnMainSync {
                        if (index == 5) {
                            phase = "after_add"
                            val candidate = if (useRecentPoi) requireNotNull(recentPoi) { "No SDK place with POI identity available" } else null
                            requestedLatitude = candidate?.latitude ?: 37.48544722
                            requestedLongitude = candidate?.longitude ?: 127.03636666
                            requestedId = if (useAppIdentity) "majung:" + java.util.UUID.randomUUID() else candidate?.poiId ?: ""
                            val request = RequestWaypointInfo(requestedLatitude, requestedLongitude,
                                candidate?.name ?: "SDK 픽업", WaypointIndex.FIRST,
                                requestedId, candidate?.address ?: "", candidate?.poiSubId ?: "0",
                                poiProvider = if (useAppIdentity) "majung" else "")
                            if (requestMode == "add") helper!!.addWaypoint(request)
                            else helper!!.requestRouteWithWaypoints(RouteWithWaypointsRequest(
                                destination = RouteInfo(latitude=37.41289678, longitude=127.09383464, poiName="SDK 종점", poiId="", address="", poiSubId="0"),
                                waypoints = listOf(request)))
                        }
                        helper!!.getWaypointInfo(); helper!!.getDestinationInfo(); helper!!.getRecentDestinationInfo()
                    }
                    SystemClock.sleep(3000)
                }
            } finally {
                File(app.filesDir, "waypoint-sdk-probe.json").writeText(JSONObject()
                    .put("requestMode", requestMode).put("usedRecentPoi", useRecentPoi).put("usedAppIdentity", useAppIdentity).put("events", rows).toString(2))
                main.runOnMainSync { helper?.release(); app.destination.disconnect() }
            }
        }
    }
}
