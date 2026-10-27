package app.majung.vehicle

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.Executors
import org.json.JSONObject

/** A pending device secret is encrypted separately and is never included in the QR. */
class VehicleEnrollmentController(
    private val api: PickupApiClient,
    private val pendingStore: VehicleCredentialStore,
    private val vehicleStore: VehicleCredentialStore,
    private val registration: VehicleRegistrationController
) {
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var pending: VehicleCredential? = null
    private var qr: String? = null
    private var qrUntil = 0L
    var changed: (() -> Unit)? = null
    var busy = false
        private set
    var state = JSONObject()
        private set
    var message = "운전자 휴대전화로 QR을 열어 처음 한 번 연결해주세요. 관리자 코드는 필요 없어요."
        private set
    val qrLink: String? get() = qr?.takeIf { SystemClock.elapsedRealtime() < qrUntil && state.optString("status") == "AWAITING_ACCOUNT" }
    val hasPending: Boolean get() = pending != null

    fun restore() {
        if (busy) return
        busy = true
        worker.execute {
            val saved = runCatching { pendingStore.load() }
            handler.post {
                busy = false
                saved.fold(onSuccess = { pending = it; if (it != null) refresh() }, onFailure = {
                    message = "이전 연결 요청을 읽지 못했어요. 저장 상태를 다시 확인해주세요."
                })
                changed?.invoke()
            }
        }
    }
    fun start() {
        if (busy || !registration.needsRegistration) return
        execute {
            // Never orphan an earlier live QR when the user asks for a replacement.
            val saved = pendingStore.load()
            if (saved != null) {
                try { api.requestApi("POST", "/vehicles/enrollments/${saved.vehicleId}/cancel", saved.token) }
                catch (failure: ApiRejected) { if (failure.status != 410) throw failure }
                pendingStore.clear()
            }
            val issued = api.requestApi("POST", "/vehicles/enrollments", body = JSONObject())
            val credential = VehicleCredential(issued.getString("enrollmentId"), issued.getString("deviceToken"))
            pendingStore.save(credential)
            pending = credential
            JSONObject().put("enrollmentId", credential.vehicleId).put("status", "AWAITING_ACCOUNT").put("code", issued.getString("code"))
        }
    }
    fun refresh() {
        if (pending == null || busy) return
        execute { val saved = pending ?: error("No pending enrollment")
            api.requestApi("GET", "/vehicles/enrollments/${saved.vehicleId}", saved.token)
        }
    }
    fun confirm(expectedId: String, expectedMajungId: String) {
        if (busy || pending?.vehicleId != expectedId || state.optString("status") != "AWAITING_VEHICLE_CONFIRMATION" ||
            state.optString("majungId") != expectedMajungId || expectedMajungId.isBlank()) return
        execute { val saved = pending ?: error("No pending enrollment")
            api.requestApi("POST", "/vehicles/enrollments/${saved.vehicleId}/confirm", saved.token,
                JSONObject().put("majungId", expectedMajungId))
        }
    }
    private fun execute(call: () -> JSONObject) {
        if (busy) return
        busy = true; changed?.invoke()
        worker.execute {
            val result = runCatching {
                call().also { data ->
                    check(data.getString("enrollmentId") == pending?.vehicleId)
                    check(data.getString("status") in setOf("AWAITING_ACCOUNT", "AWAITING_VEHICLE_CONFIRMATION", "LINKED"))
                    if (data.has("code")) check(data.getString("code").matches(Regex("[A-Za-z0-9_-]{43}")))
                    if (data.getString("status") != "AWAITING_ACCOUNT") {
                        check(data.getString("nickname").isNotBlank())
                        check(data.getString("majungId").isNotBlank())
                    }
                    if (data.getString("status") == "LINKED") {
                        vehicleStore.save(requireNotNull(pending))
                        pendingStore.clear()
                    }
                }
            }
            handler.post {
                busy = false
                result.fold(onSuccess = { data ->
                    state = data
                    if (data.has("code")) {
                        val code = data.getString("code")
                        qr = "${BuildConfig.PICKUP_WEB_ORIGIN}/account#enroll/${data.getString("enrollmentId")}/$code"
                        qrUntil = SystemClock.elapsedRealtime() + 300000
                        data.remove("code")
                    }
                    message = when (data.optString("status")) {
                        "LINKED" -> "차량과 운전자 계정을 연결했어요."
                        "AWAITING_VEHICLE_CONFIRMATION" -> "${data.optString("nickname")} · ${data.optString("majungId")} · 본인 계정인지 확인해주세요"
                        else -> "운전자 휴대전화로 QR을 스캔하고 카카오 로그인 후 연결을 요청해주세요."
                    }
                    if (data.optString("status") != "AWAITING_ACCOUNT") qr = null
                    if (data.optString("status") == "LINKED") { pending = null; registration.refresh() }
                }, onFailure = { failure ->
                    qr = null
                    message = "결과를 확인하지 못했어요. ‘연결 상태 다시 확인’을 눌러주세요. 연결 요청을 자동으로 반복하지 않아요."
                    if (failure is ApiRejected && failure.status in setOf(403, 410)) {
                        state = JSONObject()
                        message = try {
                            pendingStore.clear(); pending = null
                            "연결 QR이 만료됐거나 취소됐어요. 새 QR로 다시 연결해주세요."
                        } catch (_: Exception) { "연결 정보를 지우지 못했어요. 새 요청을 만들지 말고 저장 상태를 확인해주세요." }
                    }
                })
                changed?.invoke()
            }
        }
    }
}
