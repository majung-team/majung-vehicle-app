package app.majung.vehicle

import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.RequestWaypointInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteWithWaypointsRequest
import ai.pleos.playground.navi.data.WaypointInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** SDK integration only: synthetic places, no production API or participant tokens. */
class WaypointResponseRegressionTest {
    private val main = InstrumentationRegistry.getInstrumentation()
    private val app = ApplicationProvider.getApplicationContext<VehicleApplication>()

    @Test fun rawWaypointAndResolvedPickupRemainSeparateAcrossReconnect() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("waypointResponse") == "true")
        assumeTrue(BuildConfig.DEBUG && BuildConfig.PICKUP_API == "http://10.0.2.2:8080")
        val reports = JSONArray()
        val final = PickupPlace("시험 최종 목적지", 37.566535, 126.977969)
        val pickup = SdkPickupPlace(PickupPlace("시험 픽업 경유지", 37.5, 127.03), "", "", "0").withRequestIdentity()
        var helper: NaviHelper? = null
        var reader = DestinationReader(app)
        var connected = false
        var raw: List<WaypointInfo>? = null
        var latestRequest = 0L
        fun awaitSdk(label: String, condition: () -> Boolean) {
            val until = SystemClock.elapsedRealtime() + 65_000
            while (SystemClock.elapsedRealtime() < until) {
                var done = false
                main.runOnMainSync {
                    done = condition()
                    val now = SystemClock.elapsedRealtime()
                    if (!done && now - latestRequest >= 5000) {
                        reader.pollTelemetry()
                        helper?.getWaypointInfo()
                        latestRequest = now
                    }
                }
                if (done) return
                SystemClock.sleep(100)
            }
            throw AssertionError("Waypoint SDK recheck timed out: $label")
        }
        try {
            main.runOnMainSync {
                helper = NaviHelper(app).also { sdk ->
                    sdk.initialize(onConnected = { Handler(Looper.getMainLooper()).post { connected = true } }, onFailed = {})
                    sdk.addListener(object : NaviHelperEventListener {
                        override fun onWayPointInfo(waypointInfoList: List<WaypointInfo>) {
                            Handler(Looper.getMainLooper()).post { raw = waypointInfoList.toList() }
                        }
                    })
                }
                reader.connect()
            }
            awaitSdk("connect") { connected }
            for (mode in listOf("addWaypoint", "requestRouteWithWaypoints")) {
                main.runOnMainSync {
                    raw = null
                    helper!!.requestRoute(RouteInfo(final.latitude, final.longitude, poiName = final.label,
                        poiId = "", address = "", poiSubId = "0"))
                }
                awaitSdk("final destination") { reader.currentStops()?.let {
                    it.destination.place.sameCoordinates(final) && it.waypoints.isEmpty()
                } == true }
                main.runOnMainSync {
                    raw = null
                    reader.restorePickupReference(pickup.place, pickup.poiId)
                    val request = RequestWaypointInfo(pickup.place.latitude, pickup.place.longitude, pickup.place.label,
                        WaypointIndex.FIRST, pickup.poiId, "", "0", poiProvider = "majung")
                    if (mode == "addWaypoint") helper!!.addWaypoint(request)
                    else helper!!.requestRouteWithWaypoints(RouteWithWaypointsRequest(
                        RouteInfo(final.latitude, final.longitude, poiName = final.label, poiId = "", address = "", poiSubId = "0"),
                        listOf(request)))
                }
                awaitSdk(mode) {
                    raw?.count { it.poiId == pickup.poiId } == 1 &&
                        reader.currentStops()?.resolve(pickup.place) != null &&
                        reader.telemetry.current(pickup.place, SystemClock.elapsedRealtime()) != null
                }
                lateinit var report: JSONObject
                main.runOnMainSync {
                    val original = raw!!.single { it.poiId == pickup.poiId }
                    val stop = reader.currentStops()!!.resolve(pickup.place)!!
                    assertEquals(NavigationStopKind.WAYPOINT, stop.kind)
                    assertEquals(original.remainTime.toLong(), stop.durationSeconds)
                    assertTrue(reader.currentStops()!!.destination.place.sameCoordinates(final))
                    val missing = original.latitude == 0.0 && original.longitude == 0.0
                    assertTrue("Unexpected nonzero coordinate mismatch", missing ||
                        PickupPlace("SDK", original.latitude, original.longitude).sameCoordinates(pickup.place))
                    report = JSONObject().put("method", mode).put("rawCoordinatesMissing", missing)
                        .put("uniqueRequestIdentityMatched", true).put("resolvedPickupMatched", true)
                        .put("waypointEtaMatchesRaw", true).put("finalDestinationPreserved", true)
                        .put("finalAndWaypointEtaDiffer", reader.currentStops()!!.destination.durationSeconds != stop.durationSeconds)
                    reader.disconnect()
                    reader = DestinationReader(app)
                    reader.restorePickupReference(pickup.place, pickup.poiId)
                    reader.connect()
                }
                awaitSdk("fresh reader") {
                    reader.currentStops()?.resolve(pickup.place)?.kind == NavigationStopKind.WAYPOINT &&
                        reader.telemetry.current(pickup.place, SystemClock.elapsedRealtime()) != null
                }
                report.put("freshReaderRecovered", true)
                reports.put(report)
                main.runOnMainSync { reader.useFinalDestination() }
            }
            File(app.filesDir, "waypoint-response-regression.json").writeText(JSONObject()
                .put("result", "passed").put("NaviHelper", "3.0.5").put("syntheticPlaces", true)
                .put("productionApiUsed", false).put("cases", reports).toString(2))
        } finally {
            main.runOnMainSync { reader.disconnect(); helper?.release() }
        }
    }
}
