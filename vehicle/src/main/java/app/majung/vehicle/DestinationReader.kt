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
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.WaypointInfo
import ai.pleos.playground.navi.data.WaypointChangedInfo
import ai.pleos.playground.navi.data.WaypointArrivedInfo
import ai.pleos.playground.navi.data.RecentDestinationInfo
import ai.pleos.playground.navi.data.RequestWaypointInfo
import ai.pleos.playground.navi.data.RouteWithWaypointsRequest
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteStateInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import java.time.Instant

/** All mutable state is confined to the main thread. No raw SDK data is logged. */
class DestinationReader(private val context: Context) {
    val selection = DestinationSelection()
    val telemetry = VehicleTelemetry()
    var pickupArrived: ((PickupPlace) -> Unit)? = null
    var pickupPlace: (() -> PickupPlace?)? = null
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
    var previewPlaceLabel: String? = null
        private set
    var waitingForGuidance = false
        private set
    val preparationMessage: String get() = when {
        !connected -> "내비게이션에 연결하고 있어요."
        waitingForGuidance -> "내비에서 목적지를 검색하고 경로 안내를 시작해주세요."
        routeValidation.confirmationDelay(SystemClock.elapsedRealtime()) > 0 -> "장소를 받았어요. 경로가 같은지 한 번 더 확인하고 있어요."
        else -> "내비에서 안내 중인 장소와 경로를 확인하고 있어요."
    }
    private var lastPreparationRequestAt = -5000L
    private var routeRequestStartedAt = 0L
    private var routeResponseLatencyMs = 0L
    private var awaitingDestination: DestinationInfo? = null
    private var destinationReceivedAt = 0L
    private var route: NavigationStops? = null
    private var routeReceivedAt = 0L
    private var desiredPickup: PickupPlace? = null
    private var selectedSdkPlace: SdkPickupPlace? = null
    private val knownSdkPlaces = linkedMapOf<String, SdkPickupPlace>()
    private var insertion: WaypointInsertion? = null
    private var placeChange: PickupPlaceChange? = null
    private var reviewedProposal: PickupPlace? = null
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

    private fun confirmArrival(index: Int?, name: String) {
        val selected = pickupPlace?.invoke() ?: return
        val now = SystemClock.elapsedRealtime()
        val evidence = PickupArrivalEvidence.resolve(currentStops(), selected, index, name,
            telemetry.current(selected, now)) ?: return
        pickupArrived?.invoke(evidence)
    }

    fun referenceFor(place: PickupPlace): String? {
        val stop = currentStops()?.resolve(place) ?: return null
        if (stop.kind != NavigationStopKind.WAYPOINT) return null
        return knownSdkPlaces.values.filter { it.place.sameCoordinates(place) }.singleOrNull()?.poiId
    }

    /** Restore only with the server-confirmed place and our encrypted session reference. */
    fun restorePickupReference(place: PickupPlace?, reference: String?) {
        if (place == null || reference.isNullOrBlank() || placeChange != null || reviewedProposal != null) return
        if (desiredPickup?.sameCoordinates(place) == true && selectedSdkPlace?.poiId == reference) return
        if (!NavigationStop(place, 0, NavigationStopKind.WAYPOINT).usable) return
        val restored = SdkPickupPlace(place, reference, "", "0")
        desiredPickup = place
        selectedSdkPlace = restored
        knownSdkPlaces[reference] = restored
        invalidateRoute()
        if (connected) pollTelemetry()
    }

    fun useFinalDestination() {
        desiredPickup = null
        selectedSdkPlace = null
        knownSdkPlaces.clear()
        insertion = null
        requiredInsertion = null
        placeChange = null
        reviewedProposal = null
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
        val requestPlace = selected.withRequestIdentity()
        placeChange = null
        reviewedProposal = null
        insertion = WaypointInsertion(place, previous)
        requiredInsertion = insertion
        desiredPickup = place
        selectedSdkPlace = requestPlace
        knownSdkPlaces[requestPlace.poiId] = requestPlace
        invalidateRoute()
        status = "경유지 추가 후 장소를 확인하고 있어요"
        failure = null
        handler.removeCallbacks(insertionTimeout)
        handler.postDelayed(insertionTimeout, 45000)
        val requested = requestSdk {
            requireNotNull(helper).addWaypoint(RequestWaypointInfo(latitude = place.latitude, longitude = place.longitude,
                poiName = place.label, waypointIndex = WaypointIndex.FIRST, poiId = requestPlace.poiId,
                address = requestPlace.address, poiSubId = requestPlace.poiSubId, poiProvider = requestPlace.poiProvider))
        }
        if (requested) handler.postDelayed(validationRetry, 5000)
        return requested
    }
    fun verifiedPlaceChange(next: PickupPlace): NavigationStop? {
        val stops = currentStops() ?: return null
        val plan = placeChange?.takeIf { it.requested.sameCoordinates(next) }
        if (plan != null) return plan.confirmed(stops)
        return if (reviewedProposal?.sameCoordinates(next) == true) stops.resolve(next) else null
    }

