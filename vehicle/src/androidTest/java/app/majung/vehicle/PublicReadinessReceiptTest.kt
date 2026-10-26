package app.majung.vehicle

import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Explicit HTTPS check using a disposable fixture. Never copies the installed driver's credentials. */
class PublicReadinessReceiptTest {
    @Test fun confirmsPreparedStateThroughPublicServer() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("publicReadiness") == "true")
        val app = ApplicationProvider.getApplicationContext<VehicleApplication>()
        val input = File(app.filesDir, "public-readiness-session.json")
        val main = InstrumentationRegistry.getInstrumentation()
        try {
            val data = JSONObject(input.readText())
            val id = data.getString("pickupId")
            val owner = data.getString("driverToken")
            val store = MemoryPickupSessionStore().apply { save(SavedPickupSession(id, owner, null, 0)) }
            lateinit var controller: PickupController
            main.runOnMainSync {
                controller = PickupController(PickupApiClient(), store)
                controller.restoreSession()
            }
            val until = SystemClock.elapsedRealtime() + 15000
            var received = false
            while (SystemClock.elapsedRealtime() < until) {
                val snapshot = PickupApiClient().request("GET", "/$id", owner)
                val participant = snapshot.getJSONObject("participant")
                if (participant.getLong("readinessSequence") > 0 &&
                    participant.getLong("readinessSequence") == participant.getLong("readinessReceivedSequence")) {
                    received = true
                    break
                }
                SystemClock.sleep(100)
            }
            assertEquals(true, received)
            main.runOnMainSync { assertEquals("READY", controller.readiness) }
            File(app.filesDir, "public-readiness-summary.json").writeText(JSONObject()
                .put("publicHttps", true).put("vehicleController", true).put("readyReceived", true)
                .put("physicalCar", false).put("driverRead", false).put("result", "passed").toString(2))
        } finally { input.delete() }
    }
}
