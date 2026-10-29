package app.majung.vehicle

import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.helper.NaviHelper
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in emulator SDK + local server test. All places and participants are synthetic fixtures. */
class PlaceConfirmationSdkTest {
    private var poll: (() -> Unit)? = null
    private val main = InstrumentationRegistry.getInstrumentation()
    @Test fun changesPickupAfterSdkProofAndKeepsFinalDestinationAndOtherWaypoint() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("placeConfirmation") == "true")
        assumeTrue(BuildConfig.DEBUG && BuildConfig.PICKUP_API == "http://10.0.2.2:8080")
        val app = ApplicationProvider.getApplicationContext<VehicleApplication>()
        val api = PickupApiClient()
        var reader = DestinationReader(app)
        poll = { reader.pollTelemetry() }
        val final = PickupPlace("시험 최종 목적지", 37.566535, 126.977969)
        val other = PickupPlace("시험 다른 경유지", 37.52, 127.02)
        val old = PickupPlace("시험 기존 픽업", 37.49, 127.03)
        val next = PickupPlace("시험 새 픽업", 37.50, 127.04)
        lateinit var helper: NaviHelper
        lateinit var controller: PickupController
        var connected = false
        var id: String? = null
        var owner: String? = null
        try {
            main.runOnMainSync {
                helper = NaviHelper(app)
                helper.initialize(onConnected = { Handler(Looper.getMainLooper()).post { connected = true } }, onFailed = {})
                reader.connect()
            }
            phase("helper-connect"); await { connected }
            main.runOnMainSync { helper.requestRoute(RouteInfo(final.latitude, final.longitude, poiName = final.label,
                poiId = "", address = "", poiSubId = "0")) }
            phase("initial-route"); await { reader.currentStops()?.destination?.place?.sameCoordinates(final) == true }
            main.runOnMainSync { assertTrue(reader.addPickupWaypoint(other)) }
            phase("other-waypoint"); await { reader.currentStops()?.resolve(other) != null }
            main.runOnMainSync { assertTrue(reader.addPickupWaypoint(old)) }
            phase("old-waypoint"); await { reader.currentStops()?.let { it.resolve(old) != null && it.resolve(other) != null } == true }
            val created = api.create(old)
            id = created.getString("pickupId")
            owner = created.getString("driverToken")
            val guest = api.request("POST", "/$id/participants", body = JSONObject().put("inviteToken", created.getString("inviteToken")))
                .getString("participantToken")
            val store = MemoryPickupSessionStore().apply { save(SavedPickupSession(id!!, owner!!, null, 0)) }
            main.runOnMainSync { controller = PickupController(api, store); controller.restoreSession() }
            phase("controller-restore"); await { controller.online && controller.status == "ACTIVE" && !controller.busy }
            main.runOnMainSync { controller.confirmRecoveredSharing() }
            api.request("PUT", "/$id/place-proposal", guest, JSONObject().put("place", JSONObject()
                .put("label", next.label).put("latitude", next.latitude).put("longitude", next.longitude)))
            main.runOnMainSync { controller.refresh() }
            phase("proposal-read"); await { controller.proposalId != null && !controller.busy }
            main.runOnMainSync { assertTrue(reader.changePickupPlace(old, next, controller.proposalId!!)) }
            phase("route-replacement"); await { reader.verifiedPlaceChange(next) != null && reader.telemetry.current(next, SystemClock.elapsedRealtime()) != null }
            main.runOnMainSync {
                val route = reader.currentStops()!!
                assertTrue(route.destination.place.sameCoordinates(final))
                assertNotNull(route.resolve(other))
                assertNull(route.resolve(old))
            }
            // A fresh reader has none of the previous SDK coordinate cache or route proof.
            main.runOnMainSync {
                reader.disconnect()
                reader = DestinationReader(app)
                reader.connect()
                reader.reviewProposedPlace(next, controller.proposalId!!)
            }
            phase("reader-recovery"); await { reader.verifiedPlaceChange(next) != null && reader.telemetry.current(next, SystemClock.elapsedRealtime()) != null }
            main.runOnMainSync { controller.confirmProposedPlace(reader) }
            phase("server-confirmation"); await { !controller.busy && controller.placeVersion == 2L }
            assertEquals(2, api.request("GET", "/$id", guest).getLong("placeVersion"))
            main.runOnMainSync { controller.syncVehicle(reader) }
            phase("telemetry-upload"); await { controller.vehicleMessage.contains("공유") }
            assertEquals(2, api.request("GET", "/$id/vehicle-state", guest).getLong("placeVersion"))
            java.io.File(app.filesDir, "place-confirmation-sdk-summary.json").writeText(
                """{"result":"passed","finalDestinationPreserved":true,"otherWaypointPreserved":true,"oldWaypointRemoved":true,"serverPlaceVersion":2,"sdkAndLocalHttp":true,"freshReaderRecovery":true}""")
        } finally {
            if (id != null && owner != null) runCatching { api.request("POST", "/$id/cancellation", owner) }
            main.runOnMainSync { reader.disconnect(); runCatching { helper.release() } }
        }
    }
    private var currentPhase = "start"
    private fun phase(value: String) { currentPhase = value; android.util.Log.i("MajungPlaceTest", value) }
    private fun await(condition: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + 65000
        var nextPoll = 0L
        while (SystemClock.elapsedRealtime() < until) {
            var done = false
            main.runOnMainSync {
                done = condition()
                if (!done && SystemClock.elapsedRealtime() >= nextPoll) { poll?.invoke(); nextPoll = SystemClock.elapsedRealtime() + 5000 }
            }
            if (done) return
            SystemClock.sleep(100)
        }
        throw AssertionError("SDK place confirmation timed out: $currentPhase")
    }
}