    /** After process loss, review the existing route only; never rebuild an unknown route. */
    fun reviewProposedPlace(next: PickupPlace, proposalId: String) {
        if (!NavigationStop(next, 0, NavigationStopKind.WAYPOINT).usable) return
        val selected = SdkPickupPlace(next, "majung:proposal:$proposalId", "", "0", "majung")
        knownSdkPlaces[selected.poiId] = selected
        selectedSdkPlace = selected
        desiredPickup = next
        reviewedProposal = next
        placeChange = null
        requiredInsertion = null
        insertion = null
        invalidateRoute()
        query()
    }

    fun changePickupPlace(old: PickupPlace, next: PickupPlace, proposalId: String): Boolean {
        val previous = currentStops() ?: return false
        val plan = PickupPlaceChange.plan(previous, old, next) ?: return false
        if (!connected || insertion != null) return false
        val points = plan.waypoints.map { point ->
            if (point.sameCoordinates(next)) SdkPickupPlace(next, "majung:proposal:$proposalId", "", "0", "majung") else
            knownSdkPlaces.values.singleOrNull { it.place.sameCoordinates(point) }
                ?: SdkPickupPlace(point, "", "", "0").withRequestIdentity()
        }
        points.forEach { knownSdkPlaces[it.poiId] = it }
        val final = previous.destination.place
        val finalIdentity = SdkPickupPlace(final, "", "", "0").withRequestIdentity()
        reviewedProposal = null
        placeChange = plan
        requiredInsertion = null
        desiredPickup = next
        selectedSdkPlace = points.single { it.place.sameCoordinates(next) }
        invalidateRoute()
        status = "새 장소와 기존 경유지를 확인하고 있어요"
        handler.removeCallbacks(insertionTimeout)
        handler.postDelayed(insertionTimeout, 45000)
        val sent = requestSdk {
            requireNotNull(helper).requestRouteWithWaypoints(RouteWithWaypointsRequest(
                RouteInfo(final.latitude, final.longitude, poiName = final.label, poiId = finalIdentity.poiId,
                    address = "", poiSubId = "0", poiProvider = "majung"),
                points.mapIndexed { index, point -> RequestWaypointInfo(point.place.latitude, point.place.longitude,
                    point.place.label, if (index == 0) WaypointIndex.FIRST else WaypointIndex.SECOND,
                    point.poiId, point.address, point.poiSubId, point.poiProvider) }
            ))
        }
        if (sent) handler.postDelayed(validationRetry, 5000)
        return sent
    }

    private val routeValidation = RouteValidation()
    private val validationRetry = Runnable { pollTelemetry() }

