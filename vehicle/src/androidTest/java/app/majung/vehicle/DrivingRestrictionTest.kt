package app.majung.vehicle

import android.app.AlertDialog
import android.os.SystemClock
import android.widget.LinearLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class DrivingRestrictionTest {
    @Test fun liveCarManagerIsReadAndDisconnectBlocksAgain() {
        val main = InstrumentationRegistry.getInstrumentation()
        lateinit var monitor: DrivingRestrictionMonitor
        main.runOnMainSync {
            monitor = DrivingRestrictionMonitor(ApplicationProvider.getApplicationContext<VehicleApplication>()) { }
            assertFalse(monitor.allowed)
            monitor.start()
        }
        val until = SystemClock.elapsedRealtime() + 8000
        var allowed = false
        while (!allowed && SystemClock.elapsedRealtime() < until) {
            main.runOnMainSync { allowed = monitor.allowed }
            SystemClock.sleep(50)
        }
        try { assertTrue("Parked emulator must allow baseline UX", allowed) }
        finally { main.runOnMainSync { monitor.stop(); assertFalse(monitor.allowed) } }
    }

    @Test fun liveRestrictionOnOffChangesGate() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val service = instrumentation.uiAutomation.executeShellCommand("service check hmg_car_service").use { descriptor ->
            java.io.FileInputStream(descriptor.fileDescriptor).use { String(it.readBytes()) }
        }
        org.junit.Assume.assumeTrue("This emulator lacks the official restriction-control service", service.contains("found") && !service.contains("not found"))
        lateinit var monitor: DrivingRestrictionMonitor
        instrumentation.runOnMainSync {
            monitor = DrivingRestrictionMonitor(ApplicationProvider.getApplicationContext<VehicleApplication>()) { }
            monitor.start()
        }
        fun waitFor(expected: Boolean) {
            val until = SystemClock.elapsedRealtime() + 8000
            while (SystemClock.elapsedRealtime() < until) {
                var value = !expected
                instrumentation.runOnMainSync { value = monitor.allowed }
                if (value == expected) return
                SystemClock.sleep(50)
            }
            throw AssertionError("Live UX restriction did not reach expected state")
        }
        fun regulation(value: String) {
            instrumentation.uiAutomation.executeShellCommand("cmd hmg_car_service --service drivingstate driving_regulation $value")
                .use { descriptor -> java.io.FileInputStream(descriptor.fileDescriptor).use { it.readBytes() } }
        }
        try {
            waitFor(true)
            regulation("on")
            waitFor(false)
            regulation("off")
            waitFor(true)
        } finally {
            regulation("off")
            instrumentation.runOnMainSync { monitor.stop() }
        }
    }

    @Test fun restrictedViewClosesDialogAndRechecksActions() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                var allowed = true
                var actions = 0
                val views = VehicleViews(activity, LinearLayout(activity), {}) { allowed }
                val button = views.button("시험 동작") { actions++ }
                button.performClick()
                assertEquals(1, actions)
                val dialog = AlertDialog.Builder(activity).setMessage("시험 확인창").setPositiveButton("확인", null).show()
                views.trackDialog(dialog)
                assertTrue(dialog.isShowing)
                allowed = false
                views.applyRestrictions()
                assertFalse(button.isEnabled)
                assertFalse(dialog.isShowing)
                button.performClick()
                assertEquals(1, actions)
                allowed = true
                views.beginRender()
                views.applyRestrictions()
                assertTrue(button.isEnabled)
                button.performClick()
                assertEquals(2, actions)
            }
        }
    }
}
