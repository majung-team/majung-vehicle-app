package app.majung.vehicle

import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.net.URI
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test

/** Official emulator SDK -> local API, while Activity is stopped. No synthetic coordinates. */
class PickupSharingServiceTest {
    @Test fun keepSharingForFiveMinutesWithStoppedActivity() {
        val main = InstrumentationRegistry.getInstrumentation()
        val app = ApplicationProvider.getApplicationContext<VehicleApplication>()
        val api = PickupApiClient()
        assertTrue("Grant location permission before this test", PickupSharingService.hasLocationPermission(app))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            main.runOnMainSync { app.pickup.prepareNextPickup(); app.destination.connect() }
            await(20000) { app.pickup.canCreate && app.destination.selection.current(SystemClock.elapsedRealtime()) != null }
            main.runOnMainSync { app.pickup.create(app.destination.selection.current(SystemClock.elapsedRealtime())!!) }
            await { app.pickup.pickupId != null && !app.pickup.busy }
            var invitation: String? = null
            main.runOnMainSync { invitation = app.pickup.invitationForHostBrowser() }
            val parts = URI(invitation!!).fragment.split('/')
            val id = parts[1]
            val guest = api.request("POST", "/$id/participants", body = JSONObject()
                .put("inviteToken", parts[2]).put("displayName", "백그라운드 시험"))
            val token = guest.getString("participantToken")
            try {
                main.runOnMainSync { app.pickup.refresh() }
                await { app.pickup.canApprove }
                assertTrue("Must not start before approval", !app.sharingRunning)
                main.runOnMainSync { app.pickup.approve() }
                await { app.sharingRunning }
                var sequence = awaitFresh(api, id, token).getLong("sequence")
                scenario.moveToState(Lifecycle.State.CREATED)
                val started = SystemClock.elapsedRealtime()
                var updates = 0
                while (SystemClock.elapsedRealtime() - started < 310000) {
                    SystemClock.sleep(5000)
                    val state = api.request("GET", "/$id/vehicle-state", token)
                    assertTrue("Service stopped while Activity was stopped", app.sharingRunning)
                    assertTrue("SDK telemetry became stale", state.optString("freshness") == "FRESH")
                    val next = state.getLong("sequence")
                    if (next > sequence) updates++
                    sequence = next
                }
                assertTrue("No periodic heartbeat while stopped", updates >= 45)
                // Server-side cancellation must also stop the service without reopening the screen.
                val owner = EncryptedPickupSessionStore(app).load()!!
                api.request("POST", "/$id/cancellation", owner.driverToken)
                await { app.pickup.status == "CANCELLED" && !app.sharingRunning }
                assertTrue(EncryptedPickupSessionStore(app).load() == null)
                assertTrue(app.destination.telemetry.current(app.pickup.place ?: PickupPlace("", 0.0, 0.0),
                    SystemClock.elapsedRealtime()) == null)
                main.sendStatus(0, android.os.Bundle().apply {
                    putString("backgroundResult", "Activity stopped: 310s; fresh heartbeat observations: $updates; server cancellation stopped service")
                })
            } finally {
                main.runOnMainSync { app.pickup.cancel() }
                await { !app.sharingRunning }
            }
        }
    }

    private fun awaitFresh(api: PickupApiClient, id: String, token: String): JSONObject {
        val end = SystemClock.elapsedRealtime() + 20000
        while (SystemClock.elapsedRealtime() < end) {
            val state = api.request("GET", "/$id/vehicle-state", token)
            if (state.optString("freshness") == "FRESH") return state
            SystemClock.sleep(250)
        }
        throw AssertionError("No fresh SDK sample")
    }

    private fun await(timeout: Long = 15000, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < end) {
            var ready = false
            InstrumentationRegistry.getInstrumentation().runOnMainSync { ready = condition() }
            if (ready) return
            SystemClock.sleep(100)
        }
        throw AssertionError("Expected sharing state not reached")
    }
}
