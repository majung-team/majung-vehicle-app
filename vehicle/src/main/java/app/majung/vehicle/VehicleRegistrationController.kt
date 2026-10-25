package app.majung.vehicle

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.util.concurrent.Executors

class VehicleRegistrationController(private val api: PickupApiClient, private val store: VehicleCredentialStore) {
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var credential: VehicleCredential? = null
    private var development = false
    private var verified = false
    private var registrationChecked = false
    private var storageBlocked = false
    private class CredentialStorageFailure : Exception()
    var busy = false
        private set
    var message = "차량 등록 상태 확인 중"
        private set
    var changed: (() -> Unit)? = null
    val canCreate: Boolean get() = !busy && !storageBlocked && (development || verified && credential != null)
    val vehicleToken: String? get() = if (verified) credential?.token else null
    val needsRegistration: Boolean get() = !busy && registrationChecked && !storageBlocked && !development && credential == null

    fun refresh() {
        if (busy) return
        busy = true
        verified = false
        registrationChecked = false
        development = false
        changed?.invoke()
        worker.execute {
            val result = runCatching {
                val saved = try { store.load() } catch (_: Exception) { throw CredentialStorageFailure() }
                val configuration = api.requestApi("GET", "/config")
                if (saved != null) {
                    val me = api.requestApi("GET", "/vehicles/me", saved.token)
                    check(me.getString("vehicleId") == saved.vehicleId)
                }
                saved to (BuildConfig.DEBUG && configuration.optBoolean("developmentToolsEnabled", false))
            }
            handler.post {
                busy = false
                result.fold(onSuccess = { (saved, local) ->
                    storageBlocked = false
                    registrationChecked = true
                    credential = saved
                    verified = saved != null
                    development = local
                    message = when { verified -> "등록된 차량이에요"; local -> "로컬 개발 모드 · 등록 없이 시험 가능"; else -> "관리자에게 최초 등록 코드를 받아주세요" }
                }, onFailure = { error ->
                    message = "차량 권한을 확인하지 못했어요. 연결을 확인한 뒤 다시 확인해주세요"
                    if (error is CredentialStorageFailure) {
                        clearCredential("저장된 차량 권한을 읽을 수 없어 지웠어요. 새 등록 코드를 요청해주세요")
                    }
                    if (error is ApiRejected && error.code in setOf("VEHICLE_REVOKED", "VEHICLE_CREDENTIAL_INVALID", "UNAUTHORIZED")) {
                        clearCredential("차량 권한이 철회됐거나 유효하지 않아요. 새 등록 코드를 요청해주세요")
                    }
                })
                changed?.invoke()
            }
        }
    }

    fun register(code: String) {
        if (!needsRegistration || !code.matches(Regex("[A-Za-z0-9_-]{43}"))) return
        busy = true
        message = "차량 등록 중 · 응답을 기다려주세요"
        changed?.invoke()
        worker.execute {
            val result = runCatching {
                val response = api.requestApi("POST", "/vehicles/registrations", body = JSONObject()
                    .put("code", code).put("displayName", "마중 차량"))
                val saved = VehicleCredential(response.getString("vehicleId"), response.getString("vehicleToken"))
                store.save(saved)
                saved
            }
            handler.post {
                busy = false
                result.fold(onSuccess = { saved ->
                    credential = saved
                    verified = true
                    message = "차량 등록을 마쳤어요. 다음부터는 코드 없이 사용할 수 있어요"
                }, onFailure = { error ->
                    verified = false
                    message = if (error is ApiRejected && error.code == "REGISTRATION_CODE_UNAVAILABLE")
                        "등록 코드가 만료됐거나 이미 사용됐어요. 새 코드를 요청해주세요"
                    else "등록 결과 또는 안전한 저장을 확인하지 못했어요. 자동 재시도하지 않아요. 관리자에게 코드 사용 여부를 확인해주세요"
                })
                changed?.invoke()
            }
        }
    }

    fun rejectCreation(error: Throwable) {
        if (error is ApiRejected && error.code in setOf("VEHICLE_REVOKED", "VEHICLE_CREDENTIAL_INVALID", "VEHICLE_REGISTRATION_REQUIRED", "UNAUTHORIZED")) {
            clearCredential("차량 권한을 다시 등록해야 해요. 관리자에게 확인해주세요")
            changed?.invoke()
        }
    }

    private fun clearCredential(reason: String) {
        verified = false
        development = false
        credential = null
        registrationChecked = true
        message = try { store.clear(); storageBlocked = false; reason }
        catch (_: Exception) { storageBlocked = true; "기존 차량 권한을 지우지 못했어요. 재등록하지 말고 관리자에게 확인해주세요" }
    }
}
