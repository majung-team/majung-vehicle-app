package app.majung.vehicle.domain


import app.majung.vehicle.*
import app.majung.vehicle.ui.*
import app.majung.vehicle.ui.sections.*
import app.majung.vehicle.navi.*
import app.majung.vehicle.domain.*
import app.majung.vehicle.data.api.*
import app.majung.vehicle.data.storage.*
import app.majung.vehicle.util.*
import app.majung.vehicle.service.*

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.json.JSONObject
import java.util.concurrent.Executors

/** Only the driver's identity is linked to this device. Passengers submit through their own accounts. */
class VehicleAccountController(private val api: PickupApiClient, private val registration: VehicleRegistrationController) {
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var generation = 0
    private var qrUntil = 0L
    private var polling = false
    var changed: (() -> Unit)? = null
    var busy = false
        private set
    var refreshing = false
        private set
    var state = JSONObject()
        private set
    var requestsCheckedAt = 0L
        private set
    val requestsFresh: Boolean get() = requestsCheckedAt > 0 && SystemClock.elapsedRealtime() - requestsCheckedAt in 0..15000
    var requests = emptyList<JSONObject>()
        private set
    var message = "운전자 계정을 연결하면 마중 요청을 받을 수 있어요"
        private set
    var hasConnectedPeople: Boolean? = null
        private set
    var contacts = emptyList<JSONObject>()
        private set
    var contactsPage = 0
        private set
    var invitationMessage: String? = null
        private set
    private var pendingInvitation: JSONObject? = null
    private var qr: String? = null
    private var hasLoadedRequests = false
    internal var onNewPendingRide: ((RequestedRide) -> Unit)? = null
    val qrLink: String? get() = qr?.takeIf { SystemClock.elapsedRealtime() < qrUntil && state.optString("status") in setOf("AWAITING_ACCOUNT", "AWAITING_VEHICLE_CONFIRMATION") && registration.vehicleToken != null }
    fun registrationChanged() {
        generation++
        requestsCheckedAt = 0
        hasLoadedRequests = false
        hasConnectedPeople = null
        invitationMessage = null
        if (registration.vehicleToken == null) { qr=null; state=JSONObject(); requests=emptyList(); contacts=emptyList(); pendingInvitation=null }
        changed?.invoke()
    }
    fun refresh(showProgress: Boolean = false, background: Boolean = false, loaded: (() -> Unit)? = null) {
        execute(loaded = loaded, refreshingAccount = showProgress, background = background) {
            val account=api.requestApi("GET", "/vehicles/account-link", it)
            val list=runCatching {
                api.requestApi("GET", "/vehicles/ride-requests", it).getJSONArray("requests")
            }.getOrElse { error ->
                if (account.optString("status") != "LINKED" && error is ApiRejected && error.status in setOf(404, 409)) {
                    org.json.JSONArray()
                } else {
                    throw error
                }
            }
            JSONObject().put("account",account).put("requests",list).apply {
                if (!background && account.optString("status") == "LINKED") {
                    val hasPeople = runCatching {
                        api.requestApi("GET", "/vehicles/connections?page=0", it).getJSONArray("items").length() > 0
                    }.getOrNull()
                    if (hasPeople != null) put("hasConnectedPeople", hasPeople)
                }
            }
        }
    }
    fun loadContacts(page: Int = 0, failed: (() -> Unit)? = null, loaded: () -> Unit) {
        if (page !in 0..50) return
        execute(loaded = loaded, failed = failed, invitationFeedback = true) { token ->
            JSONObject().put("contacts", api.requestApi("GET", "/vehicles/connections?page=$page", token).getJSONArray("items")).put("contactsPage", page)
        }
    }
    fun invite(connectionId: String, place: PickupPlace) {
        if (busy || state.optString("status") != "LINKED") return
        val previous = pendingInvitation
        val same = previous?.optString("connectionId") == connectionId && previous.optJSONObject("place")?.let {
            it.optString("label") == place.label && it.optDouble("latitude") == place.latitude && it.optDouble("longitude") == place.longitude
        } == true
        val body = if (same) previous!! else JSONObject().put("connectionId", connectionId)
            .put("requestId", java.util.UUID.randomUUID().toString())
            .put("place", JSONObject().put("label", place.label).put("latitude", place.latitude).put("longitude", place.longitude))
        pendingInvitation = body
        execute(invitationFeedback = true) { token ->
            api.requestApi("POST", "/vehicles/invitations", token, body)
            JSONObject().put("invited", true).put("requests", api.requestApi("GET", "/vehicles/ride-requests", token).getJSONArray("requests"))
        }
    }
    fun cancelInvitation(id: String) {
        execute { token ->
            api.requestApi("POST", "/vehicles/ride-requests/$id/cancel-invitation", token)
            JSONObject().put("requests", api.requestApi("GET", "/vehicles/ride-requests", token).getJSONArray("requests"))
        }
    }
    fun issueQr() {
        execute { token ->
            val challenge=api.requestApi("POST","/vehicles/account-link/challenges",token)
            JSONObject().put("challenge",challenge).put("account",JSONObject().put("status","AWAITING_ACCOUNT").put("challengeId",challenge.getString("challengeId")))
        }
    }
    fun confirm(expectedChallengeId: String, expectedMajungId: String) {
        if (busy) return
        if (expectedChallengeId.isBlank() || expectedMajungId.isBlank() ||
            state.optString("status") != "AWAITING_VEHICLE_CONFIRMATION" ||
            state.optString("challengeId") != expectedChallengeId || state.optString("majungId") != expectedMajungId) {
            message="연결 요청이 바뀌었어요. 운전자 계정을 다시 확인해주세요"
            changed?.invoke()
            return
        }
        val id=expectedChallengeId
        execute { token -> JSONObject().put("account",api.requestApi("POST","/vehicles/account-link/confirm",token,JSONObject().put("challengeId",id))) }
    }
    fun changeRequest(id: String, action: String, block: Boolean = false, loaded: (() -> Unit)? = null) {
        if (action !in setOf("accept","reject")) return
        val body = if (action == "reject" && block) JSONObject().put("block", true) else null
        execute(loaded = loaded) { token ->
            api.requestApi("POST","/vehicles/ride-requests/$id/$action", token, body)
            JSONObject().put("requests",api.requestApi("GET","/vehicles/ride-requests",token).getJSONArray("requests"))
        }
    }
    private fun execute(loaded: (() -> Unit)? = null, failed: (() -> Unit)? = null, invitationFeedback: Boolean = false, refreshingAccount: Boolean = false, background: Boolean = false, call: (String) -> JSONObject) {
        val token=registration.vehicleToken ?: return
        if (busy || (background && polling)) return
        val epoch=generation
        if (invitationFeedback) invitationMessage = null
        if (background) {
            polling = true
        } else {
            refreshing = refreshingAccount
            busy=true; changed?.invoke()
        }
        worker.execute {
            val result=runCatching {
                call(token).also { data ->
                    data.optJSONObject("challenge")?.let { check(it.getString("challengeId").isNotBlank()); check(it.getString("code").matches(Regex("[A-Za-z0-9_-]{43}"))) }
                    data.optJSONArray("contacts")?.let { array ->
                        data.getInt("contactsPage")
                        (0 until array.length()).forEach { index -> array.getJSONObject(index).let { contact ->
                            check(contact.getString("connectionId").isNotBlank()); contact.getString("nickname"); contact.getString("majungId")
                        } }
                    }
                    data.optJSONArray("requests")?.let { array -> (0 until array.length()).forEach { array.getJSONObject(it) } }
                }
            }
            handler.post {
                if (background) polling = false else { busy=false; refreshing=false }
                if (epoch!=generation || token!=registration.vehicleToken) { failed?.invoke(); changed?.invoke(); return@post }
                result.fold(onSuccess={ data ->
                    data.optJSONObject("account")?.let {
                        if (it.optString("majungId") != state.optString("majungId") || it.optString("status") != "LINKED")
                            hasConnectedPeople = null
                        state = it
                        if (it.optString("status") != "LINKED") { contacts=emptyList(); pendingInvitation=null }
                    }
                    if (data.has("hasConnectedPeople")) hasConnectedPeople = data.getBoolean("hasConnectedPeople")
                    data.optJSONArray("requests")?.let { array ->
                        val newRequests = (0 until array.length()).map { array.getJSONObject(it) }
                        val previousPendingIds = requests.filter { it.optString("status") == "PENDING" }.map { it.optString("requestId") }.toSet()
                        requests = newRequests
                        requestsCheckedAt = SystemClock.elapsedRealtime()
                        if (hasLoadedRequests) {
                            for (item in newRequests) {
                                val id = item.optString("requestId")
                                val itemStatus = item.optString("status")
                                if (itemStatus == "PENDING" && id.isNotBlank() && id !in previousPendingIds) {
                                    item.optJSONObject("place")?.let { point ->
                                        val place = PickupPlace(point.optString("label"), point.optDouble("latitude"), point.optDouble("longitude"))
                                        if (NavigationStop(place, 0, NavigationStopKind.WAYPOINT).usable) {
                                            val ride = RequestedRide(id, item.optString("nickname"), place, itemStatus)
                                            onNewPendingRide?.invoke(ride)
                                        }
                                    }
                                }
                            }
                        }
                        hasLoadedRequests = true
                    }
                    data.optJSONArray("contacts")?.let { array ->
                        contacts = (0 until array.length()).map { array.getJSONObject(it) }
                        contactsPage = data.getInt("contactsPage")
                        if (contactsPage == 0) hasConnectedPeople = contacts.isNotEmpty()
                    }
                    data.optJSONObject("challenge")?.let {
                        qr="${BuildConfig.PICKUP_WEB_ORIGIN}/account#vehicle/${it.getString("challengeId")}/${it.getString("code")}"
                        qrUntil=SystemClock.elapsedRealtime()+300000
                    }
                    if (state.optString("status") !in setOf("AWAITING_ACCOUNT","AWAITING_VEHICLE_CONFIRMATION")) qr=null
                    message=when (state.optString("status")) {
                        "LINKED" -> "${state.optString("nickname")} · ${state.optString("majungId")} · 운전자 계정 연결됨"
                        "AWAITING_VEHICLE_CONFIRMATION" -> "휴대전화 연결 요청을 확인해주세요"
                        "AWAITING_ACCOUNT" -> "휴대전화로 계정 연결 QR을 스캔해주세요"
                        else -> if (registration.vehicleCode.isNotBlank() && registration.directRequestEnabled)
                            "휴대전화 미연결 · 차량 코드 ${registration.vehicleCode}로 직접 요청 수신 중"
                        else "차량 코드로 요청을 받거나 운전자 계정을 연결할 수 있어요"
                    }
                    if (data.optBoolean("invited")) { pendingInvitation=null; message="초대를 보냈어요. 탑승자가 수락하면 픽업 대기에 들어가요" }
                    if (data.optBoolean("invited")) invitationMessage = message
                    loaded?.invoke()
                },onFailure={ failure ->
                    requestsCheckedAt = 0
                    contacts=emptyList()
                    if (failure is ApiRejected && failure.status in 400..499) pendingInvitation=null
                    // A mutation may have committed. Polling only reads; it never repeats a mutation.
                    message=if (failure is ApiRejected && failure.status in setOf(401,403)) "차량 권한을 다시 확인해주세요" else if (failure is ApiRejected && failure.status < 500) when (failure.code) {
                        "CONNECTION_REQUIRED" -> "연결이 해제됐어요. 연결 목록을 다시 확인해주세요"
                        "RIDE_REQUEST_STATE" -> "이미 진행 중이거나 응답이 끝난 요청이에요. 목록을 다시 확인해주세요"
                        else -> "요청을 처리하지 못했어요. 목록을 다시 확인해주세요"
                    } else "처리 결과를 확인하지 못했어요. 상태를 다시 확인해주세요"
                    if (invitationFeedback) invitationMessage = message
                    if (failure is ApiRejected && failure.status in setOf(401,403,410)) { hasConnectedPeople=null; qr=null; requests=emptyList(); state=JSONObject() }
                    failed?.invoke()
                })
                changed?.invoke()
            }
        }
    }
}
