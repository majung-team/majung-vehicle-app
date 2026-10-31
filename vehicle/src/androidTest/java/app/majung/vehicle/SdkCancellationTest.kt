package app.majung.vehicle

import ai.pleos.playground.navi.constants.NaviMode
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteStateInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Synthetic destination only. No pickup API or production account is used. */
class SdkCancellationTest {
    @Test fun cancelAcknowledgementAndGuidanceState() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("sdkCancellation") == "true")
        val test = InstrumentationRegistry.getInstrumentation()
        val app = ApplicationProvider.getApplicationContext<VehicleApplication>()
        var control: NaviHelper? = null
        var connected = false
        var cancellationCallbacks = 0
        var stateCallbacks = 0
        var guiding = false
        val result = JSONObject().put("syntheticData", true)
        val listener = object : NaviHelperEventListener {
            override fun onRouteCancelled() { cancellationCallbacks++ }
            override fun onRouteStateInfo(info: RouteStateInfo) {
                stateCallbacks++
                guiding = info.naviMode in setOf(NaviMode.ROUTE_GUIDING, NaviMode.SIMULATION_GUIDING)
            }
        }
        fun await(timeout: Long, condition: () -> Boolean): Boolean {
            val end = SystemClock.elapsedRealtime() + timeout
            while (SystemClock.elapsedRealtime() < end) {
                var ready = false
                test.runOnMainSync { ready = condition() }
                if (ready) return true
                SystemClock.sleep(100)
            }
            return false
        }
        try {
            test.runOnMainSync {
                app.destination.disconnect()
                control = NaviHelper(app)
                control!!.initialize(onConnected = {
                    control!!.addListener(listener)
                    connected = true
                }, onFailed = {})
            }
            assertTrue("SDK connection unavailable", await(15000) { connected })
            test.runOnMainSync { control!!.requestRoute(RouteInfo(latitude = 37.48544722,
                longitude = 127.03636666, poiName = "42dot", poiId = "36khp37hh8gqcm5nqhe5",
                address = "서울시 강남구 남부순환로 2621", poiSubId = "0")) }
            assertTrue("Guidance did not start", await(20000) {
                control!!.getRouteStateInfo()
                guiding
            })
            test.runOnMainSync { control!!.cancelRoute() }
            val began = SystemClock.elapsedRealtime()
            var stopped = false
            while (SystemClock.elapsedRealtime() - began < 30000) {
                var before = 0
                test.runOnMainSync { before = stateCallbacks; control!!.getRouteStateInfo() }
                if (await(2000) { stateCallbacks > before && !guiding }) { stopped = true; break }
                SystemClock.sleep(500)
            }
            result.put("cancelCallbacks", cancellationCallbacks).put("guidanceStopped", stopped)
                .put("waitMs", SystemClock.elapsedRealtime() - began)
            assertTrue("SDK did not report guidance stopped within 30 seconds", stopped)
        } finally {
            File(app.filesDir, "sdk-cancellation-summary.json").writeText(result.toString(2))
            test.runOnMainSync { control?.removeListener(listener); control?.release() }
        }
    }
}
