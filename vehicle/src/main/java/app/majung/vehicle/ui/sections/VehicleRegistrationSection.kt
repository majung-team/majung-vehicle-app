package app.majung.vehicle.ui.sections


import app.majung.vehicle.*
import app.majung.vehicle.ui.*
import app.majung.vehicle.ui.sections.*
import app.majung.vehicle.navi.*
import app.majung.vehicle.domain.*
import app.majung.vehicle.data.api.*
import app.majung.vehicle.data.storage.*
import app.majung.vehicle.util.*
import app.majung.vehicle.service.*

import android.app.AlertDialog
import android.text.InputFilter
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

internal class VehicleRegistrationSection(private val app: VehicleApplication, private val views: VehicleViews) {
    init { views.addView(views.label("차량 연결", 24f).apply { setTypeface(typeface, android.graphics.Typeface.BOLD) }) }
    private val text: TextView = views.label("", 16f).also(views::addView)
    private val editProfile = views.button("차종 · 차량번호 · 힌트 설정", compact = true) { openProfileDialog() }
    private val refresh = views.button("차량 연결 상태 다시 확인", compact = true) { app.registration.refresh() }

    private fun openProfileDialog() {
        val reg = app.registration
        val density = views.context.resources.displayMetrics.density
        val pad = (16 * density).toInt()
        val container = LinearLayout(views.context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, (8 * density).toInt(), pad, 0)
        }
        fun field(hintText: String, value: String, maxLen: Int) = EditText(views.context).apply {
            hint = hintText
            setText(value)
            isSingleLine = true
            filters = arrayOf(InputFilter.LengthFilter(maxLen))
        }.also { container.addView(it) }

        val modelInput = field("차종 (예: 아이오닉 5)", reg.vehicleModel, 32)
        val plateInput = field("차량 번호 (예: 12가 3456)", reg.licensePlate, 16)
        val hintInput = field("운전자 구별 힌트 (예: 송*준 아빠)", reg.ownerHint, 40)
        val directCheck = CheckBox(views.context).apply {
            text = "휴대전화 연결 없이도 차량 코드로 마중 요청 받기"
            isChecked = reg.directRequestEnabled
        }.also { container.addView(it) }

        AlertDialog.Builder(views.context)
            .setTitle("차량 정보 및 요청 수신 설정")
            .setMessage(if (reg.vehicleCode.isNotBlank()) "차량 코드 · ${reg.vehicleCode}\n탑승자가 이 코드를 입력하면 아래 차종·차량번호·힌트를 보고 확인한 뒤 요청을 보낼 수 있어요." else null)
            .setView(container)
            .setPositiveButton("저장") { _, _ ->
                if (views.canInteract) {
                    reg.updateProfile(
                        model = modelInput.text?.toString().orEmpty(),
                        plate = plateInput.text?.toString().orEmpty(),
                        hint = hintInput.text?.toString().orEmpty(),
                        directEnabled = directCheck.isChecked
                    )
                }
            }
            .setNegativeButton("취소", null)
            .show()
            .also(views::trackDialog)
    }

    fun render() {
        val reg = app.registration
        val codeLine = reg.vehicleCode.takeIf { it.isNotBlank() }?.let { "\n차량 호출 코드 · $it" }.orEmpty()
        val detailParts = listOfNotNull(
            reg.vehicleModel.takeIf { it.isNotBlank() },
            reg.licensePlate.takeIf { it.isNotBlank() },
            reg.ownerHint.takeIf { it.isNotBlank() }?.let { "힌트: $it" }
        )
        val detailLine = if (reg.vehicleToken != null) {
            val infoText = if (detailParts.isEmpty()) "차종 · 차량번호 미설정" else detailParts.joinToString(" · ")
            val directText = if (reg.directRequestEnabled) "상시 요청 수신 켜짐" else "직접 요청 수신 꺼짐"
            "\n$infoText ($directText)"
        } else ""
        text.updateText(reg.message + codeLine + detailLine)
        editProfile.visibility = if (reg.vehicleToken != null) View.VISIBLE else View.GONE
        editProfile.isEnabled = reg.vehicleToken != null && !reg.busy
        refresh.isEnabled = !reg.busy
    }
}
