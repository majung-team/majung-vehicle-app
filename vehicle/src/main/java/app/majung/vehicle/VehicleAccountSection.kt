package app.majung.vehicle

import android.app.AlertDialog
import android.view.View
import android.widget.ImageView
import org.json.JSONObject

internal class VehicleAccountSection(private val app: VehicleApplication,private val views: VehicleViews) {
    private val status=views.label("",16f).also(views::addView)
    private var qrDialog: AlertDialog?=null
    private var shownQr: String?=null
    private val issue=views.button("운전자 계정 연결 QR 만들기") { app.account.issueQr() }
    private val qr=views.button("운전자 휴대전화로 QR 스캔") {
        val link=app.account.qrLink ?: return@button
        val image=ImageView(views.context).apply { setImageBitmap(InvitationQr.create(link)); adjustViewBounds=true; minimumHeight=320; contentDescription="운전자 계정 연결 QR" }
        shownQr=link
        qrDialog=AlertDialog.Builder(views.context).setTitle("운전자 본인 휴대전화로 스캔해주세요")
            .setMessage("로그인 후 연결 요청을 보내고, 이 차량에서 계정을 확인해주세요. 탑승자는 이 QR을 스캔할 필요가 없어요.")
            .setView(image).setPositiveButton("닫기",null).show().also(views::trackDialog)
    }
    private val confirm=views.button("요청한 운전자 계정 확인") {
        val state=app.account.state
        AlertDialog.Builder(views.context).setTitle("이 계정을 차량에 연결할까요?")
            .setMessage("${state.optString("nickname")} · ${state.optString("majungId")}\n운전자 본인의 계정인지 확인해주세요.")
            .setPositiveButton("연결 승인") { _,_ -> if (views.canInteract) app.account.confirm() }.setNegativeButton("돌아가기",null).show().also(views::trackDialog)
    }
    private val inbox=views.button("받은 마중 요청과 픽업 대기 목록") {
        val items=app.account.requests
        if (items.isEmpty()) {
            AlertDialog.Builder(views.context).setMessage("받은 요청이나 대기 중인 픽업이 없어요. 새로 확인해주세요.").setPositiveButton("확인",null).show().also(views::trackDialog)
        } else AlertDialog.Builder(views.context).setTitle("마중 요청 · 수락 순서대로 진행")
            .setItems(items.map { "${it.optString("nickname")} · ${it.getJSONObject("place").optString("label")} · ${stateLabel(it.optString("status"))}" }.toTypedArray()) { _,index -> if (views.canInteract) details(items[index]) }
            .setNegativeButton("닫기",null).show().also(views::trackDialog)
    }
    private val refresh=views.button("계정·마중 요청 새로 확인") { app.account.refresh() }
    private fun details(item: JSONObject) {
        val place=item.getJSONObject("place").let { PickupPlace(it.getString("label"),it.getDouble("latitude"),it.getDouble("longitude")) }
        val id=item.getString("requestId")
        val dialog=AlertDialog.Builder(views.context).setTitle("${item.optString("nickname")} · ${stateLabel(item.optString("status"))}")
            .setMessage("${item.optString("majungId")}\n요청 장소 · ${place.label}\n수락해도 현재 픽업을 끝내기 전에는 다음 픽업을 시작하지 않아요.")
            .setNegativeButton("돌아가기",null)
        when (item.optString("status")) {
            "PENDING" -> dialog.setPositiveButton("수락하고 대기에 추가") { _,_ -> if (views.canInteract) app.account.changeRequest(id,"accept") }
                .setNeutralButton("거절") { _,_ -> if (views.canInteract) app.account.changeRequest(id,"reject") }
            "QUEUED" -> {
                dialog.setNeutralButton("내비 경유지에 추가") { _,_ -> if (views.canInteract && app.pickup.canCreate) app.destination.addPickupWaypoint(place) }
                dialog.setPositiveButton("이 픽업 시작") { _,_ ->
                    if (views.canInteract && app.pickup.canStartRequested(id)) {
                        val selected=app.destination.selection.current(android.os.SystemClock.elapsedRealtime())
                        if (selected?.sameCoordinates(place)==true) app.pickup.startRequested(id,place,app.destination.referenceFor(place))
                        else AlertDialog.Builder(views.context).setMessage("요청 장소를 내비 경유지로 추가한 뒤, 경로 확인이 끝나면 시작해주세요. 현재 픽업이 끝났다면 ‘다음 픽업 준비’를 먼저 눌러주세요.").setPositiveButton("확인",null).show().also(views::trackDialog)
                    }
                }
            }
            "ACTIVE" -> dialog.setPositiveButton("진행 중인 픽업 복구") { _,_ -> if (views.canInteract && app.pickup.canStartRequested(id)) app.pickup.startRequested(id,place,app.destination.referenceFor(place)) }
        }
        dialog.show().also { shown ->
            if (item.optString("status")=="QUEUED") {
                shown.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled=app.pickup.canStartRequested(id)
                shown.getButton(AlertDialog.BUTTON_NEUTRAL).isEnabled=app.pickup.canCreate
            }
            if (item.optString("status")=="ACTIVE") shown.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled=app.pickup.canStartRequested(id)
            views.trackDialog(shown)
        }
    }
    fun render() {
        status.updateText(app.account.message+"\n받은 요청·진행 중인 픽업 ${app.account.requests.size}건")
        val state=app.account.state.optString("status")
        issue.isEnabled=app.registration.vehicleToken!=null && !app.account.busy && state!="LINKED"
        qr.visibility=if (app.account.qrLink!=null) View.VISIBLE else View.GONE
        confirm.visibility=if (state=="AWAITING_VEHICLE_CONFIRMATION") View.VISIBLE else View.GONE
        confirm.isEnabled=!app.account.busy
        inbox.isEnabled=state=="LINKED" && !app.account.busy
        refresh.isEnabled=!app.account.busy && app.registration.vehicleToken!=null
        if (shownQr!=null && shownQr!=app.account.qrLink) { qrDialog?.dismiss(); qrDialog=null; shownQr=null }
    }
    private fun stateLabel(state: String)=when(state) { "PENDING"->"응답 대기"; "QUEUED"->"픽업 대기"; "ACTIVE"->"픽업 진행 중"; else->state }
}
