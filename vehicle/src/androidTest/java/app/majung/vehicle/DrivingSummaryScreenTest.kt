package app.majung.vehicle

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.time.Instant
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class DrivingSummaryScreenTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun summaryUsesFreshTelemetryAndRespectsPlatformLimits() {
        isolated { app ->
            instrumentation.runOnMainSync {
                val pickup = app.pickup
                fun set(name: String, value: Any) {
                    PickupController::class.java.getDeclaredField(name).apply { isAccessible = true }.set(pickup, value)
                }
                val place = PickupPlace("시험 장소", 37.3, 127.3)
                set("status", "ACTIVE"); set("online", true); set("readiness", "READY"); set("place", place)
                app.sharingRunning = true
                val now = SystemClock.elapsedRealtime()
                app.destination.telemetry.location(37.2, 127.2, now, Instant.now())
                app.destination.telemetry.destination(place, 420, now, Instant.now())
                val section = DrivingPickupSection(app, app)
                section.render(true, 40, 3)
                assertEquals("약 7분 남음", section.root.findViewById<TextView>(R.id.driving_eta).text.toString())
                assertFalse(section.root.isClickable)
                for (index in 0 until section.root.childCount) assertFalse(section.root.getChildAt(index).isClickable)
                app.destination.telemetry.invalidate()
                section.render(true, 40, 3)
                assertEquals("남은 시간 확인 중", section.root.findViewById<TextView>(R.id.driving_eta).text.toString())
                section.render(true, 5, 1)
                val visible = (0 until section.root.childCount).map { section.root.getChildAt(it) }.filter { it.visibility == View.VISIBLE }
                assertEquals(1, visible.size)
                assertTrue((visible.single() as TextView).text.length <= 5)
                section.render(true, 0, 0)
                assertTrue((0 until section.root.childCount).all { section.root.getChildAt(it).visibility == View.GONE })
                section.render(false, 40, 3)
                assertEquals("차량 상태 확인 중", section.root.findViewById<TextView>(R.id.driving_phase).text.toString())
                assertEquals(View.GONE, section.root.findViewById<TextView>(R.id.driving_eta).visibility)
            }
        }
    }

    /** An operator changes the official Virtual Console's gear; the app reads CarUxRestrictions. */
    @Test fun realSystemTransitionHidesControlsAndRestoresParkedScreen() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("drivingSummaryTransition") == "true")
        isolated { app ->
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                fun waitFor(restricted: Boolean, timeout: Long): Boolean {
                    val end = SystemClock.elapsedRealtime() + timeout
                    while (SystemClock.elapsedRealtime() < end) {
                        var matches = false
                        scenario.onActivity { activity ->
                            val summary = activity.findViewById<View>(R.id.driving_summary)
                            val parked = activity.findViewById<View>(R.id.parked_controls)
                            matches = if (restricted) summary.isShown && !parked.isShown else parked.isShown && !summary.isShown
                        }
                        if (matches) return true
                        SystemClock.sleep(100)
                    }
                    return false
                }
                fun capture(name: String) {
                    instrumentation.waitForIdleSync()
                    File(app.filesDir, name).outputStream().use {
                        instrumentation.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                }
                assertTrue("Start in P", waitFor(false, 15000))
                capture("driving-summary-parked.png")
                File(app.filesDir, "driving-summary-stage.txt").writeText("READY_FOR_D")
                assertTrue("No restricted UI after D", waitFor(true, 90000))
                scenario.onActivity { activity ->
                    fun assertReadOnly(view: View) {
                        if (!view.isShown) return
                        assertFalse("Visible control during restriction", view.isClickable || view is android.widget.EditText)
                        if (view is ViewGroup) for (index in 0 until view.childCount) assertReadOnly(view.getChildAt(index))
                    }
                    assertReadOnly(activity.findViewById(R.id.driving_summary))
                }
                instrumentation.runOnMainSync {
                    val pickup = app.pickup
                    fun set(name: String, value: Any) {
                        PickupController::class.java.getDeclaredField(name).apply { isAccessible = true }.set(pickup, value)
                    }
                    val place = PickupPlace("시험 장소", 37.3, 127.3)
                    set("status", "ACTIVE"); set("online", true); set("readiness", "READY"); set("place", place)
                    app.sharingRunning = true
                    val now = SystemClock.elapsedRealtime()
                    app.destination.telemetry.location(37.2, 127.2, now, Instant.now())
                    app.destination.telemetry.destination(place, 420, now, Instant.now())
                    pickup.changed?.invoke()
                }
                scenario.onActivity { activity ->
                    assertTrue(activity.findViewById<View>(R.id.driving_summary).isShown)
                    assertEquals("약 7분 남음", activity.findViewById<TextView>(R.id.driving_eta).text.toString())
                }
                capture("driving-summary-restricted.png")
                File(app.filesDir, "driving-summary-stage.txt").writeText("READY_FOR_P")
                assertTrue("Parked UI did not return", waitFor(false, 90000))
                File(app.filesDir, "driving-summary-result.json").writeText(
                    """{"source":"Pleos Virtual Console / CarUxRestrictions","parked":true,"restrictedReadOnly":true,"parkedRestored":true,"syntheticSession":true}""")
            }
        }
    }

    private fun isolated(action: (VehicleApplication) -> Unit) {
        val app = ApplicationProvider.getApplicationContext<VehicleApplication>()
        val names = listOf("registration", "account", "pickup", "destination")
        val fields = names.map { VehicleApplication::class.java.getDeclaredField(it).apply { isAccessible = true } }
        val originals = fields.map { it.get(app) }
        val oldSharing = app.sharingRunning
        instrumentation.runOnMainSync {
            val api = PickupApiClient("http://127.0.0.1:1")
            val store = object : VehicleCredentialStore {
                override fun load(): VehicleCredential? = null
                override fun save(credential: VehicleCredential) { }
                override fun clear() { }
            }
            val registration = VehicleRegistrationController(api, store)
            fields[0].set(app, registration)
            fields[1].set(app, VehicleAccountController(api, registration))
            fields[2].set(app, PickupController(api, MemoryPickupSessionStore(), registration))
            fields[3].set(app, DestinationReader(app))
            app.sharingRunning = false
        }
        try { action(app) }
        finally {
            instrumentation.runOnMainSync {
                fields.zip(originals).forEach { (field, value) -> field.set(app, value) }
                app.sharingRunning = oldSharing
            }
        }
    }
}
