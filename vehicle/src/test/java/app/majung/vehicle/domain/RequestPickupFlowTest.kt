package app.majung.vehicle

import org.junit.Assert.*
import org.junit.Test

class RequestPickupFlowTest {
    private val home = PickupPlace("최종 목적지", 37.1, 127.1)
    private val station = PickupPlace("만날 장소", 37.2, 127.2)
    private fun route(vararg waypoints: PickupPlace) = NavigationStops(
        NavigationStop(home, 600, NavigationStopKind.DESTINATION),
        waypoints.map { NavigationStop(it, 120, NavigationStopKind.WAYPOINT) })
    private inner class Host : RequestPickupFlow.Host {
        override var authority: Any? = "driver-one"
        override var requestsFresh = true
        override var pickupStatus = ""
        override var hasPickup = false
        override var pickupBusy = false
        override var sharingRunning = false
        override var routeFailure: String? = null
        override var pickupMessage = ""
        var target: RequestedRide? = RequestedRide("request-one", "시험 탑승자", station, "QUEUED")
        var current: NavigationStops? = route()
        var otherActive = false
        var insertions = 0
        var starts = 0
        var refreshes = 0
        var clock = 0L
        override fun request(id: String) = target?.takeIf { it.id == id }
        override fun hasOtherActiveRequest(id: String) = otherActive
        override fun canStart(id: String) = !hasPickup && !pickupBusy
        override fun refreshRequests() { refreshes++ }
        override fun prepareRoute(retry: Boolean) { }
        override fun stops() = current
        override fun select(place: PickupPlace) = current?.resolve(place) != null
        override fun insert(place: PickupPlace): Boolean { insertions++; current = null; return true }
        override fun clearEnded() { hasPickup = false; pickupStatus = "" }
        override fun start(ride: RequestedRide) { starts++; pickupBusy = true }
        fun flow() = RequestPickupFlow(this) { clock }
    }
    @Test fun addsOnceWaitsForProofAndStartsOnlyAfterConfirmation() {
        val host = Host(); val flow = host.flow()
        flow.begin("request-one"); flow.tick()
        repeat(10) { flow.tick(); flow.confirm(true) }
        assertEquals(1, host.insertions); assertEquals(0, host.starts)
        host.current = route(station); flow.tick()
        assertEquals(RequestPickupFlow.Stage.READY, flow.stage)
        flow.confirm(false); assertEquals(0, host.starts)
        flow.confirm(true); repeat(10) { flow.tick(); flow.confirm(true) }
        assertEquals(1, host.starts)
        host.pickupBusy = false; host.hasPickup = true; host.pickupStatus = "ACTIVE"; flow.tick()
        assertEquals(RequestPickupFlow.Stage.DONE, flow.stage)
    }
    @Test fun reusesExistingStopAndRejectsChangedDestinationBeforeConfirm() {
        val host = Host(); host.current = route(station); val flow = host.flow()
        flow.begin("request-one"); flow.tick()
        assertEquals(0, host.insertions)
        host.current = NavigationStops(NavigationStop(PickupPlace("다른 목적지", 38.0, 128.0), 600, NavigationStopKind.DESTINATION), listOf(NavigationStop(station, 120, NavigationStopKind.WAYPOINT)))
        flow.confirm(true)
        assertEquals(RequestPickupFlow.Stage.FAILED, flow.stage); assertEquals(0, host.starts)
    }
    @Test fun staleServerStateCancelledRequestAndChangedDriverNeverStart() {
        for (kind in 0..2) {
            val host = Host(); host.current = route(station); val flow = host.flow()
            flow.begin("request-one"); flow.tick()
            when (kind) { 0 -> host.requestsFresh = false; 1 -> host.target = null; else -> host.authority = "another-driver" }
            flow.confirm(true)
            assertEquals(0, host.starts)
            assertNotEquals(RequestPickupFlow.Stage.READY, flow.stage)
            if (kind == 2) assertNull(flow.ride)
        }
    }
    @Test fun activePickupBlocksAnotherAndEndedPickupCanPrepareNext() {
        val host = Host(); host.hasPickup = true; host.pickupStatus = "ACTIVE"
        val flow = host.flow(); flow.begin("request-one")
        assertEquals(RequestPickupFlow.Stage.IDLE, flow.stage)
        host.hasPickup = false; host.otherActive = true
        flow.begin("request-one"); assertEquals(RequestPickupFlow.Stage.IDLE, flow.stage)
        host.otherActive = false; host.hasPickup = true
        host.pickupStatus = "COMPLETED"; host.sharingRunning = true
        flow.begin("request-one"); flow.tick()
        assertEquals(0, host.insertions)
        host.sharingRunning = false; flow.tick()
        assertEquals(1, host.insertions)
    }
    @Test fun timeoutStopsLoadingAndDoesNotRepeatWaypointOrStartOnRetry() {
        val host = Host(); val flow = host.flow(); flow.begin("request-one"); flow.tick()
        host.clock = 60000; flow.tick()
        assertEquals(RequestPickupFlow.Stage.FAILED, flow.stage)
        repeat(5) { flow.tick() }; assertEquals(1, host.insertions)
        host.current = route(station); flow.begin("request-one"); flow.tick()
        assertEquals(RequestPickupFlow.Stage.READY, flow.stage); assertEquals(1, host.insertions)
        flow.cancel(); flow.confirm(true); assertEquals(0, host.starts)
    }
    @Test fun everyEndedRequestIsRejectedAndSecondPickupWaitsForCompletion() {
        for (state in listOf("CANCELLED", "EXPIRED", "REJECTED", "ACTIVE")) {
            val host = Host(); host.current = route(station); val flow = host.flow()
            flow.begin("request-one"); flow.tick()
            host.target = host.target!!.copy(status = state)
            flow.confirm(true); assertEquals(0, host.starts)
        }
        val host = Host(); host.current = route(station); val flow = host.flow()
        flow.begin("request-one"); flow.tick(); flow.confirm(true)
        host.pickupBusy = false; host.hasPickup = true; host.pickupStatus = "ACTIVE"; flow.tick()
        flow.consumeDone()
        val second = PickupPlace("다음 만날 장소", 37.3, 127.3)
        host.target = RequestedRide("request-two", "다음 탑승자", second, "QUEUED")
        flow.begin("request-two"); assertEquals(RequestPickupFlow.Stage.IDLE, flow.stage)
        host.pickupStatus = "COMPLETED"
        flow.begin("request-two"); flow.tick()
        assertEquals(1, host.insertions)
        host.current = route(station, second); flow.tick(); flow.confirm(true)
        assertEquals(2, host.starts)
    }

    @Test fun staleRouteClosesReadyActionAndLostStartReplyDoesNotAutoRetry() {
        val host = Host(); host.current = route(station); val flow = host.flow()
        flow.begin("request-one"); flow.tick(); host.current = null; flow.confirm(true)
        assertEquals(0, host.starts)
        host.current = route(station); flow.tick(); flow.confirm(true)
        host.pickupBusy = false; host.pickupMessage = "시작 응답 없음"; flow.tick()
        assertEquals(RequestPickupFlow.Stage.FAILED, flow.stage)
        repeat(10) { flow.tick() }; assertEquals(1, host.starts)
    }
}
