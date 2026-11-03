package app.majung.vehicle

internal data class RequestedRide(val id: String, val nickname: String, val place: PickupPlace, val status: String)

/** Preparation may change navigation; sharing starts only after a second, explicit confirmation. */
internal class RequestPickupFlow(private val host: Host, private val now: () -> Long) {
    interface Host {
        val authority: Any?
        val requestsFresh: Boolean
        val pickupStatus: String
        val hasPickup: Boolean
        val pickupBusy: Boolean
        val sharingRunning: Boolean
        val routeFailure: String?
        val pickupMessage: String
        fun request(id: String): RequestedRide?
        fun canStart(id: String): Boolean
        fun hasOtherActiveRequest(id: String): Boolean
        fun refreshRequests()
        fun prepareRoute(retry: Boolean)
        fun stops(): NavigationStops?
        fun select(place: PickupPlace): Boolean
        fun insert(place: PickupPlace): Boolean
        fun clearEnded()
        fun start(ride: RequestedRide)
    }
    enum class Stage { IDLE, PREPARING, READY, STARTING, FAILED, DONE }
    var stage = Stage.IDLE
        private set
    var ride: RequestedRide? = null
        private set
    var message = ""
        private set
    private var owner: Any? = null
    private var deadline = 0L
    private var inserted = false
    private var baseline: NavigationStops? = null
    private var updating = false
    val visible: Boolean get() = stage !in setOf(Stage.IDLE, Stage.DONE)

    fun begin(id: String) {
        if (stage in setOf(Stage.PREPARING, Stage.READY, Stage.STARTING)) return
        val item = host.request(id) ?: return
        if (item.status != "QUEUED" || host.authority == null || host.pickupBusy) return
        if ((host.hasPickup && host.pickupStatus !in ended) || host.hasOtherActiveRequest(id)) return
        owner = host.authority
        ride = item
        inserted = false
        baseline = null
        deadline = now() + 60000
        stage = Stage.PREPARING
        message = "요청 상태와 내비 경로를 확인하고 있어요."
        updating = true
        try {
            if (host.hasPickup) host.clearEnded()
            host.refreshRequests()
            host.prepareRoute(retry = true)
        } finally { updating = false }
    }

    fun tick() {
        if (updating) return
        if (owner != null && owner != host.authority) {
            ride = null
            owner = null
            fail("운전자 연결이 바뀌었어요. 요청을 다시 선택해주세요.")
            return
        }
        if (stage !in setOf(Stage.PREPARING, Stage.READY, Stage.STARTING)) return
        updating = true
        try {
            val target = ride ?: return
            if (owner != host.authority || owner == null) { fail("운전자 연결이 바뀌었어요. 요청을 다시 선택해주세요."); return }
            if (stage == Stage.STARTING) {
                if (!host.pickupBusy) {
                    if (host.hasPickup && host.pickupStatus in setOf("ACTIVE", "ARRIVED")) stage = Stage.DONE
                    else fail(host.pickupMessage.ifBlank { "시작 결과를 확인하지 못했어요. 요청 목록에서 다시 확인해주세요." })
                }
                return
            }
            if (now() >= deadline) { fail("경로 준비가 늦어지고 있어요. 내비 안내를 확인한 뒤 다시 시도해주세요."); return }
            if (host.hasPickup || host.hasOtherActiveRequest(target.id)) { fail("다른 픽업이 진행 중이에요. 먼저 완료해주세요."); return }
            if (!host.canStart(target.id)) { fail("차량 연결 상태를 확인한 뒤 다시 선택해주세요."); return }
            val current = host.request(target.id)
            if (host.requestsFresh && (current == null || current.status != "QUEUED" || !current.place.sameCoordinates(target.place))) {
                fail("요청이 취소·만료됐거나 장소가 바뀌었어요. 목록에서 다시 확인해주세요."); return
            }
            if (!host.requestsFresh) { stage = Stage.PREPARING; message = "서버에서 요청 상태를 다시 확인하고 있어요."; return }
            if (host.sharingRunning) { stage = Stage.PREPARING; message = "이전 픽업의 위치 공유를 정리하고 있어요."; return }
            host.prepareRoute(retry = false)
            host.routeFailure?.let { fail(it); return }
            val route = host.stops()
            if (route == null) { stage = Stage.PREPARING; message = "내비 경로와 만날 장소를 확인하고 있어요."; return }
            val before = baseline
            if (before != null && !route.preserves(before)) { fail("기존 내비 경로가 바뀌었어요. 요청을 다시 선택해주세요."); return }
            if (route.resolve(target.place) != null) {
                if (baseline == null) baseline = route
                if (host.select(target.place)) { stage = Stage.READY; message = "경로를 확인했어요. 마중을 시작하면 위치를 공유해요." }
                return
            }
            if (!inserted) {
                baseline = route
                inserted = true // Never repeat a navigation mutation from polling or a lost callback.
                stage = Stage.PREPARING
                message = "최종 목적지는 유지하고 만날 장소를 경유지로 추가하고 있어요."
                if (!host.insert(target.place)) fail(host.routeFailure ?: "경유지를 추가하지 못했어요. 내비 경로를 확인해주세요.")
            } else { stage = Stage.PREPARING; message = "추가한 경유지가 맞는지 확인하고 있어요." }
        } finally { updating = false }
    }

    fun confirm(allowed: Boolean) {
        if (!allowed || stage != Stage.READY) return
        tick()
        if (stage != Stage.READY) return
        val target = ride ?: return
        if (!host.canStart(target.id) || !host.requestsFresh || host.pickupBusy) return
        stage = Stage.STARTING
        message = "마중을 시작하고 있어요."
        host.start(target)
    }
    fun cancel() {
        if (stage == Stage.STARTING) return
        stage = Stage.IDLE
        ride = null
        owner = null
        baseline = null
        message = ""
        // Do not automatically remove a waypoint: the driver may have changed navigation meanwhile.
    }
    fun consumeDone() { if (stage == Stage.DONE) { stage = Stage.IDLE; ride = null; owner = null } }
    private fun fail(reason: String) { stage = Stage.FAILED; message = reason }
    private val ended = setOf("COMPLETED", "CANCELLED", "EXPIRED")
}
