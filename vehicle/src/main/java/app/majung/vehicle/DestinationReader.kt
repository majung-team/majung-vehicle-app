package app.majung.vehicle

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import ai.pleos.playground.navi.constants.NaviMode
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.DrivingInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.RouteStateInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import java.time.Instant

/** All mutable state is confined to the main thread. No raw SDK data is logged. */
class DestinationReader(private val context: Context) {
    val selection = DestinationSelection()
    val telemetry = VehicleTelemetry()
    var telemetryChanged: (() -> Unit)? = null
    // Debug diagnostics contain timings and equality flags only, never SDK payloads.
    var timingObserver: ((SdkTimingEvent) -> Unit)? = null
    private var lastLocationValue: Pair<Double, Double>? = null
    private var lastDestinationValue: Triple<Double, Double, Long>? = null
    private var lastDrivingEta: Long? = null

    private fun timing(type: SdkTimingType, changed: Boolean? = null) {
        if (BuildConfig.DEBUG) timingObserver?.invoke(SdkTimingEvent(type, SystemClock.elapsedRealtime(), changed))
    }

    private val handler = Handler(Looper.getMainLooper())
    private var helper: NaviHelper? = null
    private var listener: NaviHelperEventListener? = null
    private var generation = 0
    private var connected = false
    private val routeValidation = RouteValidation()
    private val validationRetry = Runnable { pollTelemetry() }

    private fun invalidateRoute() {
        handler.removeCallbacks(validationRetry)
        routeValidation.invalidate()
        selection.invalidate()
        telemetry.invalidate()
        telemetryChanged?.invoke()
    }
    var status = "내비게이션 연결 전"
        private set

    fun connect() {
        if (helper != null) return
        val version = ++generation
        status = "내비게이션 연결 중"
        val instance = try { NaviHelper(context.applicationContext) }
        catch (_: Exception) { status = "내비게이션 연결 실패"; return }
        helper = instance
        val callbacks = object : NaviHelperEventListener {
            private fun update(action: () -> Unit) {
                handler.post { if (generation == version && connected) action() }
            }
            override fun onCurrentLocationInfo(currentLocationInfo: CurrentLocationInfo) = update {
                val value = currentLocationInfo.latitude to currentLocationInfo.longitude
                timing(SdkTimingType.LOCATION, lastLocationValue?.let { it != value })
                lastLocationValue = value
                if (!routeValidation.ready) return@update
                telemetry.location(currentLocationInfo.latitude, currentLocationInfo.longitude,
                    SystemClock.elapsedRealtime(), Instant.now())
                telemetryChanged?.invoke()
            }
            override fun onDrivingInfo(drivingInfo: DrivingInfo) = update {
                val value = drivingInfo.destination.duration.toLong()
                timing(SdkTimingType.DRIVING_ETA, lastDrivingEta?.let { it != value })
                lastDrivingEta = value
            }
            override fun onDestinationInfo(destinationInfo: DestinationInfo) = update {
                val value = Triple(destinationInfo.latitude, destinationInfo.longitude, destinationInfo.remainTime.toLong())
                timing(SdkTimingType.DESTINATION_ETA, lastDestinationValue?.let { it != value })
                lastDestinationValue = value
                val candidate = DestinationSelection().apply {
                    receive(destinationInfo.name, destinationInfo.latitude,
                        destinationInfo.longitude, SystemClock.elapsedRealtime())
                }.current(SystemClock.elapsedRealtime())
                if (!routeValidation.receiveDestination(candidate, SystemClock.elapsedRealtime())) {
                    if (!routeValidation.ready) {
                        selection.invalidate()
                        telemetry.invalidate()
                        telemetryChanged?.invoke()
                    }
                    return@update
                }
                handler.removeCallbacks(validationRetry)
                selection.receive(candidate!!.label, candidate.latitude, candidate.longitude, SystemClock.elapsedRealtime())
                telemetry.destination(candidate, destinationInfo.remainTime.toLong(),
                    SystemClock.elapsedRealtime(), Instant.now())
                timing(SdkTimingType.LOCATION_REQUEST)
                requestSdk { helper?.getCurrentLocationInfo() }
                telemetryChanged?.invoke()
                status = if (selection.current(SystemClock.elapsedRealtime()) == null)
                    "내비게이션에서 목적지를 선택해주세요" else "내비게이션 목적지를 확인했어요"
            }
            override fun onRouteStateInfo(routeStateInfo: RouteStateInfo) = update {
                timing(SdkTimingType.ROUTE_STATE)
                // 3.0.5 emulator returns isRouting=false during guidance. Treat true as
                // unverified; the published field description and observed value differ.
                val guiding = routeStateInfo.termsAgreement && !routeStateInfo.isRouting &&
                    routeStateInfo.naviMode in setOf(NaviMode.ROUTE_GUIDING, NaviMode.SIMULATION_GUIDING)
                val wasReady = routeValidation.ready
                if (routeValidation.receiveState(guiding, SystemClock.elapsedRealtime())) {
                    timing(SdkTimingType.DESTINATION_REQUEST)
                    requestSdk { helper?.getDestinationInfo() }
                } else if (wasReady && !routeValidation.ready) {
                    selection.invalidate()
                    telemetry.invalidate()
                    telemetryChanged?.invoke()
                    status = "안내 경로를 다시 확인하고 있어요"
                }
            }
            override fun onRouteStarted(routeStartInfo: RouteStartInfo) = update {
                timing(SdkTimingType.ROUTE_STARTED)
                invalidateRoute()
                handler.postDelayed(validationRetry, 5000)
                status = "경로 탐색 중 · 완료 후 목적지를 다시 확인해주세요"
            }
            override fun onDestinationChanged() = update {
                timing(SdkTimingType.DESTINATION_CHANGED)
                invalidateRoute()
                handler.postDelayed(validationRetry, 5000)
                status = "목적지가 바뀌었어요 · 다시 확인해주세요"
            }
            override fun onRoutingCompleted() = update {
                timing(SdkTimingType.ROUTING_COMPLETED)
                invalidateRoute()
                query()
            }
            override fun onReRouteEnded() = update {
                timing(SdkTimingType.REROUTE_ENDED)
                invalidateRoute()
                query()
            }
            override fun onRouteCancelled() = update {
                timing(SdkTimingType.ROUTE_CANCELLED)
                invalidateRoute()
                handler.postDelayed(validationRetry, 5000)
                status = "경로가 취소됐어요"
            }
            override fun onError(errorCode: NaviErrorCode) = update {
                timing(SdkTimingType.ERROR)
                invalidateRoute()
                handler.postDelayed(validationRetry, 5000)
                status = "내비게이션 정보를 확인하지 못했어요"
            }
        }
        listener = callbacks
        try {
            instance.initialize(onConnected = {
                handler.post {
                    if (generation == version) {
                        connected = true
                        timing(SdkTimingType.CONNECTED)
                        instance.addListener(callbacks)
                        query()
                    }
                }
            }, onFailed = {
                handler.post { if (generation == version) disconnect() }
            })
        } catch (_: Exception) { disconnect() }
    }

