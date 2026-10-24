package app.majung.vehicle

import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Operator changes P -> D -> P using the official Virtual Console, not a fake manager. */
class DrivingTransitionObservationTest {
    @Test fun observesRealSystemRestrictionTransition() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("observeDrivingTransition") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = ApplicationProvider.getApplicationContext<VehicleApplication>()
        lateinit var monitor: DrivingRestrictionMonitor
        instrumentation.runOnMainSync { monitor = DrivingRestrictionMonitor(context) { }; monitor.start() }
        fun waitFor(expected: Boolean, timeout: Long): Boolean {
            val end = SystemClock.elapsedRealtime() + timeout
            while (SystemClock.elapsedRealtime() < end) {
                var allowed = !expected
                instrumentation.runOnMainSync { allowed = monitor.allowed }
                if (allowed == expected) return true
                SystemClock.sleep(50)
            }
            return false
        }
        var initial = false
        var restricted = false
        var recovered = false
        try {
            initial = waitFor(true, 8000)
            restricted = initial && waitFor(false, 45000)
            recovered = restricted && waitFor(true, 45000)
            assertTrue("No actual system restriction transition observed", initial && restricted && recovered)
        } finally {
            instrumentation.runOnMainSync { monitor.stop() }
            java.io.File(context.filesDir, "driving-transition-summary.json").writeText(
                """{"initialAllowed":$initial,"restricted":$restricted,"allowedAgain":$recovered}""")
        }
    }
}
