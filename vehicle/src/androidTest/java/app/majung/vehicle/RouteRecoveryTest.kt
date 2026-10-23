package app.majung.vehicle

import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.helper.NaviHelper
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.net.URI
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in: real NaviHelper and local API, with the Activity stopped during route changes. */
class RouteRecoveryTest {
    @Test fun recoverRouteWithoutReusingCancelledTelemetry() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("verifyRouteRecovery") == "true")
        val main = InstrumentationRegistry.getInstrumentation()
        val app = ApplicationProvider.getApplicationContext<VehicleApplication>()
        val api = PickupApiClient()
        val counts = mutableMapOf<SdkTimingType, Int>()
        var control: NaviHelper? = null
        var connected = false
        var started = 0
        val report = JSONObject()
        fun phase(value: String) {
            report.put("phase", value)
            main.sendStatus(0, android.os.Bundle().apply { putString("routePhase", value) })
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            try {
                main.runOnMainSync {
                    app.pickup.prepareNextPickup()
                    app.destination.connect()
                    control = NaviHelper(app)
                    control!!.initialize(onConnected = { Handler(Looper.getMainLooper()).post { connected = true } }, onFailed = {})
                    app.destination.timingObserver = { event ->
                        counts[event.type] = (counts[event.type] ?: 0) + 1
                        if (event.type == SdkTimingType.ROUTE_STARTED) started++
                    }
                }
                phase("connect")
                await { connected && !app.pickup.busy }
                main.runOnMainSync {
                    if (app.pickup.canCancel) app.pickup.cancel()
                    control!!.requestRoute(exampleRoute())
                }
                await { !app.pickup.busy }
                main.runOnMainSync { app.pickup.prepareNextPickup() }
                phase("initial_destination")
                await { app.destination.selection.current(SystemClock.elapsedRealtime()) != null && app.pickup.canCreate }
                phase("create")
                main.runOnMainSync { app.pickup.create(app.destination.selection.current(SystemClock.elapsedRealtime())!!) }
                await { app.pickup.pickupId != null && !app.pickup.busy }
                var invite: String? = null
                main.runOnMainSync { invite = app.pickup.invitationForHostBrowser() }
                val parts = URI(invite!!).fragment.split('/')
                val id = parts[1]
                val guest = api.request("POST", "/$id/participants", body = JSONObject()
                    .put("inviteToken", parts[2]).put("displayName", "경로 복구 확인"))
                val token = guest.getString("participantToken")
                main.runOnMainSync { app.pickup.refresh() }
                await { app.pickup.canApprove }
                main.runOnMainSync { app.pickup.approve() }
                phase("sharing_service")
                await { app.sharingRunning }
                fun vehicle(): JSONObject {
                    try { return api.request("GET", "/$id/vehicle-state", token) }
                    catch (error: ApiRejected) { throw AssertionError("Vehicle state HTTP ${error.status}") }
                }
                fun awaitFresh(): Long {
                    val begin = SystemClock.elapsedRealtime()
                    while (SystemClock.elapsedRealtime() - begin < 45000) {
                        if (vehicle().optString("freshness") == "FRESH") return SystemClock.elapsedRealtime() - begin
                        SystemClock.sleep(1000)
                    }
                    throw AssertionError("Validated route did not restore sharing")
                }
                fun awaitHidden() {
                    val begin = SystemClock.elapsedRealtime()
                    while (SystemClock.elapsedRealtime() - begin < 15000) {
                        val state = vehicle()
                        if (state.optString("freshness") == "WAITING" && state.isNull("latitude") && state.isNull("durationSeconds")) return
                        SystemClock.sleep(1000)
                    }
                    throw AssertionError("Invalid route still exposes vehicle data")
                }
                phase("initial_fresh")
                awaitFresh()
                scenario.moveToState(Lifecycle.State.CREATED)
                phase("background_route_request")
                var before = 0
                main.runOnMainSync { before = started; counts.clear(); control!!.requestRoute(exampleRoute()) }
                await { started > before }
                phase("request_hidden")
                awaitHidden()
                phase("request_recovery")
                report.put("backgroundRequestRecoveryMs", awaitFresh())
                main.runOnMainSync {
                    report.put("routingCompletedCallbacks", counts[SdkTimingType.ROUTING_COMPLETED] ?: 0)
                    report.put("rerouteEndedCallbacks", counts[SdkTimingType.REROUTE_ENDED] ?: 0)
                    control!!.cancelRoute()
                }
                phase("cancel_hidden")
                awaitHidden()
                val hiddenUntil = SystemClock.elapsedRealtime() + 12000
                while (SystemClock.elapsedRealtime() < hiddenUntil) {
                    val state = vehicle()
                    assertTrue("Cancelled route revived old position", state.optString("freshness") == "WAITING" && state.isNull("latitude"))
                    SystemClock.sleep(500)
                }
                report.put("cancelledRouteStayedHiddenMs", 12000)
                phase("new_route")
                main.runOnMainSync { control!!.requestRoute(exampleRoute()) }
                report.put("newRouteRecoveryMs", awaitFresh())
                phase("reconnect")
                main.runOnMainSync { app.destination.disconnect() }
                awaitHidden()
                report.put("reconnectRecoveryMs", awaitFresh())
                assertTrue(app.sharingRunning)
                phase("complete")
            } finally {
                main.runOnMainSync {
                    report.put("counts", JSONObject(counts.mapKeys { it.key.name }))
                    report.put("canCreate", app.pickup.canCreate)
                    report.put("selectionAvailable", app.destination.selection.current(SystemClock.elapsedRealtime()) != null)
                    report.put("controllerStatus", app.pickup.status)
                }
                File(app.filesDir, "route-recovery-summary.json").writeText(report.toString(2))
                main.runOnMainSync {
                    app.destination.timingObserver = null
                    control?.release()
                    if (app.pickup.canCancel) app.pickup.cancel()
                }
                val cleanupEnd = SystemClock.elapsedRealtime() + 10000
                while (app.sharingRunning && SystemClock.elapsedRealtime() < cleanupEnd) SystemClock.sleep(100)
                main.runOnMainSync {
                    app.stopService(android.content.Intent(app, PickupSharingService::class.java))
                    app.destination.disconnect()
                }
            }
        }
    }

    private fun exampleRoute() = RouteInfo(latitude = 37.48544722, longitude = 127.03636666,
        poiName = "42dot", poiId = "36khp37hh8gqcm5nqhe5", address = "서울시 강남구 남부순환로 2621", poiSubId = "0")

    private fun await(condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 35000
        while (SystemClock.elapsedRealtime() < end) {
            var ready = false
            InstrumentationRegistry.getInstrumentation().runOnMainSync { ready = condition() }
            if (ready) return
            SystemClock.sleep(100)
        }
        throw AssertionError("Route recovery setup did not reach expected state")
    }
}
