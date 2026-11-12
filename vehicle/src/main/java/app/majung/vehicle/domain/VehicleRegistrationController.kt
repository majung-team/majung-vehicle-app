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
    var vehicleInfo = JSONObject()
        private set
    var changed: (() -> Unit)? = null
    val canCreate: Boolean get() = !busy && !storageBlocked && (development || verified && credential != null)
    val vehicleToken: String? get() = if (verified) credential?.token else null
    val needsRegistration: Boolean get() = !busy && registrationChecked && !storageBlocked && !development && credential == null
    val vehicleCode: String get() = vehicleInfo.optString("vehicleCode")
    val vehicleModel: String get() = vehicleInfo.optString("vehicleModel")
    val licensePlate: String get() = vehicleInfo.optString("licensePlate")
    val ownerHint: String get() = vehicleInfo.optString("ownerHint")
    val directRequestEnabled: Boolean get() = vehicleInfo.optBoolean("directRequestEnabled", true)
    val vehicleDisplayName: String get() = vehicleInfo.optString("displayName")

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
                val me = if (saved != null) {
                    runCatching {
                        api.requestApi("GET", "/vehicles/me", saved.token).also {
                            check(it.getString("vehicleId") == saved.vehicleId)
                        }
                    }.getOrElse { error ->
                        if (error is ApiRejected && error.status == 404) {
                            api.requestApi("GET", "/vehicles/account-link", saved.token)
                            JSONObject()
                        } else {
                            throw error
                        }
                    }
                } else JSONObject()
                Triple(saved, BuildConfig.DEBUG && configuration.optBoolean("developmentToolsEnabled", false), me)
            }
            handler.post {
                busy = false
                result.fold(onSuccess = { (saved, local, me) ->
                    storageBlocked = false
                    registrationChecked = true
                    credential = saved
                    verified = saved != null
                    development = local
                    vehicleInfo = me
                    message = when { verified -> "등록된 차량이에요"; local -> "로컬 개발 모드 · 등록 없이 시험 가능"; else -> "휴대전화로 QR을 열어 차량을 연결해주세요" }
                }, onFailure = { error ->
                    message = "차량 권한을 확인하지 못했어요. 연결을 확인한 뒤 다시 확인해주세요"
                    if (error is CredentialStorageFailure) {
                        clearCredential("저장된 차량 권한을 읽을 수 없어 지웠어요. 휴대전화로 다시 연결해주세요")
                    }
                    if (error is ApiRejected && error.code in setOf("VEHICLE_REVOKED", "VEHICLE_CREDENTIAL_INVALID", "UNAUTHORIZED")) {
                        clearCredential("차량 권한이 철회됐거나 유효하지 않아요. 휴대전화로 다시 연결해주세요")
                    }
                })
                changed?.invoke()
            }
        }
    }

    fun updateProfile(model: String, plate: String, hint: String, directEnabled: Boolean) {
        val token = vehicleToken ?: return
        if (busy) return
        busy = true
        message = "차량 정보를 저장하고 있어요"
        changed?.invoke()
        worker.execute {
            val result = runCatching {
                val body = JSONObject()
                    .put("vehicleModel", model.trim())
                    .put("licensePlate", plate.trim())
                    .put("ownerHint", hint.trim())
                    .put("directRequestEnabled", directEnabled)
                api.requestApi("PUT", "/vehicles/me", token, body)
            }
            handler.post {
                busy = false
                result.fold(onSuccess = { updated ->
                    vehicleInfo = updated
                    message = "차량 정보를 저장했어요"
                }, onFailure = { error ->
                    rejectCreation(error)
                    if (verified) message = "차량 정보를 저장하지 못했어요. 입력 길이나 연결 상태를 확인해주세요"
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
                val me = runCatching { api.requestApi("GET", "/vehicles/me", saved.token) }.getOrDefault(JSONObject())
                saved to me
            }
            handler.post {
                busy = false
                result.fold(onSuccess = { (saved, me) ->
                    credential = saved
                    verified = true
                    vehicleInfo = me
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
            clearCredential("차량 권한을 다시 등록해야 해요. 연결 상태를 확인해주세요")
            changed?.invoke()
        }
    }

    private fun clearCredential(reason: String) {
        verified = false
        development = false
        credential = null
        vehicleInfo = JSONObject()
        registrationChecked = true
        message = try { store.clear(); storageBlocked = false; reason }
        catch (_: Exception) { storageBlocked = true; "기존 차량 권한을 지우지 못했어요. 재등록하지 말고 연결 상태를 확인해주세요" }
    }
}
