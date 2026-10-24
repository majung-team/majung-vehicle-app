package app.majung.vehicle

import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.helper.NaviHelper
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Button
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.net.URI
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Real SDK probe: a response without matching coordinates must never create a pickup. */
class WaypointPickupTest {
    @Test fun verifyWaypointIdentityBeforePickupCreation() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("verifyWaypoint") == "true")
        assertTrue(Build.MODEL.contains("emulator", true))
        val main = InstrumentationRegistry.getInstrumentation()
        val app = ApplicationProvider.getApplicationContext<VehicleApplication>()
        val api = PickupApiClient()
        var control: NaviHelper? = null
        var connected = false
        var routeStarts = 0
        var selectedSdkPlace: SdkPickupPlace? = null
        val useIdentity = InstrumentationRegistry.getArguments().getString("withoutWaypointIdentity") != "true"
        val report = JSONObject()
        val counts = mutableMapOf<SdkTimingType,Int>()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            try {
                main.runOnMainSync {
                    app.destination.timingObserver = { counts[it.type] = (counts[it.type] ?: 0) + 1 }
                    control = NaviHelper(app)
                    control!!.initialize(onConnected = { Handler(Looper.getMainLooper()).post { connected = true } }, onFailed = {})
                }
                await { connected && !app.pickup.busy }
                main.runOnMainSync {
                    control!!.addListener(object : NaviHelperEventListener {
                        override fun onRouteStarted(routeStartInfo: RouteStartInfo) {
                            Handler(Looper.getMainLooper()).post { routeStarts++ }
                        }
                    })
                    if (app.pickup.canCancel) app.pickup.cancel()
                }
                await { !app.pickup.busy }
                main.runOnMainSync {
                    app.pickup.prepareNextPickup()
                    app.destination.useFinalDestination()
                    app.destination.connect()
                    control!!.requestRoute(RouteInfo(latitude = 37.48544722, longitude = 127.03636666,
                        poiName = "42dot", poiId = "36khp37hh8gqcm5nqhe5", address = "서울시 강남구 남부순환로 2621", poiSubId = "0"))
                }
                await { routeStarts > 0 }
                main.runOnMainSync {
                    app.startActivity(android.content.Intent(app, MainActivity::class.java)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                }
                scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
                main.runOnMainSync { app.destination.connect() }
                await { app.pickup.canCreate && app.destination.currentStops()?.waypoints?.isEmpty() == true &&
                    app.destination.selection.current(SystemClock.elapsedRealtime()) != null }
                main.runOnMainSync {
                    app.destination.loadRecentPlaces { places ->
                        selectedSdkPlace = places.firstOrNull { it.poiId.isNotBlank() }
                    }
                }
                await { selectedSdkPlace != null }
                val requested = selectedSdkPlace!!.place
                var routeBeforeFinal = 0
                main.runOnMainSync {
                    routeBeforeFinal = routeStarts
                    control!!.requestRoute(RouteInfo(latitude = 37.566535, longitude = 126.977969,
                        poiName = "SDK 최종 목적지", poiId = "", address = "", poiSubId = "0"))
                }
                await { routeStarts > routeBeforeFinal }
                main.runOnMainSync {
                    app.startActivity(android.content.Intent(app, MainActivity::class.java)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                }
                scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
                main.runOnMainSync { app.destination.connect(); app.destination.query() }
                await { app.destination.currentStops()?.let { stops ->
                    stops.waypoints.isEmpty() && kotlin.math.abs(stops.destination.place.latitude - 37.566535) < 0.000001
                } == true }
                var original: PickupPlace? = null
                main.runOnMainSync {
                    original = app.destination.currentStops()!!.destination.place
                    assertTrue(app.destination.addPickupWaypoint(if (useIdentity) selectedSdkPlace!!
                        else selectedSdkPlace!!.copy(poiId = "", address = "", poiSubId = "0")))
                    assertNull(app.destination.selection.current(SystemClock.elapsedRealtime()))
                }
                // The emulator does not reliably emit route-start for an added waypoint.
                // This delay only lets its foreground navigation screen open; readiness
                // still requires the reader's two validated SDK responses.
                SystemClock.sleep(3000)
                main.runOnMainSync {
                    app.startActivity(android.content.Intent(app, MainActivity::class.java)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                }
                scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
                main.runOnMainSync { app.destination.connect() }
                await { app.destination.currentStops()?.waypoints?.isNotEmpty() == true ||
                    app.destination.failure == NavigationFailure.WAYPOINT_COORDINATES_UNAVAILABLE }
                var current: NavigationStops? = null
                main.runOnMainSync { current = app.destination.currentStops() }
                if (current != null) {
                    assertTrue("SDK replaced the final destination", current!!.destination.place.sameCoordinates(original!!))
                    report.put("finalDestinationPreserved", true)
                }
                val matched = current?.resolve(requested)?.kind == NavigationStopKind.WAYPOINT
                assertEquals("Unexpected SDK waypoint identity result", useIdentity, matched)
                report.put("resolvedPickupCoordinatesMatched", matched)
                    .put("failureCode", app.destination.failure?.name)
                if (matched) {
                    main.runOnMainSync { assertNotNull(app.destination.selection.current(SystemClock.elapsedRealtime())) }
                    report.put("sdkOutcome", "SDK_POI_IDENTITY_MATCHED")
                        .put("finalEtaSeconds", current!!.destination.durationSeconds)
                        .put("waypointEtaSeconds", current!!.resolve(requested)!!.durationSeconds)
                } else {
                    assertEquals(NavigationFailure.WAYPOINT_COORDINATES_UNAVAILABLE, app.destination.failure)
                    assertFalse(app.destination.addingWaypoint)
                    scenario.onActivity {
                        assertFalse(it.findViewById<Button>(R.id.create_pickup).isEnabled)
                        assertNull(app.destination.selection.current(SystemClock.elapsedRealtime()))
                        assertNull(app.pickup.pickupId)
                    }
                    capture(app, "waypoint-unverified.png")
                    report.put("sdkOutcome", "COORDINATES_UNAVAILABLE")
                        .put("unverifiedCreationBlocked", true)
                    // Switching back is an explicit user action, not an automatic ETA fallback.
                    main.runOnMainSync { app.destination.useFinalDestination() }
                    await { app.destination.selection.current(SystemClock.elapsedRealtime())?.sameCoordinates(original!!) == true }
                }
                await {
                    var enabled = false
                    scenario.onActivity { enabled = it.findViewById<Button>(R.id.create_pickup).isEnabled }
                    enabled
                }
                scenario.onActivity { assertTrue(it.findViewById<Button>(R.id.create_pickup).isEnabled) }
                capture(app, "waypoint-selection.png")
                var selected: PickupPlace? = null
                scenario.onActivity {
                    selected = app.destination.selection.current(SystemClock.elapsedRealtime())!!
                    it.findViewById<Button>(R.id.create_pickup).performClick()
                }
                await { app.pickup.pickupId != null && !app.pickup.busy }
                var invitation: String? = null
                main.runOnMainSync { invitation = app.pickup.invitationForHostBrowser() }
                val parts = URI(invitation!!).fragment.split('/')
                val id = parts[1]
                val guest = api.request("POST", "/$id/participants", body = JSONObject()
                    .put("inviteToken", parts[2]).put("displayName", "경유지 확인"))
                val token = guest.getString("participantToken")
                main.runOnMainSync { app.pickup.refresh() }
                await { app.pickup.canApprove }
                main.runOnMainSync { app.pickup.approve() }
                await { app.sharingRunning }
                var live: JSONObject? = null
                val until = SystemClock.elapsedRealtime() + 30000
                while (SystemClock.elapsedRealtime() < until) {
                    val value = api.request("GET", "/$id/vehicle-state", token)
                    if (value.optString("freshness") == "FRESH") { live = value; break }
                    SystemClock.sleep(1000)
                }
                assertNotNull("Verified stop did not publish", live)
                main.runOnMainSync {
                    val stop = app.destination.currentStops()!!.resolve(selected!!)!!
                    assertEquals(stop.durationSeconds, live!!.getLong("durationSeconds"))
                    report.put("sharedEtaSeconds", live!!.getLong("durationSeconds"))
                        .put("sharedTarget", stop.kind.name)
                }
                report.put("result", "passed")
            } finally {
                main.runOnMainSync {
                    report.put("counts", JSONObject(counts.mapKeys { it.key.name }))
                    report.put("readerStatus", app.destination.status)
                    app.destination.timingObserver = null
                }
                File(app.filesDir, "waypoint-pickup-summary.json").writeText(report.toString(2))
                main.runOnMainSync {
                    control?.release()
                    if (app.pickup.canCancel) app.pickup.cancel()
                }
                val until = SystemClock.elapsedRealtime() + 10000
                while (app.sharingRunning && SystemClock.elapsedRealtime() < until) SystemClock.sleep(100)
                main.runOnMainSync {
                    app.stopService(android.content.Intent(app, PickupSharingService::class.java))
                    app.destination.disconnect()
                }
            }
        }
    }

    private fun capture(app: VehicleApplication, name: String) {
        if (InstrumentationRegistry.getArguments().getString("captureEvidence") != "true") return
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()!!
        File(app.filesDir, name).outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }

    private fun await(condition: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + 40000
        while (SystemClock.elapsedRealtime() < until) {
            var done = false
            InstrumentationRegistry.getInstrumentation().runOnMainSync { done = condition() }
            if (done) return
            SystemClock.sleep(100)
        }
        throw AssertionError("Waypoint setup did not reach expected state")
    }
}
