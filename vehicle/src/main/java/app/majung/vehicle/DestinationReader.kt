package app.majung.vehicle

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.constants.NaviMode
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.DrivingInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.WaypointInfo
import ai.pleos.playground.navi.data.WaypointChangedInfo
import ai.pleos.playground.navi.data.WaypointArrivedInfo
import ai.pleos.playground.navi.data.RecentDestinationInfo
import ai.pleos.playground.navi.data.RequestWaypointInfo
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
    private var awaitingDestination: DestinationInfo? = null
    private var destinationReceivedAt = 0L
    private var route: NavigationStops? = null
    private var routeReceivedAt = 0L
    private var desiredPickup: PickupPlace? = null
    private var selectedSdkPlace: SdkPickupPlace? = null
    private var insertion: WaypointInsertion? = null
    private var requiredInsertion: WaypointInsertion? = null
    private var recentCallback: ((List<SdkPickupPlace>) -> Unit)? = null
    var failure: NavigationFailure? = null
        private set
    private var consecutiveTimeouts = 0
    private var responsePending = false
    private val responseTimeout = Runnable {
        responsePending = false
        fail(NavigationFailure.RESPONSE_TIMEOUT, retry = ++consecutiveTimeouts < 3)
    }
    private val recentTimeout = Runnable {
        recentCallback = null
        fail(NavigationFailure.RECENT_PLACES_TIMEOUT)
    }
    private val insertionTimeout = Runnable { fail(NavigationFailure.WAYPOINT_CONFIRMATION_TIMEOUT) }
    val addingWaypoint: Boolean get() = insertion != null

    private fun fail(reason: NavigationFailure, retry: Boolean = false) {
        failure = reason
        cancelResponseTimeout()
        handler.removeCallbacks(recentTimeout)
        handler.removeCallbacks(insertionTimeout)
        recentCallback = null
        insertion = null
        invalidateRoute()
        status = reason.message
        android.util.Log.w("MajungNavigation", "code=${reason.name}")
        if (retry && connected) handler.postDelayed(validationRetry, 5000)
    }

    fun currentStops(): NavigationStops? = route?.takeIf {
        val age = SystemClock.elapsedRealtime() - routeReceivedAt
        routeValidation.ready && age in 0 until 10000
    }

    fun useFinalDestination() {
        desiredPickup = null
        selectedSdkPlace = null
        insertion = null
        requiredInsertion = null
        handler.removeCallbacks(insertionTimeout)
        invalidateRoute()
        query()
    }

    fun loadRecentPlaces(callback: (List<SdkPickupPlace>) -> Unit) {
        if (!connected) { fail(NavigationFailure.DISCONNECTED); return }
        recentCallback = callback
        handler.removeCallbacks(recentTimeout)
        handler.postDelayed(recentTimeout, 8000)
        requestSdk { requireNotNull(helper).getRecentDestinationInfo() }
    }

    fun addPickupWaypoint(place: PickupPlace): Boolean =
        addPickupWaypoint(SdkPickupPlace(place, "", "", "0"))

    fun addPickupWaypoint(selected: SdkPickupPlace): Boolean {
        val place = selected.place
        if (!connected) { fail(NavigationFailure.DISCONNECTED); return false }
        val previous = currentStops() ?: run { fail(NavigationFailure.ROUTE_UNVERIFIED); return false }
        if (validPlace(place.label, place.latitude, place.longitude, SystemClock.elapsedRealtime()) == null ||
            (place.latitude == 0.0 && place.longitude == 0.0)) {
            fail(NavigationFailure.INVALID_PLACE)
            return false
        }
        if (insertion != null) return false
        if (previous.waypoints.size >= 2) { fail(NavigationFailure.WAYPOINT_LIMIT); return false }
        if (previous.all.any { it.place.sameCoordinates(place) }) { fail(NavigationFailure.DUPLICATE_PLACE); return false }
        insertion = WaypointInsertion(place, previous)
        requiredInsertion = insertion
        desiredPickup = place
        selectedSdkPlace = selected
        invalidateRoute()
        status = "경유지 추가 후 장소를 확인하고 있어요"
        failure = null
        handler.removeCallbacks(insertionTimeout)
        handler.postDelayed(insertionTimeout, 45000)
        val requested = requestSdk {
            requireNotNull(helper).addWaypoint(RequestWaypointInfo(latitude = place.latitude, longitude = place.longitude,
                poiName = place.label, waypointIndex = WaypointIndex.FIRST, poiId = selected.poiId,
                address = selected.address, poiSubId = selected.poiSubId))
        }
        if (requested) handler.postDelayed(validationRetry, 5000)
        return requested
    }
    private val routeValidation = RouteValidation()
    private val validationRetry = Runnable { pollTelemetry() }

    private fun invalidateRoute() {
        cancelResponseTimeout()
        handler.removeCallbacks(validationRetry)
        routeValidation.invalidate()
        awaitingDestination = null
        route = null
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
        catch (_: SecurityException) { fail(NavigationFailure.PERMISSION_DENIED); return }
        catch (_: Exception) { fail(NavigationFailure.CONNECTION_FAILED); return }
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
                // Only consume destination and waypoint replies from the same validation cycle.
                if (!routeValidation.beginWaypointResponse(SystemClock.elapsedRealtime())) return@update
                awaitingDestination = destinationInfo
                destinationReceivedAt = SystemClock.elapsedRealtime()
                timing(SdkTimingType.WAYPOINT_REQUEST)
                requestSdk { helper?.getWaypointInfo() }
            }
            override fun onWayPointInfo(waypointInfoList: List<WaypointInfo>) = update {
                timing(SdkTimingType.WAYPOINT)
                val final = awaitingDestination ?: return@update
                awaitingDestination = null
                val now = SystemClock.elapsedRealtime()
                cancelResponseTimeout()
                consecutiveTimeouts = 0
                val finalPlace = validPlace(final.name, final.latitude, final.longitude, now)
                // Retain unknown stops in the fingerprint and ambiguity checks, but never
                // resolve them as usable pickup targets or estimates.
                val waypointPlaces = waypointInfoList.map { PickupPlace(it.name, it.latitude, it.longitude) }
                val valid = finalPlace != null && now - destinationReceivedAt in 0 until 4000
                if (!valid) {
                    fail(NavigationFailure.INVALID_PLACE)
                    return@update
                }
                val stops = NavigationStops(
                    NavigationStop(finalPlace!!, final.remainTime.toLong(), NavigationStopKind.DESTINATION),
                    WaypointCoordinates.resolve(waypointInfoList.mapIndexed { index, point ->
                        SdkWaypoint(waypointPlaces[index], point.remainTime.toLong(), point.poiId)
                    }, selectedSdkPlace))
                if (!routeValidation.receiveRoute(stops.all.map { it.place }, now)) {
                    if (!routeValidation.ready) {
                        route = null
                        selection.invalidate()
                        telemetry.invalidate()
                        telemetryChanged?.invoke()
                    }
                    return@update
                }
                route = stops
                routeReceivedAt = now
                val pending = requiredInsertion
                val target = if (pending != null) pending.confirmed(stops) else
                    desiredPickup?.let { stops.resolve(it) } ?: if (desiredPickup == null) stops.resolve(stops.destination.place) else null
                if (target == null) {
                    val reason = when {
                        stops.waypoints.any { !it.usable } -> NavigationFailure.WAYPOINT_COORDINATES_UNAVAILABLE
                        pending != null && !pending.preservesPrevious(stops) -> NavigationFailure.ROUTE_CHANGED
                        else -> NavigationFailure.PICKUP_NOT_FOUND
                    }
                    fail(reason)
                    return@update
                }
                if (pending != null) {
                    insertion = null
                    requiredInsertion = null
                }
                handler.removeCallbacks(insertionTimeout)
                failure = null
                handler.removeCallbacks(validationRetry)
                selection.receive(target.place.label, target.place.latitude, target.place.longitude, now)
                telemetry.destinations(stops.all, now, Instant.now())
                timing(SdkTimingType.LOCATION_REQUEST)
                requestSdk { helper?.getCurrentLocationInfo() }
                status = if (target.kind == NavigationStopKind.WAYPOINT) "픽업 경유지를 확인했어요" else "내비게이션 목적지를 확인했어요"
                telemetryChanged?.invoke()
            }
            override fun onRecentDestinationInfo(recentDestinationInfoList: List<RecentDestinationInfo>) = update {
                val callback = recentCallback ?: return@update
                recentCallback = null
                handler.removeCallbacks(recentTimeout)
                callback(recentDestinationInfoList.take(20).mapNotNull {
                    val place = validPlace(it.name, it.latitude, it.longitude, SystemClock.elapsedRealtime())
                        ?.takeIf { point -> point.latitude != 0.0 || point.longitude != 0.0 }
                    place?.let { point -> SdkPickupPlace(point, it.poiId, it.address, it.poiSubId ?: "0") }
                }.distinctBy { it.place.latitude to it.place.longitude })
            }
            override fun onWaypointChanged(waypointChangedInfo: WaypointChangedInfo) = update {
                timing(SdkTimingType.WAYPOINT_CHANGED)
                invalidateRoute()
                handler.postDelayed(validationRetry, 5000)
                status = "경유지가 바뀌었어요 · 픽업 장소를 다시 확인하고 있어요"
            }
            override fun onWayPointArrived(waypointArrivedInfo: WaypointArrivedInfo) = update {
                // Arrival handling belongs to 5.1. Never treat an index alone as a pickup identity.
                invalidateRoute()
                handler.postDelayed(validationRetry, 5000)
            }
            override fun onRouteStateInfo(routeStateInfo: RouteStateInfo) = update {
                timing(SdkTimingType.ROUTE_STATE)
                // 3.0.5 emulator returns isRouting=false during guidance. Treat true as
                // unverified; the published field description and observed value differ.
                val guiding = routeStateInfo.termsAgreement && !routeStateInfo.isRouting &&
                    routeStateInfo.naviMode in setOf(NaviMode.ROUTE_GUIDING, NaviMode.SIMULATION_GUIDING)
                timing(if (guiding) SdkTimingType.GUIDANCE_VALID else SdkTimingType.GUIDANCE_INVALID)
                if (!guiding) cancelResponseTimeout()
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
                fail(when (errorCode) {
                    NaviErrorCode.CAUSE_LOW_BATTERY -> NavigationFailure.LOW_BATTERY
                    NaviErrorCode.CAUSE_SEARCH_ROUTE_OVER_DISTANCE -> NavigationFailure.ROUTE_TOO_LONG
                    NaviErrorCode.CAUSE_SEARCH_ROUTE_TOO_SHORT_DISTANCE -> NavigationFailure.ROUTE_TOO_SHORT
                    NaviErrorCode.CAUSE_SEARCH_ROUTE, NaviErrorCode.CAUSE_SEARCH_TRIP -> NavigationFailure.ROUTE_SEARCH_FAILED
                })
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
                handler.post { if (generation == version) { disconnect(); fail(NavigationFailure.CONNECTION_FAILED) } }
            })
        } catch (_: SecurityException) { disconnect(); fail(NavigationFailure.PERMISSION_DENIED) }
        catch (_: Exception) { disconnect(); fail(NavigationFailure.CONNECTION_FAILED) }
    }

    private fun validPlace(name: String, latitude: Double, longitude: Double, now: Long): PickupPlace? =
        DestinationSelection().apply { receive(name, latitude, longitude, now) }.current(now)

    private fun cancelResponseTimeout() {
        handler.removeCallbacks(responseTimeout)
        responsePending = false
    }

    private fun requestSdk(action: () -> Unit): Boolean =
        NavigationRequest.execute(action) { fail(it) }

    fun pollTelemetry() {
        if (!connected || (failure != null && failure != NavigationFailure.RESPONSE_TIMEOUT)) return
        if (consecutiveTimeouts >= 3) return
        val wasReady = routeValidation.ready
        if (!routeValidation.requestState(SystemClock.elapsedRealtime())) return
        awaitingDestination = null
        if (wasReady && !routeValidation.ready) {
            selection.invalidate()
            telemetry.invalidate()
            telemetryChanged?.invoke()
        }
        if (!responsePending) {
            responsePending = true
            handler.postDelayed(responseTimeout, 12000)
        }
        timing(SdkTimingType.ROUTE_STATE_REQUEST)
        if (!requestSdk { requireNotNull(helper).getRouteStateInfo() }) return
        // Recovery also works before a pickup exists, without relying on the service.
        handler.removeCallbacks(validationRetry)
        handler.postDelayed(validationRetry, 5000)
    }

    fun query() {
        if (!connected) { fail(NavigationFailure.DISCONNECTED); return }
        failure = null
        consecutiveTimeouts = 0
        status = "선택한 목적지와 경로 확인 중"
        pollTelemetry()
    }

    fun disconnect() {
        timing(SdkTimingType.DISCONNECTED)
        lastLocationValue = null
        lastDestinationValue = null
        lastDrivingEta = null
        generation++
        cancelResponseTimeout()
        handler.removeCallbacks(recentTimeout)
        connected = false
        recentCallback = null
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
