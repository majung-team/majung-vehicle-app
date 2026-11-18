package app.majung.vehicle.feature.pickup.controller

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import app.majung.vehicle.BuildConfig
import app.majung.vehicle.data.api.ApiRejected
import app.majung.vehicle.data.api.PickupApiClient
import app.majung.vehicle.data.model.PickupSnapshotResponse
import app.majung.vehicle.data.storage.MemoryPickupSessionStore
import app.majung.vehicle.data.storage.PickupSessionPersistence
import app.majung.vehicle.data.storage.PickupSessionStore
import app.majung.vehicle.data.storage.SavedPickupSession
import app.majung.vehicle.feature.vehicle.controller.VehicleRegistrationController
import app.majung.vehicle.navi.DestinationReader
import app.majung.vehicle.navi.PickupPlace
import app.majung.vehicle.navi.sameCoordinates
import java.util.concurrent.Executors
import org.json.JSONObject

/** One application-owned session; persistent credentials are injected through a session store. */
class PickupController(
    private val api: PickupApiClient = PickupApiClient(),
    private val sessionStore: PickupSessionStore = MemoryPickupSessionStore(),
    private val registration: VehicleRegistrationController? = null,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var driverToken: String? = null
    private var inviteToken: String? = null
    private var participantId: String? = null
    private var readinessSequence = 0L
    private var receivedReadinessSequence = 0L
    private var supportsReadinessReceipt = false
    private var receiptInFlight = false
    // Pickups started from an accepted ride request are managed in the request list, not by
    // invitation.
    private var requestedPickup = false
    private var uncertainCreate = false
    private var refreshing = false
    private var controlGeneration = 0
    private var refreshError = false
    private val vehiclePublisher =
        VehicleStatePublisher(
            api,
            worker,
            handler,
            object : VehicleStatePublisher.Host {
                override val needsSharingConfirmation
                    get() = this@PickupController.needsSharingConfirmation

                override val status
                    get() = this@PickupController.status

                override val pickupId
                    get() = this@PickupController.pickupId

                override val driverToken
                    get() = this@PickupController.driverToken

                override val place
                    get() = this@PickupController.place

                override val placeVersion
                    get() = this@PickupController.placeVersion

                override fun checkAutoConfirmProposal(reader: DestinationReader) =
                    this@PickupController.checkAutoConfirmProposal(reader)

                override fun persistSession() = this@PickupController.persistSession()

                override fun notifyChanged() = this@PickupController.notifyChanged()
            },
        )
    val vehicleMessage
        get() = vehiclePublisher.vehicleMessage

    var pickupId: String? = null
        private set

    var place: PickupPlace? = null
        private set

    var placeVersion = 1L
        private set

    var placeProposal: PickupPlace? = null
        private set

    var proposalId: String? = null
        private set

    var autoConfirmProposalId: String? = null
    var navigationReference: String? = null
        private set

    var status = ""
        private set

    var participantName: String? = null
        private set

    var quickMessage: String? = null
        private set

    private var lastQuickMessageAt: String? = null
    var onQuickMessageReceived: ((String) -> Unit)? = null
    var readiness = ""
        private set

    var busy = false
        private set

    var online = false
        private set

    var message = ""
        private set

    var needsSharingConfirmation = false
        private set

    fun confirmRecoveredSharing() {
        if (!needsSharingConfirmation || !online || status !in setOf("ACTIVE", "ARRIVED")) return
        needsSharingConfirmation = false
        message = ""
        notifyChanged()
    }

    var changed: (() -> Unit)? = null
    private val observers = linkedSetOf<() -> Unit>()

    fun addObserver(observer: () -> Unit) {
        observers.add(observer)
    }

    fun removeObserver(observer: () -> Unit) {
        observers.remove(observer)
    }

    private fun notifyChanged() {
        observers.toList().forEach { it() }
        changed?.invoke()
    }

    private var restoringSession = false
    private val sessionPersistence = PickupSessionPersistence(sessionStore)
    private var storageCleanupFailed = false

    fun restoreSession() {
        if (busy || pickupId != null || restoringSession) return
        restoringSession = true
        busy = true
        worker.execute {
            val result = runCatching { sessionPersistence.load() }
            handler.post {
                busy = false
                result.fold(
                    onSuccess = { saved ->
                        if (saved == null) {
                            restoringSession = false
                            notifyChanged()
                            return@fold
                        }
                        resetVehiclePublisher()
                        pickupId = saved.pickupId
                        driverToken = saved.driverToken
                        inviteToken = saved.inviteToken
                        vehiclePublisher.restoreSequence(saved.vehicleSequence)
                        navigationReference = saved.navigationReference
                        sessionPersistence.restored(saved)
                        status = "RESTORING"
                        online = false
                        message = "진행 중인 픽업을 서버에서 확인하고 있어요."
                        refresh()
                    },
                    onFailure = {
                        restoringSession = false
                        if (clearSavedSession()) message = "저장된 접근 정보를 읽을 수 없어 지웠어요."
                    },
                )
                notifyChanged()
            }
        }
    }

    private fun persistSession(): Boolean {
        val id = pickupId ?: return true
        val token = driverToken ?: return true
        val saved =
            SavedPickupSession(
                id,
                token,
                inviteToken,
                vehiclePublisher.vehicleSequence,
                navigationReference,
            )
        return try {
            sessionPersistence.save(saved)
            true
        } catch (_: Exception) {
            message = "접근 정보를 안전하게 저장하지 못했어요. 앱을 닫기 전에 픽업을 종료해주세요."
            false
        }
    }

    private fun clearSavedSession(): Boolean {
        return try {
            sessionPersistence.clear()
            storageCleanupFailed = false
            true
        } catch (_: Exception) {
            storageCleanupFailed = true
            message = "저장된 접근 정보를 지우지 못했어요. 새 픽업을 만들 수 없어요."
            false
        }
    }

    val canCreate: Boolean
        get() =
            !busy &&
                !restoringSession &&
                !storageCleanupFailed &&
                pickupId == null &&
                !uncertainCreate &&
                registration?.canCreate != false

    val canApprove: Boolean
        get() = !busy && online && status == "WAITING_APPROVAL" && participantId != null

    val canDisconnect: Boolean
        get() = !busy && online && status == "ACTIVE" && participantId != null && !requestedPickup

    val canArrive: Boolean
        get() = !busy && online && status == "ACTIVE"

    val canComplete: Boolean
        get() = !busy && online && status == "ARRIVED"

    val canCancel: Boolean
        get() = !busy && online && pickupId != null && status !in ended

    fun create(selectedPlace: PickupPlace, waypointReference: String? = null) {
        if (!canCreate) return
        uncertainCreate = true
        runRequest(
            { api.create(selectedPlace, registration?.vehicleToken) },
            { result ->
                val id = result.getString("pickupId")
                val owner = result.getString("driverToken")
                val invitation = result.getString("inviteToken")
                resetVehiclePublisher()
                pickupId = id
                driverToken = owner
                inviteToken = invitation
                place = selectedPlace
                navigationReference = waypointReference
                status = "INVITING"
                message = "픽업을 만들었어요. 초대 링크로 참여하면 바로 연결돼요."
                uncertainCreate = false
                persistSession()
            },
            creating = true,
        )
    }

    private var uncertainRequestId: String? = null

    fun canStartRequested(id: String): Boolean =
        canCreate || (!busy && pickupId == null && uncertainRequestId == id)

    fun startRequested(requestId: String, requestedPlace: PickupPlace, waypointReference: String?) {
        if (!canStartRequested(requestId)) return
        val token = registration?.vehicleToken ?: return
        uncertainRequestId = requestId
        uncertainCreate = true
        // Starting the same accepted request is recoverable on the server; it cannot create a
        // second pickup.
        runRequest(
            { api.requestApi("POST", "/vehicles/ride-requests/$requestId/start", token) },
            { result ->
                uncertainCreate = false
                uncertainRequestId = null
                resetVehiclePublisher()
                requestedPickup = true
                pickupId = result.getString("pickupId")
                driverToken = result.getString("driverToken")
                inviteToken = null
                place = requestedPlace
                navigationReference = waypointReference
                receiveSnapshot(result.getJSONObject("pickup"))
                needsSharingConfirmation = true
                message = "마중 요청을 픽업으로 연결했어요. 경로를 확인한 뒤 계속하기를 눌러 공유해주세요."
                persistSession()
            },
            creating = true,
        )
    }

    fun confirmProposedPlace(reader: DestinationReader) {
        if (busy || !online || status != "ACTIVE" || needsSharingConfirmation) return
        val proposed = placeProposal ?: return
        val proposal = proposalId ?: return
        val id = pickupId ?: return
        val token = driverToken ?: return
        val selected = reader.verifiedPlaceChange(proposed) ?: return
        val sample = reader.telemetry.current(proposed, SystemClock.elapsedRealtime()) ?: return
        val version = placeVersion
        val reference = reader.referenceFor(proposed)
        runRequest(
            {
                api.request(
                    "PUT",
                    "/$id/place-confirmation",
                    token,
                    JSONObject()
                        .put("proposalId", proposal)
                        .put("expectedPlaceVersion", version)
                        .put(
                            "place",
                            JSONObject()
                                .put("label", proposed.label)
                                .put("latitude", proposed.latitude)
                                .put("longitude", proposed.longitude),
                        )
                        .put("durationSeconds", selected.durationSeconds)
                        .put("sourceTime", sample.observedAt.toString()),
                )
            },
            { result ->
                receiveSnapshot(result)
                if (place?.sameCoordinates(proposed) == true) {
                    navigationReference = reference
                    persistSession()
                    message = "만날 장소를 바꿨어요. 새 경로의 위치와 시간을 공유해요."
                }
            },
        )
    }

    fun checkAutoConfirmProposal(reader: DestinationReader) {
        val target = autoConfirmProposalId ?: return
        val proposed = placeProposal ?: return
        if (proposalId != target) {
            autoConfirmProposalId = null
            return
        }
        val verified =
            reader.verifiedPlaceChange(proposed) != null &&
                reader.telemetry.current(proposed, SystemClock.elapsedRealtime()) != null
        if (verified && !busy && online && status == "ACTIVE" && !needsSharingConfirmation) {
            autoConfirmProposalId = null
            confirmProposedPlace(reader)
        }
    }

    fun refresh() {
        val id = pickupId ?: return
        val token = driverToken ?: return
        if (busy || refreshing || status in ended) return
        runRequest({ api.request("GET", "/$id", token) }, ::receiveSnapshot, readOnly = true)
    }

    val needsNewInvitation: Boolean
        get() = status == "INVITE_EXPIRED" || (status == "INVITING" && inviteToken == null)

    val canRenewInvitation: Boolean
        get() = !busy && online && needsNewInvitation && driverToken != null

    fun renewInvitation() {
        val id = pickupId ?: return
        val token = driverToken ?: return
        if (!canRenewInvitation) return
        runRequest(
            { api.request("POST", "/$id/invitations", token) },
            { result ->
                inviteToken = result.getString("inviteToken")
                status = "INVITING"
                participantId = null
                participantName = null
                readiness = ""
                message = "새 초대를 만들었어요."
                persistSession()
            },
        )
    }

    fun approve() {
        if (!canApprove) return
        val path = "/$pickupId/participants/$participantId/approval"
        val token = driverToken ?: return
        runRequest({ api.request("POST", path, token) }, ::receiveSnapshot)
    }

    /**
     * Disconnects the passenger who opened the invitation and shows a fresh invitation for the next
     * person.
     */
    fun disconnectParticipant() {
        if (!canDisconnect) return
        val path = "/$pickupId/participant"
        val token = driverToken ?: return
        runRequest(
            {
                try {
                    api.request("DELETE", path, token)
                } catch (rejected: ApiRejected) {
                    // The server owns this rule; remember it so the button stays hidden after a
                    // restart.
                    if (rejected.code == "RIDE_REQUEST_PICKUP")
                        JSONObject().put("requestedPickup", true)
                    else throw rejected
                }
            },
            { result ->
                if (result.optBoolean("requestedPickup")) {
                    requestedPickup = true
                    message = "마중 요청으로 시작한 픽업은 요청 목록에서 관리해주세요."
                    return@runRequest
                }
                inviteToken = result.getString("inviteToken")
                status = "INVITING"
                participantId = null
                participantName = null
                readiness = ""
                message = "탑승자를 끊었어요. 새 초대로 다시 연결할 수 있어요."
                persistSession()
            },
        )
    }

    fun cancel() {
        if (!canCancel) return
        val path = "/$pickupId/cancellation"
        val token = driverToken ?: return
        runRequest({ api.request("POST", path, token) }, ::receiveSnapshot)
    }

    fun arrive() {
        if (!canArrive) return
        val path = "/$pickupId/arrival"
        val token = driverToken ?: return
        val version = placeVersion
        runRequest(
            { api.request("POST", path, token, JSONObject().put("placeVersion", version)) },
            ::receiveSnapshot,
        )
    }

    private var sdkArrivalAttempt = 0
    private var sdkArrivalUntil = 0L
    private var sdkArrivalPickup: String? = null

    fun arriveFromSdk(confirmedPlace: PickupPlace) {
        if (
            needsSharingConfirmation ||
                status != "ACTIVE" ||
                place?.sameCoordinates(confirmedPlace) != true
        )
            return
        if (sdkArrivalPickup == pickupId && SystemClock.elapsedRealtime() < sdkArrivalUntil) return
        sdkArrivalPickup = pickupId
        sdkArrivalUntil = SystemClock.elapsedRealtime() + 30000
        sdkArrivalAttempt = 0
        sendSdkArrival()
    }

    private fun sendSdkArrival() {
        if (
            sdkArrivalPickup != pickupId ||
                status != "ACTIVE" ||
                needsSharingConfirmation ||
                SystemClock.elapsedRealtime() >= sdkArrivalUntil ||
                sdkArrivalAttempt >= 3
        )
            return
        if (busy) {
            handler.postDelayed(::sendSdkArrival, 1000)
            return
        }
        val token = driverToken ?: return
        val path = "/$pickupId/arrival"
        sdkArrivalAttempt++
        val version = placeVersion
        runRequest(
            { api.request("POST", path, token, JSONObject().put("placeVersion", version)) },
            ::receiveSnapshot,
        )
        // Arrival is idempotent on the server. Retry a lost acknowledgement at most twice;
        // never send an event to a subsequent pickup or after recovered sharing was stopped.
        handler.postDelayed(::sendSdkArrival, 6000)
    }

    fun complete() {
        if (!canComplete) return
        val path = "/$pickupId/completion"
        val token = driverToken ?: return
        runRequest({ api.request("POST", path, token) }, ::receiveSnapshot)
    }

    fun prepareNextPickup() {
        if (busy || status !in ended) return
        if (!clearSavedSession()) {
            notifyChanged()
            return
        }
        restoringSession = false
        controlGeneration++
        resetVehiclePublisher()
        pickupId = null
        driverToken = null
        inviteToken = null
        participantId = null
        participantName = null
        quickMessage = null
        lastQuickMessageAt = null
        place = null
        navigationReference = null
        readiness = ""
        status = ""
        message = ""
        online = false
        uncertainCreate = false
        notifyChanged()
    }

    fun invitationForHostBrowser(): String? {
        val id = pickupId ?: return null
        val token = inviteToken ?: return null
        val origin = BuildConfig.PICKUP_WEB_ORIGIN
        return if (origin.isNotBlank() && online && status == "INVITING") "$origin/#join/$id/$token"
        else null
    }

    fun invitationForSharingBrowser(): String? =
        invitationForHostBrowser()?.replace("/#join/", "/#share/")

    fun syncVehicle(reader: DestinationReader) = vehiclePublisher.sync(reader)

    private fun resetVehiclePublisher() {
        requestedPickup = false
        placeVersion = 1
        placeProposal = null
        proposalId = null
        vehiclePublisher.reset()
    }

    private fun receiveSnapshot(data: JSONObject) {
        val snapshot = PickupSnapshotResponse(data)
        val version = snapshot.placeVersion
        if (version != placeVersion) {
            vehiclePublisher.invalidatePlace()
            sdkArrivalPickup = null
        }
        placeVersion = version
        proposalId = snapshot.proposalId
        placeProposal = snapshot.proposedPlace
        val nextStatus = snapshot.status
        if (status != nextStatus) message = ""
        status = nextStatus
        if (restoringSession && status in setOf("ACTIVE", "ARRIVED")) {
            needsSharingConfirmation = true
            message = "이전 픽업을 찾았어요. 계속하기를 눌러 위치 공유를 다시 시작해주세요."
        }
        if (status in ended) needsSharingConfirmation = false
        restoringSession = false
        snapshot.place?.let { place = it }
        participantId = snapshot.participantId
        participantName = snapshot.participantName
        readiness = snapshot.readiness
        readinessSequence = snapshot.readinessSequence
        receivedReadinessSequence = snapshot.receivedReadinessSequence
        supportsReadinessReceipt = snapshot.supportsReadinessReceipt
        val quickMsgText = snapshot.quickMessageText
        val quickMsgSentAt = snapshot.quickMessageSentAt
        if (
            quickMsgText != null &&
                (quickMsgSentAt != lastQuickMessageAt || quickMsgText != quickMessage)
        ) {
            lastQuickMessageAt = quickMsgSentAt
            quickMessage = quickMsgText
            onQuickMessageReceived?.invoke(quickMsgText)
        }
        if (status != "INVITING") inviteToken = null
        if (status in ended) {
            clearSavedSession()
            resetVehiclePublisher()
            driverToken = null
            inviteToken = null
            participantId = null
            participantName = null
            quickMessage = null
            lastQuickMessageAt = null
            place = null
            navigationReference = null
            if (!storageCleanupFailed) message = "픽업 공유가 종료됐어요. 이 앱의 접근 정보도 지웠어요."
        } else persistSession()
    }

    // Confirms application receipt, including background sharing; this does not mean the driver
    // read it.
    private fun acknowledgeReadiness() {
        if (
            !online ||
                receiptInFlight ||
                !supportsReadinessReceipt ||
                status !in setOf("ACTIVE", "ARRIVED") ||
                readinessSequence <= receivedReadinessSequence
        )
            return
        val id = pickupId ?: return
        val passenger = participantId ?: return
        val token = driverToken ?: return
        val sequence = readinessSequence
        val generation = vehiclePublisher.vehicleGeneration
        receiptInFlight = true
        worker.execute {
            val result = runCatching {
                api.request(
                    "PUT",
                    "/$id/readiness-receipt",
                    token,
                    JSONObject().put("participantId", passenger).put("sequence", sequence),
                )
            }
            handler.post {
                receiptInFlight = false
                // Do not apply an earlier passenger/pickup's response to a new session.
                if (
                    generation == vehiclePublisher.vehicleGeneration &&
                        id == pickupId &&
                        passenger == participantId &&
                        result.isSuccess
                ) {
                    receivedReadinessSequence = maxOf(receivedReadinessSequence, sequence)
                }
                // A lost receipt is retried only after the next successful snapshot read.
            }
        }
    }

    private fun runRequest(
        call: () -> JSONObject,
        accept: (JSONObject) -> Unit,
        creating: Boolean = false,
        readOnly: Boolean = false,
    ) {
        if (busy || (readOnly && refreshing)) return
        val generation = if (readOnly) controlGeneration else ++controlGeneration
        if (readOnly) refreshing = true
        else {
            busy = true
            refreshError = false
            message = ""
            notifyChanged()
        }
        worker.execute {
            val result = runCatching(call)
            handler.post {
                if (readOnly) refreshing = false else busy = false
                if (readOnly && (generation != controlGeneration || busy)) return@post
                result.fold(
                    onSuccess = { data ->
                        try {
                            if (readOnly && refreshError) {
                                message = ""
                                refreshError = false
                            }
                            accept(data)
                            online = true
                        } catch (_: Exception) {
                            online = false
                            message = "서버 응답 형식을 확인하지 못했어요."
                            refreshError = readOnly
                        }
                    },
                    onFailure = { error ->
                        refreshError = readOnly
                        online = false
                        if (creating) registration?.rejectCreation(error)
                        if (creating && error is ApiRejected && error.status in 400..499)
                            uncertainCreate = false
                        message =
                            if (creating && uncertainCreate)
                                "생성 결과를 확인하지 못했어요. 중복 생성을 막기 위해 자동 재시도하지 않아요."
                            else
                                when ((error as? ApiRejected)?.code) {
                                    "PLACE_CHANGED",
                                    "PROPOSAL_CHANGED" -> "장소 제안이 바뀌었어요. 새로 확인한 뒤 경로를 다시 확인해주세요."
                                    "ROUTE_UNVERIFIED",
                                    "FUTURE_SOURCE_TIME" ->
                                        "최신 내비 경로와 시간을 확인하지 못했어요. 차량 시간을 확인하고 다시 눌러주세요."
                                    else -> "서버와 연결하지 못했어요. 다시 확인해주세요."
                                }
                        if (
                            !creating &&
                                error is ApiRejected &&
                                error.status in setOf(401, 403, 404, 410)
                        ) {
                            clearSavedSession()
                            restoringSession = false
                            resetVehiclePublisher()
                            driverToken = null
                            inviteToken = null
                            participantId = null
                            needsSharingConfirmation = false
                            status = "EXPIRED"
                            place = null
                            navigationReference = null
                            participantName = null
                            if (!storageCleanupFailed) message = "이 픽업에 접근할 수 없어요. 접근 정보를 지웠어요."
                        }
                    },
                )
                notifyChanged()
                if (readOnly) acknowledgeReadiness()
            }
        }
    }

    companion object {
        private val ended = setOf("COMPLETED", "CANCELLED", "EXPIRED")
    }
}
