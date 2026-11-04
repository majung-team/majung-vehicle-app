package app.majung.vehicle.util


import app.majung.vehicle.*
import app.majung.vehicle.ui.*
import app.majung.vehicle.ui.sections.*
import app.majung.vehicle.navi.*
import app.majung.vehicle.domain.*
import app.majung.vehicle.data.api.*
import app.majung.vehicle.data.storage.*
import app.majung.vehicle.util.*
import app.majung.vehicle.service.*

import android.content.Context
import android.content.SharedPreferences

internal class PickupVoiceGuide(context: Context, private val pickup: PickupController,
    private val preferences: SharedPreferences = context.getSharedPreferences("pickup-voice", Context.MODE_PRIVATE),
    private val createOutput: (() -> Unit) -> PickupVoiceOutput = { PleosPickupVoiceOutput(context, it) }) {
    private val policy = PickupVoicePolicy()
    private var output: PickupVoiceOutput? = null
    var enabled = preferences.getBoolean("enabled", true)
        private set
    var changed: (() -> Unit)? = null
    private var saveFailed = false
    val message: String get() = if (saveFailed) "음성 설정을 저장하지 못했어요. 다시 켜주세요"
        else if (enabled) output?.message ?: "음성 안내 준비 중" else "음성 안내 꺼짐"
    private val observer: () -> Unit = { observe() }

    init {
        pickup.addObserver(observer)
        if (enabled) output = createOutput { changed?.invoke() }
        observe()
    }

    fun setEnabled(value: Boolean) {
        if (enabled == value) return
        // The preference contains no credentials; a failed write must not report that the choice was saved.
        saveFailed = !preferences.edit().putBoolean("enabled", value).commit()
        enabled = value && !saveFailed
        output?.close()
        output = if (enabled) createOutput { changed?.invoke() } else null
        observe()
        changed?.invoke()
    }

    fun speak(text: String) {
        if (enabled && text.isNotBlank() && output?.ready == true) output?.speak(text)
    }

    private fun observe() {
        val eligible = enabled && pickup.online && !pickup.needsSharingConfirmation &&
            pickup.status in setOf("ACTIVE", "ARRIVED")
        val notice = policy.observe(pickup.pickupId, pickup.status, pickup.readiness, eligible, pickup.proposalId)
        if (!eligible) output?.stop()
        if (notice != null && output?.ready == true) output?.speak(notice.text)
    }

    fun close() {
        pickup.removeObserver(observer)
        output?.close()
        output = null
    }
}
