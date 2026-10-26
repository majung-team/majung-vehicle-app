package app.majung.vehicle

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.helper.NaviHelper
import java.io.File
import java.net.URI
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Real SDK -> public HTTPS -> PostgreSQL. This is an emulator, not a physical phone test. */
class PublicHttpsPickupTest {
    private val main get() = InstrumentationRegistry.getInstrumentation()
    private fun await(timeout: Long = 15000, condition: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < until) {
            var done = false
            main.runOnMainSync { done = condition() }
            if (done) return
            SystemClock.sleep(100)
        }
        throw AssertionError("Public pickup did not reach expected state")
    }
    @Test fun registeredVehicleSharesAndEndsThroughPublicHttps() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("publicHttps") == "true")
        assertTrue(BuildConfig.PICKUP_API.startsWith("https://"))
        val app = ApplicationProvider.getApplicationContext<VehicleApplication>()
        val api = PickupApiClient()
        assertTrue("Public endpoint must require vehicle registration",
            !api.requestApi("GET", "/config").getBoolean("developmentToolsEnabled"))
        val codeFile = File(app.filesDir, "public-registration-code")
        val code = codeFile.readText().trim()
        assertTrue(codeFile.delete())
        var sdk: NaviHelper? = null
        var connected = false
        val summary = JSONObject().put("physicalPhone", false).put("publicHttps", true)
        try {
            await { !app.registration.busy }
            main.runOnMainSync {
                if (!app.registration.canCreate) app.registration.register(code)
            }
            await { app.registration.canCreate }
            assertTrue(EncryptedVehicleCredentialStore(app).load() != null)
            summary.put("vehicleCredentialEncrypted", true)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                main.runOnMainSync {
                    if (app.pickup.canCancel) app.pickup.cancel()
                }
                await { !app.pickup.busy }
                main.runOnMainSync {
                    app.pickup.prepareNextPickup()
                    app.destination.useFinalDestination()
                    app.destination.connect()
                    sdk = NaviHelper(app)
                    sdk!!.initialize(onConnected = { Handler(Looper.getMainLooper()).post { connected = true } }, onFailed = {})
                }
                await { connected }
                main.runOnMainSync {
                    sdk!!.requestRoute(RouteInfo(latitude = 37.48544722, longitude = 127.03636666,
                        poiName = "42dot", poiId = "36khp37hh8gqcm5nqhe5", address = "서울시 강남구 남부순환로 2621", poiSubId = "0"))
                }
                SystemClock.sleep(3000)
                main.runOnMainSync {
                    app.startActivity(android.content.Intent(app, MainActivity::class.java)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                    app.destination.connect()
                    app.destination.query()
                }
                scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
                await(30000) { app.pickup.canCreate && app.destination.selection.current(SystemClock.elapsedRealtime()) != null }
                main.runOnMainSync {
                    val place = app.destination.selection.current(SystemClock.elapsedRealtime())!!
                    app.pickup.create(place, app.destination.referenceFor(place))
                }
                await { app.pickup.pickupId != null && !app.pickup.busy }
                lateinit var link: String
                main.runOnMainSync { link = app.pickup.invitationForHostBrowser()!! }
                val parts = URI(link).fragment.split('/')
                val id = parts[1]
                val guest = api.request("POST", "/$id/participants", body = JSONObject()
                    .put("inviteToken", parts[2]).put("displayName", "HTTPS 시험 탑승자"))
                val guestToken = guest.getString("participantToken")
                val before = runCatching { api.request("GET", "/$id/vehicle-state", guestToken) }.exceptionOrNull()
                assertEquals(403, (before as ApiRejected).status)
                summary.put("beforeApprovalBlocked", true)
                main.runOnMainSync { app.pickup.refresh() }
                await { app.pickup.status == "ACTIVE" && !app.pickup.busy }
                await { app.pickup.status == "ACTIVE" }
                api.request("PUT", "/$id/readiness", guestToken, JSONObject().put("readiness", "READY").put("sequence", 1))
                main.runOnMainSync { app.pickup.refresh() }
                await { app.pickup.readiness == "READY" }
                val until = SystemClock.elapsedRealtime() + 30000
                var fresh = false
                while (!fresh && SystemClock.elapsedRealtime() < until) {
                    fresh = api.request("GET", "/$id/vehicle-state", guestToken).optString("freshness") == "FRESH"
                    if (!fresh) SystemClock.sleep(1000)
                }
                assertTrue("Server never accepted current real SDK telemetry", fresh)
                summary.put("sdkTelemetryFresh", true).put("readinessDelivered", true)
                main.runOnMainSync { app.pickup.arrive() }
                await { app.pickup.status == "ARRIVED" && !app.pickup.busy }
                assertEquals("ARRIVED", api.request("GET", "/$id", guestToken).getString("status"))
                summary.put("manualArrivalDelivered", true)
                main.runOnMainSync { app.pickup.complete() }
                await { app.pickup.status == "COMPLETED" && !app.pickup.busy }
                val after = runCatching { api.request("GET", "/$id/vehicle-state", guestToken) }.exceptionOrNull()
                assertEquals(410, (after as ApiRejected).status)
                assertNull(EncryptedPickupSessionStore(app).load())
                assertTrue(EncryptedVehicleCredentialStore(app).load() != null)
                summary.put("afterCompletionBlocked", true).put("pickupCredentialRemoved", true)
                    .put("vehicleRegistrationRetained", true).put("result", "passed")
            }
        } finally {
            main.runOnMainSync {
                if (app.pickup.canCancel) app.pickup.cancel()
                sdk?.release()
            }
            File(app.filesDir, "public-https-summary.json").writeText(summary.toString(2))
        }
    }
}
