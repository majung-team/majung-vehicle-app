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
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.ImageView

internal class VehicleEnrollmentSection(private val app: VehicleApplication, private val views: VehicleViews) {
    init {
        views.addView(views.label("휴대전화로 한 번만 연결하면 돼요", 26f).apply { setTypeface(typeface, android.graphics.Typeface.BOLD) })
        views.addView(views.label("QR 스캔  →  카카오 로그인  →  이 차량에서 본인 확인", 19f))
    }
    private val message = views.label("", 18f).also(views::addView)
    private var dialog: AlertDialog? = null
    private var shownQr: String? = null
    private val confirm = views.button("요청한 계정 확인하고 연결", primary = true) {
        val id = app.enrollment.state.optString("enrollmentId")
        val majungId = app.enrollment.state.optString("majungId")
        AlertDialog.Builder(views.context).setTitle("이 계정으로 마중을 시작할까요?")
            .setMessage("${app.enrollment.state.optString("nickname")} · $majungId\n운전자 본인의 계정인지 확인해주세요. 차량 소유권 인증은 아니에요.")
            .setPositiveButton("이 계정으로 연결") { _, _ -> if (views.canInteract) app.enrollment.confirm(id, majungId) }
            .setNegativeButton("돌아가기", null).show().also(views::trackDialog)
    }
    private val showQr = views.button("연결 QR 보기", primary = true) {
        val link = app.enrollment.qrLink ?: return@button
        shownQr = link
        val image = ImageView(views.context).apply {
            setImageBitmap(InvitationQr.create(link)); adjustViewBounds = true; minimumHeight = 320
            contentDescription = "차량 첫 연결 QR"
        }
        dialog = AlertDialog.Builder(views.context).setTitle("운전자 본인 휴대전화로 스캔해주세요")
            .setMessage("카카오 로그인 후 연결을 요청하면 차량에서 본인 계정을 확인해요. 이 QR은 5분 동안만 사용할 수 있어요.")
            .setView(image).setPositiveButton("닫기", null).show().also(views::trackDialog)
    }
    private val refresh = views.button("연결 상태 다시 확인", refreshAfterAction = false) {
        if (!app.enrollment.busy) app.enrollment.refresh(showProgress = true)
    }
    private val start = views.button("휴대전화로 시작하기 · QR 만들기", primary = true) { app.enrollment.start() }
    fun render() {
        val status = app.enrollment.state.optString("status")
        val awaitingConfirm = status == "AWAITING_VEHICLE_CONFIRMATION"
        message.updateText(app.enrollment.message)
        if (awaitingConfirm) {
            val dp = { value: Int -> (value * views.context.resources.displayMetrics.density).toInt() }
            message.setTextColor(Color.rgb(30, 64, 175))
            message.setPadding(dp(16), dp(12), dp(16), dp(12))
            message.background = GradientDrawable().apply {
                setColor(Color.rgb(238, 244, 255))
                cornerRadius = dp(12).toFloat()
                setStroke(dp(1), Color.rgb(184, 209, 255))
            }
        } else {
            val dp = { value: Int -> (value * views.context.resources.displayMetrics.density).toInt() }
            message.setTextColor(Color.rgb(32, 40, 46))
            message.setPadding(0, dp(8), 0, dp(8))
            message.background = null
        }
        confirm.visibility = if (awaitingConfirm) View.VISIBLE else View.GONE
        confirm.isEnabled = !app.enrollment.busy || app.enrollment.refreshing
        views.styleButton(confirm, primary = true)
        showQr.visibility = if (app.enrollment.qrLink != null) View.VISIBLE else View.GONE
        views.styleButton(showQr, primary = !awaitingConfirm)
        refresh.visibility = if (app.enrollment.hasPending) View.VISIBLE else View.GONE
        refresh.updateText(if (app.enrollment.refreshing) "연결 상태 확인 중…" else "연결 상태 다시 확인")
        // Keep the pressed surface and layout stable; the controller rejects duplicate requests.
        refresh.isEnabled = app.enrollment.hasPending
        start.isEnabled = app.registration.needsRegistration && (!app.enrollment.busy || app.enrollment.refreshing)
        start.text = if (app.enrollment.hasPending) "이전 요청 취소하고 새 QR 만들기" else "휴대전화로 시작하기 · QR 만들기"
        views.styleButton(start, primary = !app.enrollment.hasPending)
        if (views.canInteract && app.enrollment.qrLink != null && shownQr != app.enrollment.qrLink) showQr.performClick()
        if (shownQr != null && shownQr != app.enrollment.qrLink) { dialog?.dismiss(); dialog = null; shownQr = null }
    }
}
