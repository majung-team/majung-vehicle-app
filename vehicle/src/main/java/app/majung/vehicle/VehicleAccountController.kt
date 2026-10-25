package app.majung.vehicle

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
    var changed: (() -> Unit)? = null
    var busy = false
        private set
    var state = JSONObject()
        private set
    var requests = emptyList<JSONObject>()
        private set
    var message = "운전자 계정을 연결하면 마중 요청을 받을 수 있어요"
        private set
    private var qr: String? = null
    val qrLink: String? get() = qr?.takeIf { SystemClock.elapsedRealtime() < qrUntil && state.optString("status") in setOf("AWAITING_ACCOUNT", "AWAITING_VEHICLE_CONFIRMATION") && registration.vehicleToken != null }
    fun registrationChanged() {
        generation++
        if (registration.vehicleToken == null) { qr=null; state=JSONObject(); requests=emptyList() }
        changed?.invoke()
    }
    fun refresh() {
        execute {
            val account=api.requestApi("GET", "/vehicles/account-link", it)
            val list=if (account.optString("status")=="LINKED") api.requestApi("GET", "/vehicles/ride-requests", it).getJSONArray("requests") else org.json.JSONArray()
            JSONObject().put("account",account).put("requests",list)
        }
    }
    fun issueQr() {
        execute { token ->
            val challenge=api.requestApi("POST","/vehicles/account-link/challenges",token)
            JSONObject().put("challenge",challenge).put("account",JSONObject().put("status","AWAITING_ACCOUNT").put("challengeId",challenge.getString("challengeId")))
        }
    }
    fun confirm() {
        val id=state.optString("challengeId").takeIf { it.isNotBlank() } ?: return
        execute { token -> JSONObject().put("account",api.requestApi("POST","/vehicles/account-link/confirm",token,JSONObject().put("challengeId",id))) }
    }
    fun changeRequest(id: String, action: String) {
        if (action !in setOf("accept","reject")) return
        execute { token ->
            api.requestApi("POST","/vehicles/ride-requests/$id/$action",token)
            JSONObject().put("requests",api.requestApi("GET","/vehicles/ride-requests",token).getJSONArray("requests"))
        }
    }
    private fun execute(call: (String) -> JSONObject) {
        val token=registration.vehicleToken ?: return
        if (busy) return
        val epoch=generation
        busy=true; changed?.invoke()
        worker.execute {
            val result=runCatching {
                call(token).also { data ->
                    data.optJSONObject("challenge")?.let { check(it.getString("challengeId").isNotBlank()); check(it.getString("code").matches(Regex("[A-Za-z0-9_-]{43}"))) }
                    data.optJSONArray("requests")?.let { array -> (0 until array.length()).forEach { array.getJSONObject(it) } }
                }
            }
            handler.post {
                busy=false
                if (epoch!=generation || token!=registration.vehicleToken) { changed?.invoke(); return@post }
                result.fold(onSuccess={ data ->
                    data.optJSONObject("account")?.let { state=it }
                    data.optJSONArray("requests")?.let { array -> requests=(0 until array.length()).map { array.getJSONObject(it) } }
                    data.optJSONObject("challenge")?.let {
                        qr="${BuildConfig.PICKUP_WEB_ORIGIN}/account#vehicle/${it.getString("challengeId")}/${it.getString("code")}"
                        qrUntil=SystemClock.elapsedRealtime()+300000
                    }
                    if (state.optString("status") !in setOf("AWAITING_ACCOUNT","AWAITING_VEHICLE_CONFIRMATION")) qr=null
                    message=when (state.optString("status")) {
                        "LINKED" -> "${state.optString("nickname")} · ${state.optString("majungId")} · 운전자 계정 연결됨"
                        "AWAITING_VEHICLE_CONFIRMATION" -> "휴대전화 연결 요청을 확인해주세요"
                        "AWAITING_ACCOUNT" -> "휴대전화로 계정 연결 QR을 스캔해주세요"
                        else -> "운전자 본인 계정을 처음 한 번 연결해주세요"
                    }
                },onFailure={ failure ->
                    // A mutation may have committed. Polling only reads; it never repeats a mutation.
                    message=if (failure is ApiRejected && failure.status in setOf(401,403)) "차량 권한을 다시 확인해주세요" else "처리 결과를 확인하지 못했어요. 상태를 다시 확인해주세요"
                    if (failure is ApiRejected && failure.status in setOf(401,403,410)) { qr=null; requests=emptyList(); state=JSONObject() }
                })
                changed?.invoke()
            }
        }
    }
}
