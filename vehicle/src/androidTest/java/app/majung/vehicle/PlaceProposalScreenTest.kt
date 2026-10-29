package app.majung.vehicle

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Local API fixture screen; does not claim that a driver pressed either control. */
class PlaceProposalScreenTest {
    @Test fun showsProposalButDoesNotEnableConfirmationBeforeSdkProof() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("placeConfirmation") == "true")
        val app = ApplicationProvider.getApplicationContext<VehicleApplication>()
        val api = PickupApiClient()
        val created = api.create(PickupPlace("시험 기존 픽업", 37.49, 127.03))
        val id = created.getString("pickupId")
        val owner = created.getString("driverToken")
        val guest = api.request("POST", "/$id/participants", body = JSONObject()
            .put("inviteToken", created.getString("inviteToken"))).getString("participantToken")
        api.request("PUT", "/$id/place-proposal", guest, JSONObject().put("place", JSONObject()
            .put("label", "시험 새 픽업").put("latitude", 37.5).put("longitude", 127.04)))
        EncryptedPickupSessionStore(app).save(SavedPickupSession(id, owner, null, 0))
        try {
            val main = InstrumentationRegistry.getInstrumentation()
            main.runOnMainSync { app.pickup.restoreSession() }
            val restoreUntil = SystemClock.elapsedRealtime() + 15000
            var restored = false
            while (!restored && SystemClock.elapsedRealtime() < restoreUntil) {
                main.runOnMainSync { restored = app.pickup.online && !app.pickup.busy }
                if (!restored) SystemClock.sleep(100)
            }
            assertTrue(restored)
            main.runOnMainSync { app.pickup.confirmRecoveredSharing() }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                app.startActivity(android.content.Intent(app, MainActivity::class.java)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                val until = SystemClock.elapsedRealtime() + 15000
                var found = false
                while (!found && SystemClock.elapsedRealtime() < until) {
                    scenario.onActivity { activity ->
                        fun buttons(view: View): List<Button> = when (view) {
                            is Button -> listOf(view)
                            is ViewGroup -> (0 until view.childCount).flatMap { buttons(view.getChildAt(it)) }
                            else -> emptyList()
                        }
                        val all = buttons(activity.window.decorView)
                        val proposal = all.singleOrNull { it.text.toString() == "1. 제안 장소로 경로 확인" && it.visibility == View.VISIBLE }
                        val confirm = all.singleOrNull { it.text.toString() == "2. 새 장소 확정" }
                        if (proposal != null && confirm != null && activity.hasWindowFocus()) { assertFalse(confirm.isEnabled); assertEquals(View.GONE, confirm.visibility); found = true }
                    }
                    if (!found) SystemClock.sleep(200)
                }
                assertTrue("Proposal controls were not visible", found)
                scenario.onActivity { activity ->
                    val controls = activity.findViewById<View>(R.id.parked_controls)
                    val visible = android.graphics.Rect(); controls.getGlobalVisibleRect(visible)
                    val metrics = if (android.os.Build.VERSION.SDK_INT >= 30) activity.windowManager.currentWindowMetrics.bounds.toShortString() else "legacy"
                    File(app.filesDir,"service-layout-metrics.json").writeText(JSONObject()
                        .put("decorWidth",activity.window.decorView.width).put("controlsWidth",controls.width)
                        .put("visible",visible.toShortString()).put("window",metrics).toString())
                }
                main.waitForIdleSync()
                main.uiAutomation.waitForIdle(500, 5000)
                val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                try { File(app.filesDir, "place-proposal-screen.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
                finally { bitmap.recycle() }
            }
        } finally {
            runCatching { api.request("POST", "/$id/cancellation", owner) }
            EncryptedPickupSessionStore(app).clear()
        }
    }
}
