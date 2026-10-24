package app.majung.vehicle

import android.os.SystemClock
import android.widget.Button
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Run after the real SDK pickup prepare phase and adb force-stop. No synthetic telemetry. */
class WaypointProcessRecoveryTest {
    @Test fun restoreIdlessWaypointAfterProcessRestart() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("verifyWaypointRestart") == "true")
        val main = InstrumentationRegistry.getInstrumentation()
        val app = ApplicationProvider.getApplicationContext<VehicleApplication>()
        val api = PickupApiClient()
        val store = EncryptedPickupSessionStore(app)
        val saved = store.load() ?: error("Prepare an active waypoint pickup first")
        val report = JSONObject()
        try {
            assertTrue(saved.navigationReference?.startsWith("majung:") == true)
            await { app.pickup.online && app.pickup.status == "ACTIVE" }
            assertEquals(saved.navigationReference, app.pickup.navigationReference)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity {
                    assertTrue(app.pickup.needsSharingConfirmation && !app.sharingRunning)
                    assertTrue(it.findViewById<Button>(R.id.resume_pickup).isEnabled)
                    it.findViewById<Button>(R.id.resume_pickup).performClick()
                }
                await { app.sharingRunning }
                var live: JSONObject? = null
                val deadline = SystemClock.elapsedRealtime() + 35000
                while (SystemClock.elapsedRealtime() < deadline) {
                    val state = api.request("GET", "/${saved.pickupId}/vehicle-state", saved.driverToken)
                    if (state.optString("freshness") == "FRESH" && state.getLong("sequence") > saved.vehicleSequence) {
                        live = state
                        break
                    }
                    SystemClock.sleep(500)
                }
                checkNotNull(live) { "No new real SDK telemetry after process restart" }
                main.runOnMainSync {
                    val confirmed = app.pickup.place!!
                    val stops = app.destination.currentStops()!!
                    val waypoint = stops.resolve(confirmed)!!
                    assertEquals(NavigationStopKind.WAYPOINT, waypoint.kind)
                    assertEquals(waypoint.durationSeconds, live!!.getLong("durationSeconds"))
                    assertTrue(stops.destination.durationSeconds != waypoint.durationSeconds)
                    report.put("sharedTarget", waypoint.kind.name)
                        .put("sharedEtaSeconds", live!!.getLong("durationSeconds"))
                        .put("finalEtaSeconds", stops.destination.durationSeconds)
                }
            }
            report.put("newProcessRestoredIdentity", true)
                .put("sharingRequiredUserConfirmation", true)
                .put("sequenceAdvanced", true).put("result", "passed")
        } finally {
            File(app.filesDir, "waypoint-process-recovery.json").writeText(report.toString(2))
            main.runOnMainSync { if (app.pickup.canCancel) app.pickup.cancel() }
            await { !app.sharingRunning }
            store.clear()
        }
    }

    private fun await(condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 20000
        while (SystemClock.elapsedRealtime() < end) {
            var ready = false
            InstrumentationRegistry.getInstrumentation().runOnMainSync { ready = condition() }
            if (ready) return
            SystemClock.sleep(100)
        }
        throw AssertionError("Waypoint process recovery did not reach expected state")
    }
}
