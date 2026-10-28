package app.majung.vehicle

import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.helper.NaviHelper
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Real loopback Spring API + Pleos SDK. All people and places are test fixtures. */
class QueuedPickupSdkTest {
    private val main get() = InstrumentationRegistry.getInstrumentation()
    private val origin = "http://127.0.0.1:18081"

    @Test fun acceptWaitingPassengerThenShareSdkWaypointAndComplete() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("queueSdk") == "true")
        val app = ApplicationProvider.getApplicationContext<VehicleApplication>()
        val inputFile = File(app.filesDir, "queue-sdk-input.json")
        assertTrue("Run scripts/prepare-queue-sdk-test.py first", inputFile.exists())
        val input = JSONObject(inputFile.readText())
        assertTrue(inputFile.delete())
        val api = PickupApiClient(origin)
        val requestId = input.getString("requestId")
        val vehicleToken = input.getString("vehicleToken")
        val requested = PickupPlace("SDK 시험 장소", 37.48544722, 127.03636666)
        val report = JSONObject().put("publicServer", false).put("physicalPhone", false)
        val store = object : VehicleCredentialStore {
            override fun load() = VehicleCredential(input.getString("vehicleId"), vehicleToken)
            override fun save(credential: VehicleCredential) { }
            override fun clear() { }
        }
        lateinit var registration: VehicleRegistrationController
        lateinit var account: VehicleAccountController
        lateinit var pickup: PickupController
        var control: NaviHelper? = null
        var connected = false
        main.runOnMainSync {
            registration = VehicleRegistrationController(api, store)
            account = VehicleAccountController(api, registration)
            pickup = PickupController(api, MemoryPickupSessionStore(), registration)
            registration.refresh()
        }
        await("registration") { registration.canCreate }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            try {
                main.runOnMainSync { account.refresh() }
                await("received requests") { !account.busy && account.requests.size == 2 }
                assertEquals("LINKED", account.state.getString("status"))
                main.runOnMainSync { account.changeRequest(requestId, "accept") }
                await("accepted queue") {
                    !account.busy && account.requests.any { it.optString("requestId") == requestId && it.optString("status") == "QUEUED" }
                }
                assertEquals(403, passenger(input, "GET", "/ride-requests/$requestId/tracking").first)
                try {
                    api.requestApi("POST", "/vehicles/ride-requests/$requestId/start", vehicleToken)
                    fail("A second pickup started while the first was active")
                } catch (error: ApiRejected) { assertEquals(409, error.status) }
                report.put("newPassengerAcceptedWhileAnotherActive", true)
                    .put("queuedTrackingDenied", true).put("concurrentPickupDenied", true)

                val firstId = input.getString("pickupId")
                val firstOwner = input.getString("driverToken")
                try {
                    val first=api.request("GET", "/$firstId", firstOwner)
                    api.request("POST", "/$firstId/arrival", firstOwner,
                        JSONObject().put("placeVersion",first.getLong("placeVersion")))
                } catch(error: ApiRejected) {
                    throw AssertionError("First fixture pickup arrival rejected: ${error.status}/${error.code}")
                }
                api.request("POST", "/$firstId/completion", firstOwner)
                main.runOnMainSync {
                    app.destination.connect()
                    control = NaviHelper(app)
                    control!!.initialize(onConnected = { Handler(Looper.getMainLooper()).post { connected = true } }, onFailed = {})
                }
                await("SDK connection") { connected }
                main.runOnMainSync {
                    control!!.requestRoute(RouteInfo(latitude = 37.566535, longitude = 126.977969,
                        poiName = "SDK 최종 목적지", poiId = "", address = "", poiSubId = "0"))
                }
                returnFromNavigation(app, scenario)
                await("final destination") {
                    app.destination.currentStops()?.let {
                        it.waypoints.isEmpty() && kotlin.math.abs(it.destination.place.latitude - 37.566535) < 0.000001
                    } == true
                }
                main.runOnMainSync { assertTrue(app.destination.addPickupWaypoint(requested)) }
                returnFromNavigation(app, scenario)
                await("requested waypoint") {
                    app.destination.currentStops()?.resolve(requested)?.kind == NavigationStopKind.WAYPOINT &&
                        app.destination.selection.current(SystemClock.elapsedRealtime())?.sameCoordinates(requested) == true
                }
                main.runOnMainSync {
                    assertTrue(kotlin.math.abs(app.destination.currentStops()!!.destination.place.latitude - 37.566535) < 0.000001)
                    val reference = app.destination.referenceFor(requested)
                    assertTrue(reference?.startsWith("majung:") == true)
                    pickup.startRequested(requestId, requested, reference)
                }
                await("requested pickup start") { !pickup.busy && pickup.status == "ACTIVE" }
                assertTrue(pickup.needsSharingConfirmation)
                val before = passenger(input, "GET", "/ride-requests/$requestId/tracking")
                assertEquals(200, before.first)
                assertTrue(before.second.getJSONObject("vehicle").isNull("latitude"))
                main.runOnMainSync { pickup.confirmRecoveredSharing() }
                await("SDK location and ETA") {
                    pickup.syncVehicle(app.destination)
                    pickup.vehicleMessage == "최신 위치·ETA를 탑승자에게 공유하고 있어요"
                }
                val live = passenger(input, "GET", "/ride-requests/$requestId/tracking")
                assertEquals(200, live.first)
                assertEquals("FRESH", live.second.getJSONObject("vehicle").getString("freshness"))
                main.runOnMainSync {
                    assertEquals(app.destination.currentStops()!!.resolve(requested)!!.durationSeconds,
                        live.second.getJSONObject("vehicle").getLong("durationSeconds"))
                }
                report.put("finalDestinationPreserved", true).put("sdkWaypointMatched", true)
                    .put("sharingWaitedForConfirmation", true).put("sdkWaypointEtaShared", true)

                val ready = passenger(input, "PUT", "/ride-requests/$requestId/readiness",
                    JSONObject().put("readiness", "READY").put("sequence", 1))
                assertEquals(200, ready.first)
                main.runOnMainSync { pickup.refresh() }
                await("passenger ready") { pickup.readiness == "READY" }
                main.runOnMainSync { pickup.arrive() }
                await("arrival") { !pickup.busy && pickup.status == "ARRIVED" }
                main.runOnMainSync { pickup.complete() }
                await("completion") { !pickup.busy && pickup.status == "COMPLETED" }
                assertEquals(403, passenger(input, "GET", "/ride-requests/$requestId/tracking").first)
                main.runOnMainSync { pickup.prepareNextPickup() }
                assertTrue(pickup.canCreate)
                report.put("readinessReceived", true).put("completedTrackingDenied", true)
                    .put("nextPickupAvailable", true).put("result", "passed")
            } finally {
                File(app.filesDir, "queue-sdk-summary.json").writeText(report.toString(2))
                main.runOnMainSync {
                    if (pickup.canCancel) pickup.cancel()
                    control?.release()
                    app.destination.disconnect()
                }
                await("cleanup") { !pickup.busy }
            }
        }
    }

    private fun passenger(input: JSONObject, method: String, path: String, body: JSONObject? = null): Pair<Int, JSONObject> {
        val connection = URL("$origin/api/v1$path").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.setRequestProperty("Cookie", input.getString("cookie"))
            connection.setRequestProperty("X-CSRF-TOKEN", input.getString("csrfToken"))
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            return status to stream.bufferedReader().use { JSONObject(it.readText()) }
        } finally { connection.disconnect() }
    }

    private fun returnFromNavigation(app: VehicleApplication, scenario: ActivityScenario<MainActivity>) {
        // Allow the navigation window to open; actual readiness still comes from SDK responses.
        SystemClock.sleep(3000)
        main.runOnMainSync {
            app.startActivity(android.content.Intent(app, MainActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        }
        scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        main.runOnMainSync { app.destination.connect(); app.destination.query() }
    }

    private fun await(stage: String, condition: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + 40000
        while (SystemClock.elapsedRealtime() < until) {
            var done = false
            main.runOnMainSync { done = condition() }
            if (done) return
            SystemClock.sleep(100)
        }
        throw AssertionError("Queue SDK test did not reach: $stage")
    }
}
