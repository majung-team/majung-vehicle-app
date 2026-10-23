package app.majung.vehicle

import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.time.Instant
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in two-process test against the local API. Telemetry here is a synthetic fixture. */
class PickupProcessRecoveryTest {
    @Test fun preserveSessionAcrossForceStop() {
        val main = InstrumentationRegistry.getInstrumentation()
        val phase = InstrumentationRegistry.getArguments().getString("recoveryPhase")
        assumeTrue(phase == "prepare" || phase == "verify")
        val app = ApplicationProvider.getApplicationContext<VehicleApplication>()
        val store = EncryptedPickupSessionStore(app)
        val api = PickupApiClient()
        if (phase == "prepare") {
            await { app.pickup.canCreate }
            val pickup = api.create(PickupPlace("프로세스 복구 시험", 37.0, 127.0))
            val id = pickup.getString("pickupId")
            val owner = pickup.getString("driverToken")
            try {
                val guest = api.request("POST", "/$id/participants", body = JSONObject()
                    .put("inviteToken", pickup.getString("inviteToken")).put("displayName", "복구 시험"))
                api.request("POST", "/$id/participants/${guest.getString("participantId")}/approval", owner)
                api.request("PUT", "/$id/vehicle-state", owner, JSONObject().put("sequence", 500)
                    .put("latitude", 37.0).put("longitude", 127.0).put("durationSeconds", 120)
                    .put("sourceTime", Instant.now().toString()))
                store.save(SavedPickupSession(id, owner, null, 500))
                assertTrue(store.load()?.pickupId == id)
            } catch (error: Exception) {
                runCatching { api.request("POST", "/$id/cancellation", owner) }
                store.clear()
                throw error
            }
            return
        }
        // Application.onCreate automatically loads and verifies the stored session in this new process.
        await { app.pickup.online && app.pickup.status == "ACTIVE" }
        val saved = store.load() ?: throw AssertionError("Stored session missing")
        try {
            main.runOnMainSync {
                val now = SystemClock.elapsedRealtime()
                app.destination.telemetry.location(37.0, 127.0, now, Instant.now())
                app.destination.telemetry.destination(app.pickup.place!!, 110, now, Instant.now())
                app.pickup.syncVehicle(app.destination)
            }
            await { app.pickup.vehicleMessage == "SDK 위치·ETA를 서버에 전달했어요" }
            assertTrue(api.request("GET", "/${saved.pickupId}/vehicle-state", saved.driverToken).getLong("sequence") == 501L)
            assertTrue(store.load()?.vehicleSequence == 501L)
            main.runOnMainSync { app.pickup.arrive() }
            await { app.pickup.status == "ARRIVED" && !app.pickup.busy }
            main.runOnMainSync { app.pickup.complete() }
            await { app.pickup.status == "COMPLETED" && !app.pickup.busy }
            assertTrue(store.load() == null)
        } finally {
            runCatching { api.request("POST", "/${saved.pickupId}/cancellation", saved.driverToken) }
            store.clear()
        }
    }

    private fun await(condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 15000
        while (SystemClock.elapsedRealtime() < end) {
            var ready = false
            InstrumentationRegistry.getInstrumentation().runOnMainSync { ready = condition() }
            if (ready) return
            SystemClock.sleep(100)
        }
        throw AssertionError("Process recovery did not reach expected state")
    }
}