    private fun invalidateRoute() {
        cancelResponseTimeout()
        handler.removeCallbacks(validationRetry)
        routeValidation.invalidate()
        awaitingDestination = null
        route = null
        previewPlaceLabel = null
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
                    WaypointCoordinates.resolveKnown(waypointInfoList.mapIndexed { index, point ->
                        SdkWaypoint(waypointPlaces[index], point.remainTime.toLong(), point.poiId)
                    }, knownSdkPlaces.values.toList()))
                // Show the received name early, but never expose an actionable place or ETA
                // until both route observations pass validation.
                previewPlaceLabel = (desiredPickup?.let { stops.resolve(it)?.place }
                    ?: if (desiredPickup == null) stops.destination.place else null)?.label
                routeResponseLatencyMs = (now-routeRequestStartedAt).coerceIn(0L,3000L)
                if (!routeValidation.receiveRoute(stops.all.map { it.place }, now)) {
                    if (!routeValidation.ready) {
                        route = null
                        selection.invalidate()
                        telemetry.invalidate()
                        telemetryChanged?.invoke()
                        val delay = routeValidation.confirmationRequestDelay(now,routeResponseLatencyMs)
                        handler.removeCallbacks(validationRetry)
                        handler.postDelayed(validationRetry, delay.coerceAtLeast(200L))
                    }
                    return@update
                }
                route = stops
                routeReceivedAt = now
                val pending = requiredInsertion
                val target = if (placeChange != null) placeChange?.confirmed(stops) else if (pending != null) pending.confirmed(stops) else
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
                knownSdkPlaces.keys.retainAll(waypointInfoList.map { it.poiId }.toSet())
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
                    place?.let { point -> SdkPickupPlace(point, it.poiId, it.address, it.poiSubId ?: "0", it.poiProvider) }
                }.distinctBy { it.place.latitude to it.place.longitude })
            }
            override fun onWaypointChanged(waypointChangedInfo: WaypointChangedInfo) = update {
                timing(SdkTimingType.WAYPOINT_CHANGED)
                invalidateRoute()
                handler.postDelayed(validationRetry, 5000)
                status = "경유지가 바뀌었어요 · 픽업 장소를 다시 확인하고 있어요"
            }
            override fun onWayPointArrived(waypointArrivedInfo: WaypointArrivedInfo) = update {
                val index = when (waypointArrivedInfo.index) {
                    WaypointIndex.FIRST -> 0
                    WaypointIndex.SECOND -> 1
                    else -> -1
                }
                confirmArrival(index, waypointArrivedInfo.name)
                invalidateRoute()
                handler.postDelayed(validationRetry, 5000)
            }
            override fun onDestinationArrived(destinationArrivedInfo: DestinationArrivedInfo) = update {
                confirmArrival(null, destinationArrivedInfo.name)
                invalidateRoute()
                handler.postDelayed(validationRetry, 5000)
            }
            override fun onRouteStateInfo(routeStateInfo: RouteStateInfo) = update {
                timing(SdkTimingType.ROUTE_STATE)
                // 3.0.5 emulator returns isRouting=false during guidance. Treat true as
                // unverified; the published field description and observed value differ.
                val guiding = routeStateInfo.termsAgreement && !routeStateInfo.isRouting &&
                    routeStateInfo.naviMode in setOf(NaviMode.ROUTE_GUIDING, NaviMode.SIMULATION_GUIDING)
                val wasWaitingForGuidance = waitingForGuidance
                waitingForGuidance = !guiding
                timing(if (guiding) SdkTimingType.GUIDANCE_VALID else SdkTimingType.GUIDANCE_INVALID)
                if (!guiding) {
                    cancelResponseTimeout()
                    previewPlaceLabel = null
                }
                val wasReady = routeValidation.ready
                if (routeValidation.receiveState(guiding, SystemClock.elapsedRealtime())) {
                    timing(SdkTimingType.DESTINATION_REQUEST)
                    requestSdk { helper?.getDestinationInfo() }
                } else if (guiding && wasWaitingForGuidance && !wasReady) {
                    // Guidance became available between polls. Request a fresh validation;
                    // the unsolicited state callback itself is not route evidence.
                    pollTelemetry()
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
        val confirmationDelay = routeValidation.confirmationRequestDelay(SystemClock.elapsedRealtime(),routeResponseLatencyMs)
        if (confirmationDelay > 0) {
            handler.removeCallbacks(validationRetry)
            handler.postDelayed(validationRetry, confirmationDelay)
            return
        }
        val wasReady = routeValidation.ready
        if (!routeValidation.requestState(SystemClock.elapsedRealtime())) return
        lastPreparationRequestAt = SystemClock.elapsedRealtime()
        routeRequestStartedAt = lastPreparationRequestAt
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

    /** Preview starts itself; keep the existing route evidence and 5-second SDK cadence. */
    fun prepareForPickup(refresh: Boolean = false) {
        if (helper == null) {
            if (failure == null || refresh) connect()
            return
        }
        if (!connected || addingWaypoint) return
        if (refresh && failure != null) { query(); return }
        if (SystemClock.elapsedRealtime() - lastPreparationRequestAt >= 5000) pollTelemetry()
    }

    fun retryPickupPreparation() {
        if (failure in setOf(NavigationFailure.DISCONNECTED, NavigationFailure.CONNECTION_FAILED, NavigationFailure.PERMISSION_DENIED)) {
            disconnect()
            connect()
        } else if (helper == null) connect() else query()
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
        waitingForGuidance = false
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
