package app.majung.vehicle

import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.helper.NaviHelper
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.net.URI
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in ten-minute run against the real emulator SDK and local API. */
class SdkCallbackTimingTest {
    @Test fun measureRealCallbacksForTenMinutes() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("measureSdkTiming") == "true")
        assertTrue(Build.MODEL.contains("emulator", true))
        val main = InstrumentationRegistry.getInstrumentation()
        val app = ApplicationProvider.getApplicationContext<VehicleApplication>()
        val recorder = SdkTimingRecorder()
        val report = JSONArray()
        val api = PickupApiClient()
        var pickupId: String? = null
        var guestToken: String? = null
        var control: NaviHelper? = null
        var controlConnected = false
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            try {
                assertTrue(PickupSharingService.hasLocationPermission(app))
                main.runOnMainSync { app.pickup.prepareNextPickup(); app.destination.connect() }
                await { app.destination.selection.current(SystemClock.elapsedRealtime()) != null && app.pickup.canCreate }
                main.runOnMainSync {
                    app.destination.timingObserver = recorder::record
                    control = NaviHelper(app)
                    control!!.initialize(
                        onConnected = { Handler(Looper.getMainLooper()).post { controlConnected = true } },
                        onFailed = {}
                    )
                }
                fun observe(name: String, seconds: Int, action: () -> Unit = {}) {
                    var firstSequence: Long? = null
                    var lastSequence: Long? = null
                    var advances = 0
                    val freshness = linkedMapOf<String, Int>()
                    val start = SystemClock.elapsedRealtime()
                    main.runOnMainSync { recorder.begin(start); action() }
                    main.sendStatus(0, android.os.Bundle().apply { putString("timingPhase", name) })
                    while (SystemClock.elapsedRealtime() - start < seconds * 1000L) {
                        SystemClock.sleep(2000)
                        val id = pickupId
                        if (id != null) {
                            val state = api.request("GET", "/$id/vehicle-state", guestToken)
                            val kind = state.optString("freshness", "UNKNOWN")
                            freshness[kind] = (freshness[kind] ?: 0) + 1
                            val seq = state.optLong("sequence", 0)
                            if (firstSequence == null) firstSequence = seq
                            if (lastSequence != null && seq > lastSequence!!) advances++
                            lastSequence = seq
                        }
                    }
                    main.runOnMainSync {
                        report.put(recorder.finish(name, SystemClock.elapsedRealtime())
                            .put("serverObservations", JSONObject(freshness))
                            .put("sequenceDelta", if (firstSequence == null) JSONObject.NULL else lastSequence!! - firstSequence!!)
                            .put("sequenceAdvanceObservations", advances))
                        File(app.filesDir, "sdk-timing-summary.json").writeText(JSONObject()
                            .put("sdk", "NaviHelper 3.0.5").put("phases", report).toString(2))
                    }
                }
                await { controlConnected }
                observe("passive_foreground", 120)
                main.runOnMainSync { app.destination.query() }
                await { app.destination.selection.current(SystemClock.elapsedRealtime()) != null }
                main.runOnMainSync { app.pickup.create(app.destination.selection.current(SystemClock.elapsedRealtime())!!) }
                await { app.pickup.pickupId != null && !app.pickup.busy }
                var invite: String? = null
                main.runOnMainSync { invite = app.pickup.invitationForHostBrowser() }
                val parts = URI(invite!!).fragment.split('/')
                pickupId = parts[1]
                val guest = api.request("POST", "/$pickupId/participants", body = JSONObject()
                    .put("inviteToken", parts[2]).put("displayName", "간격 측정"))
                guestToken = guest.getString("participantToken")
                main.runOnMainSync { app.pickup.refresh() }
                await { app.pickup.status == "ACTIVE" && !app.pickup.busy }
                await { app.sharingRunning }
                observe("polling_foreground", 120)
                scenario.moveToState(Lifecycle.State.CREATED)
                observe("polling_background", 240)
                observe("route_request_background", 60) {
                    // Official SDK example POI, not the user's location. Request only; no extra polling.
                    control!!.requestRoute(RouteInfo(latitude = 37.48544722, longitude = 127.03636666,
                        poiName = "42dot", poiId = "36khp37hh8gqcm5nqhe5", address = "서울시 강남구 남부순환로 2621", poiSubId = "0"))
                }
                // The production service attempts reconnection on its next five-second poll.
                observe("disconnect_reconnect_background", 60) { app.destination.disconnect() }
                assertTrue("Sharing service unexpectedly stopped", app.sharingRunning)
            } finally {
                main.runOnMainSync {
                    app.destination.timingObserver = null
                    control?.release()
                    if (app.pickup.canCancel) app.pickup.cancel()
                }
                await { !app.sharingRunning }
            }
        }
    }

    private fun await(condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 30000
        while (SystemClock.elapsedRealtime() < end) {
            var ready = false
            InstrumentationRegistry.getInstrumentation().runOnMainSync { ready = condition() }
            if (ready) return
            SystemClock.sleep(100)
        }
        throw AssertionError("SDK measurement setup did not reach expected state")
    }
}
