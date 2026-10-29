package app.majung.vehicle

import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.helper.NaviHelper
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Opt-in recording of real UI + SDK + local HTTP, with a human/browser handoff at each step. */
class VehicleDemoRecordingTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = ApplicationProvider.getApplicationContext<VehicleApplication>()
    private val folder get() = File(app.filesDir, "demo-recording")

    @Test fun pickupWithPassengerBrowser() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("recordDemo") == "true")
        assumeTrue(BuildConfig.DEBUG && BuildConfig.PICKUP_API == "http://10.0.2.2:8080")
        assertTrue(Build.MODEL.contains("emulator", true))
        folder.mkdirs()
        folder.listFiles()?.forEach { it.delete() }
        val controller = app.pickup
        val api = PickupApiClient()
        val acceptedBefore = app.sharingNotice.accepted
        val place = PickupPlace("시연 만남 장소", 37.5, 127.03)
        var helper: NaviHelper? = null
        var connected = false
        var id: String? = null
        var owner: String? = null
        try {
            assertTrue(app.sharingNotice.accept())
            instrumentation.runOnMainSync {
                helper = NaviHelper(app).also {
                    it.initialize(onConnected = { Handler(Looper.getMainLooper()).post { connected = true } }, onFailed = {})
                }
                app.destination.connect()
            }
            await("SDK connection missing", 65_000) { connected }
            instrumentation.runOnMainSync {
                helper!!.requestRoute(RouteInfo(place.latitude, place.longitude, poiName = place.label,
                    poiId = "", address = "", poiSubId = "0"))
            }
            await("SDK route missing", 65_000) {
                app.destination.pollTelemetry()
                app.destination.currentStops()?.destination?.place?.sameCoordinates(place) == true &&
                    app.destination.telemetry.current(place, SystemClock.elapsedRealtime()) != null
            }
            // Setup only: creation uses the local API; the video must label this fixture explicitly.
            val created = api.create(place)
            id = created.getString("pickupId")
            owner = created.getString("driverToken")
            EncryptedPickupSessionStore(app).save(SavedPickupSession(id!!, owner!!, created.getString("inviteToken"), 0))
            instrumentation.runOnMainSync { controller.restoreSession() }
            await("Fixture restore failed") { controller.online && !controller.busy }
            instrumentation.runOnMainSync { controller.confirmRecoveredSharing() }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                app.startActivity(android.content.Intent(app, MainActivity::class.java)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                phase("ready")
                gate("create")
                val invitation = "http://127.0.0.1:4173/#join/$id/${created.getString("inviteToken")}"
                File(folder, "invitation.json").writeText(JSONObject().put("inviteLink", invitation).toString())
                phase("inviting")
                await("Passenger did not connect", 180_000) { controller.status == "ACTIVE" && !controller.busy }
                phase("active")
                press(scenario, "화면 밖에서도 공유 시작")
                await("SDK telemetry upload failed", 65_000) {
                    app.destination.pollTelemetry()
                    controller.syncVehicle(app.destination)
                    app.sharingRunning && controller.vehicleMessage.contains("최신")
                }
                phase("sdk-visible")
                await("Passenger not ready", 180_000) { controller.readiness == "READY" }
                phase("passenger-ready")
                gate("arrive")
                press(scenario, "도착 알리기")
                confirm("도착 알리기")
                await("Arrival failed") { controller.status == "ARRIVED" && !controller.busy }
                phase("arrived")
                gate("complete")
                press(scenario, "탑승 확인 · 픽업 완료")
                confirm("탑승 확인하고 완료")
                await("Completion failed") { controller.status == "COMPLETED" && !controller.busy }
                File(folder, "summary.json").writeText("""{"result":"passed","sdkAndLocalHttp":true,"fixtureCreation":true,"readinessReceived":true,"arrival":"manual","completed":true}""")
                phase("completed")
                gate("finish")
            }
        } finally {
            try {
                instrumentation.runOnMainSync {
                    if (controller.canCancel) controller.cancel()
                    app.destination.disconnect()
                    helper?.release()
                }
                await("Cleanup still busy") { !controller.busy }
            } finally {
                if (id != null && owner != null) runCatching { api.request("POST", "/$id/cancellation", owner) }
                EncryptedPickupSessionStore(app).clear()
                if (acceptedBefore) app.sharingNotice.accept() else app.sharingNotice.withdraw()
                folder.listFiles()?.filter { it.name != "summary.json" }?.forEach { it.delete() }
            }
        }
    }

    private fun press(scenario: ActivityScenario<MainActivity>, text: String) {
        scenario.onActivity { activity ->
            fun find(view: View): Button? {
                if (view is Button && view.text.toString() == text) return view
                if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
                return null
            }
            val button = find(activity.window.decorView) ?: throw AssertionError("Demo button missing")
            assertTrue("Demo button disabled", button.isEnabled)
            button.performClick()
        }
        instrumentation.waitForIdleSync()
    }

    private fun confirm(text: String) {
        instrumentation.waitForIdleSync()
        val until = SystemClock.elapsedRealtime() + 10_000
        var clicked = false
        while (!clicked && SystemClock.elapsedRealtime() < until) {
            val button = instrumentation.uiAutomation.rootInActiveWindow
                ?.findAccessibilityNodeInfosByText(text)?.firstOrNull { it.text?.toString() == text && it.isClickable }
            clicked = button?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
            if (!clicked) SystemClock.sleep(200)
        }
        assertTrue("Confirmation missing", clicked)
        instrumentation.waitForIdleSync()
    }

    private fun phase(value: String) {
        File(folder, "phase.txt").writeText(value)
    }

    private fun gate(name: String) {
        val file = File(folder, "$name.go")
        val deadline = SystemClock.elapsedRealtime() + 180_000
        while (!file.exists() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
        assertTrue("Demo handoff timed out: $name", file.exists())
        file.delete()
    }

    private fun await(message: String, timeout: Long = 20_000, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < deadline) {
            var done = false
            instrumentation.runOnMainSync { done = condition() }
            if (done) return
            SystemClock.sleep(100)
        }
        throw AssertionError(message)
    }
}
