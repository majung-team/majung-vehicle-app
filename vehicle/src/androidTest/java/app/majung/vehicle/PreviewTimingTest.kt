package app.majung.vehicle

import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.helper.NaviHelper
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in SDK check. No account, pickup, or server mutations. */
class PreviewTimingTest {
    @Test fun readGuidanceUsingAutomaticPreparation() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("verifyPreviewTiming") == "true")
        val test=InstrumentationRegistry.getInstrumentation()
        val app=ApplicationProvider.getApplicationContext<VehicleApplication>()
        val reader=DestinationReader(app)
        var control: NaviHelper?=null
        var connected=false
        fun waitFor(predicate: () -> Boolean) {
            val end=SystemClock.elapsedRealtime()+30000
            while(SystemClock.elapsedRealtime()<end) {
                var done=false
                test.runOnMainSync { done=predicate() }
                if(done) return
                SystemClock.sleep(100)
            }
            throw AssertionError("SDK preparation did not complete within 30 seconds")
        }
        try {
            test.runOnMainSync {
                control=NaviHelper(app)
                control!!.initialize(onConnected={ connected=true },onFailed={})
            }
            waitFor { connected }
            test.runOnMainSync {
                control!!.requestRoute(RouteInfo(latitude=37.48544722,longitude=127.03636666,
                    poiName="42dot",poiId="36khp37hh8gqcm5nqhe5",address="서울시 강남구 남부순환로 2621",poiSubId="0"))
            }
            // Give the emulator navigation app time to start guidance before measuring preview.
            SystemClock.sleep(3000)
            val start=SystemClock.elapsedRealtime()
            test.runOnMainSync {
                reader.timingObserver={ event -> test.sendStatus(0,android.os.Bundle().apply {
                    putString("sdkEvent",event.type.name)
                    putLong("sincePreviewStartMs",event.elapsedMs-start)
                }) }
            }
            var firstNameAt: Long?=null
            var ready=false
            while(SystemClock.elapsedRealtime()-start<25000) {
                test.runOnMainSync {
                    reader.prepareForPickup()
                    if(firstNameAt==null && reader.previewPlaceLabel!=null) firstNameAt=SystemClock.elapsedRealtime()-start
                    ready=reader.selection.current(SystemClock.elapsedRealtime())!=null && reader.currentStops()!=null
                }
                if(ready) break
                SystemClock.sleep(1000)
            }
            val elapsed=SystemClock.elapsedRealtime()-start
            test.sendStatus(0,android.os.Bundle().apply { putLong("previewReadyMs",elapsed); firstNameAt?.let { putLong("firstPlaceNameMs",it) } })
            assertTrue("Real SDK preview did not become ready",ready)
            test.runOnMainSync {
                val target = reader.currentStops()!!.destination.place
                var starts = 0
                val flow = RequestPickupFlow(object : RequestPickupFlow.Host {
                    override val authority: Any = "emulator-test-driver"
                    override val requestsFresh = true
                    override val pickupStatus get() = if (starts > 0) "ACTIVE" else ""
                    override val hasPickup get() = starts > 0
                    override val pickupBusy = false
                    override val sharingRunning = false
                    override val routeFailure get() = reader.failure?.message
                    override val pickupMessage = ""
                    override fun request(id: String) = RequestedRide(id, "SDK 시험 탑승자", target, "QUEUED")
                    override fun canStart(id: String) = starts == 0
                    override fun hasOtherActiveRequest(id: String) = false
                    override fun refreshRequests() { }
                    override fun prepareRoute(retry: Boolean) { if (retry) reader.prepareRequestedRoute() else reader.prepareForPickup() }
                    override fun stops() = reader.currentStops()
                    override fun select(place: PickupPlace) = reader.selectVerifiedStop(place)
                    override fun insert(place: PickupPlace) = reader.addPickupWaypoint(place)
                    override fun clearEnded() { }
                    override fun start(ride: RequestedRide) { starts++ }
                }, SystemClock::elapsedRealtime)
                flow.begin("sdk-fixture-request")
                flow.tick()
                assertTrue("Verified SDK route must reach request confirmation", flow.stage == RequestPickupFlow.Stage.READY)
                flow.confirm(false)
                assertTrue(starts == 0)
                flow.confirm(true)
                flow.confirm(true)
                assertTrue("Request start must be explicit and single", starts == 1)
                test.sendStatus(0,android.os.Bundle().apply { putBoolean("requestFlowWithRealSdk",true) })
                // Use the current reader connection; the separate setup connection may no longer own SDK control.
                val active = DestinationReader::class.java.getDeclaredField("helper").apply { isAccessible = true }.get(reader) as NaviHelper
                active.cancelRoute()
            }
            waitFor { reader.prepareForPickup(); reader.previewPlaceLabel==null && reader.selection.current(SystemClock.elapsedRealtime())==null && reader.currentStops()==null }
        } finally {
            test.runOnMainSync { reader.disconnect(); control?.cancelRoute(); control?.release() }
        }
    }
}