    private fun requestSdk(action: () -> Unit) {
        try { action() }
        catch (_: Exception) {
            invalidateRoute()
            status = "내비게이션 조회 실패 · 경로를 다시 확인하고 있어요"
            handler.postDelayed(validationRetry, 5000)
        }
    }

    fun pollTelemetry() {
        if (!connected) return
        val wasReady = routeValidation.ready
        if (!routeValidation.requestState(SystemClock.elapsedRealtime())) return
        if (wasReady && !routeValidation.ready) {
            selection.invalidate()
            telemetry.invalidate()
            telemetryChanged?.invoke()
        }
        try {
            timing(SdkTimingType.ROUTE_STATE_REQUEST)
            helper?.getRouteStateInfo()
        } catch (_: Exception) {
            invalidateRoute()
            status = "경로 상태 조회 실패 · 다시 확인해주세요"
        }
        // Recovery also works before a pickup exists, without relying on the service.
        handler.removeCallbacks(validationRetry)
        handler.postDelayed(validationRetry, 5000)
    }

    fun query() {
        if (!connected) return
        status = "선택한 목적지와 경로 확인 중"
        pollTelemetry()
    }

    fun disconnect() {
        timing(SdkTimingType.DISCONNECTED)
        lastLocationValue = null
        lastDestinationValue = null
        lastDrivingEta = null
        generation++
        connected = false
        invalidateRoute()
        val instance = helper
        helper = null
        try {
            listener?.let { instance?.removeListener(it) }
            instance?.release()
        } catch (_: Exception) { /* No raw SDK error logging. */ }
        listener = null
        status = "내비게이션 연결 해제 · 이전 장소 사용 중지"
    }
}
