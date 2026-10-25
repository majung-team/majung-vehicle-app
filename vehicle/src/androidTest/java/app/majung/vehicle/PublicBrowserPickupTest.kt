package app.majung.vehicle

import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Browser operator joins/sets READY, then creates the finish gate after checking ARRIVED. */
class PublicBrowserPickupTest {
    private val main get() = InstrumentationRegistry.getInstrumentation()
    private fun await(timeout: Long, condition: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < until) {
            var done = false
            main.runOnMainSync { done = condition() }
            if (done) return
            SystemClock.sleep(100)
        }
        throw AssertionError("Browser integration did not reach expected state")
    }
    @Test fun browserParticipantUsesRealVehicleAndPublicServer() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("publicBrowser") == "true")
        val app = ApplicationProvider.getApplicationContext<VehicleApplication>()
        val finish = java.io.File(app.filesDir, "public-browser-finish")
        finish.delete()
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                await(15000) { app.registration.canCreate && !app.pickup.busy }
                main.runOnMainSync { app.pickup.prepareNextPickup(); app.destination.connect(); app.destination.query() }
                await(30000) { app.pickup.canCreate && app.destination.selection.current(SystemClock.elapsedRealtime()) != null }
                main.runOnMainSync {
                    val place = app.destination.selection.current(SystemClock.elapsedRealtime())!!
                    app.pickup.create(place, app.destination.referenceFor(place))
                }
                await(15000) { app.pickup.pickupId != null && !app.pickup.busy }
                lateinit var invitation: JSONObject
                main.runOnMainSync { invitation = JSONObject().put("joinUrl", app.pickup.invitationForHostBrowser())
                    .put("shareUrl", app.pickup.invitationForSharingBrowser()) }
                java.io.File(app.filesDir, "public-browser-invite.json").writeText(invitation.toString())
                await(240000) { app.pickup.canApprove }
                main.runOnMainSync { app.pickup.approve() }
                await(15000) { app.pickup.status == "ACTIVE" }
                await(240000) { app.pickup.readiness == "READY" && !app.pickup.busy }
                main.runOnMainSync { app.pickup.arrive() }
                await(15000) { app.pickup.status == "ARRIVED" && !app.pickup.busy }
                await(240000) { finish.exists() }
                main.runOnMainSync { app.pickup.complete() }
                await(15000) { app.pickup.status == "COMPLETED" && !app.pickup.busy }
                assertTrue(app.pickup.place == null)
                java.io.File(app.filesDir, "public-browser-summary.json").writeText(JSONObject()
                    .put("realSdk", true).put("publicHttps", true).put("browserParticipant", true)
                    .put("physicalPhone", false).put("readyReachedVehicle", true)
                    .put("manualArrival", true).put("completed", true).put("result", "passed").toString(2))
            }
        } finally {
            finish.delete()
            java.io.File(app.filesDir, "public-browser-invite.json").delete()
            main.runOnMainSync { if (app.pickup.canCancel) app.pickup.cancel() }
        }
    }
}
