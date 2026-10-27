package app.majung.vehicle

import android.app.AlertDialog
import android.view.View
import android.widget.ImageView

internal class VehicleEnrollmentSection(private val app: VehicleApplication, private val views: VehicleViews) {
    init {
        views.addView(views.label("휴대전화로 한 번만 연결하면 돼요", 26f).apply { setTypeface(typeface, android.graphics.Typeface.BOLD) })
        views.addView(views.label("QR 스캔  →  카카오 로그인  →  이 차량에서 본인 확인", 19f))
    }
    private val message = views.label("", 18f).also(views::addView)
    private val start = views.button("휴대전화로 시작하기 · QR 만들기", primary = true) { app.enrollment.start() }
    private var dialog: AlertDialog? = null
    private var shownQr: String? = null
    private val showQr = views.button("연결 QR 보기") {
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
    private val confirm = views.button("요청한 계정 확인하고 연결") {
        val id = app.enrollment.state.optString("enrollmentId")
        val majungId = app.enrollment.state.optString("majungId")
        AlertDialog.Builder(views.context).setTitle("이 계정으로 마중을 시작할까요?")
            .setMessage("${app.enrollment.state.optString("nickname")} · $majungId\n운전자 본인의 계정인지 확인해주세요. 차량 소유권 인증은 아니에요.")
            .setPositiveButton("이 계정으로 연결") { _, _ -> if (views.canInteract) app.enrollment.confirm(id, majungId) }
            .setNegativeButton("돌아가기", null).show().also(views::trackDialog)
    }
    private val refresh = views.button("연결 상태 다시 확인") { app.enrollment.refresh() }
    fun render() {
        message.updateText(app.enrollment.message)
        start.isEnabled = app.registration.needsRegistration && !app.enrollment.busy
        start.text = if (app.enrollment.hasPending) "이전 요청 취소하고 새 QR 만들기" else "휴대전화로 시작하기 · QR 만들기"
        showQr.visibility = if (app.enrollment.qrLink != null) View.VISIBLE else View.GONE
        confirm.visibility = if (app.enrollment.state.optString("status") == "AWAITING_VEHICLE_CONFIRMATION") View.VISIBLE else View.GONE
        confirm.isEnabled = !app.enrollment.busy
        refresh.isEnabled = app.enrollment.hasPending && !app.enrollment.busy
        if (views.canInteract && app.enrollment.qrLink != null && shownQr != app.enrollment.qrLink) showQr.performClick()
        if (shownQr != null && shownQr != app.enrollment.qrLink) { dialog?.dismiss(); dialog = null; shownQr = null }
    }
}
