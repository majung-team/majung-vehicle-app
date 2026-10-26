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
        val challengeId=state.optString("challengeId")
        val majungId=state.optString("majungId")
        AlertDialog.Builder(views.context).setTitle("이 계정을 차량에 연결할까요?")
            .setMessage("${state.optString("nickname")} · ${state.optString("majungId")}\n운전자 본인의 계정인지 확인해주세요.")
            .setPositiveButton("연결 승인") { _,_ -> if (views.canInteract) app.account.confirm(challengeId, majungId) }.setNegativeButton("돌아가기",null).show().also(views::trackDialog)
    }
    private val invite = views.button("연결된 탑승자에게 초대 보내기") {
        val selected = selectedPlace() ?: return@button
        app.account.loadContacts { if (views.canInteract && selectedPlace()?.sameCoordinates(selected) == true) showContacts(selected) }
    }.apply { id = R.id.invite_connected_passenger }
    private fun selectedPlace(): PickupPlace? {
        val selected = app.destination.selection.current(android.os.SystemClock.elapsedRealtime()) ?: return null
        return selected.takeIf { app.destination.currentStops()?.resolve(it) != null && !app.destination.addingWaypoint }
    }
    private fun showContacts(place: PickupPlace) {
        val items = app.account.contacts
        val page = app.account.contactsPage
        val dialog = AlertDialog.Builder(views.context).setTitle("누구를 ${place.label}에서 태울까요?")
            .setMessage(if (items.isEmpty()) "연결된 사람이 없어요. 휴대전화의 ‘내 연결’에서 먼저 사람을 연결하거나 기존 QR 초대를 이용해주세요." else null)
            .setNegativeButton("닫기", null)
        if (items.isNotEmpty()) dialog.setItems(items.map { "${it.optString("nickname")} · ${it.optString("majungId")}" }.toTypedArray()) { _, index ->
            if (!views.canInteract || selectedPlace()?.sameCoordinates(place) != true) return@setItems
            val person = items[index]
            AlertDialog.Builder(views.context).setTitle("${person.optString("nickname")} 님께 초대할까요?")
                .setMessage("${person.optString("majungId")}\n만날 장소 · ${place.label}\n탑승자가 마중 웹의 ‘내 연결’에서 수락하면 픽업 대기에 들어가요. 웹을 닫아두면 알림이 오지 않아요.")
                .setPositiveButton("초대 보내기") { _, _ ->
                    if (views.canInteract && selectedPlace()?.sameCoordinates(place) == true)
                        app.account.invite(person.getString("connectionId"), place)
                }.setNegativeButton("돌아가기", null).show().also(views::trackDialog)
        }
        if (page > 0) dialog.setNeutralButton("이전 사람들") { _, _ ->
            if (views.canInteract) app.account.loadContacts(page - 1) { if (views.canInteract && selectedPlace()?.sameCoordinates(place) == true) showContacts(place) }
        }
        if (items.size == 20 && page < 50) dialog.setPositiveButton("다음 사람들") { _, _ ->
            if (views.canInteract) app.account.loadContacts(page + 1) { if (views.canInteract && selectedPlace()?.sameCoordinates(place) == true) showContacts(place) }
        }
        dialog.show().also(views::trackDialog)
    }
    private val inbox=views.button("받은 마중 요청과 픽업 대기 목록") {
        val items=app.account.requests
        if (items.isEmpty()) {
            AlertDialog.Builder(views.context).setMessage("받은 요청이나 대기 중인 픽업이 없어요. 새로 확인해주세요.").setPositiveButton("확인",null).show().also(views::trackDialog)
        } else AlertDialog.Builder(views.context).setTitle("마중 요청 · 수락 순서대로 진행")
            .setItems(items.map { "${it.optString("nickname")} · ${it.getJSONObject("place").optString("label")} · ${stateLabel(it.optString("status"))}" }.toTypedArray()) { _,index -> if (views.canInteract) details(items[index]) }
            .setNegativeButton("닫기",null).show().also(views::trackDialog)
    }
    private val requestRows = android.widget.LinearLayout(views.context).apply {
        orientation = android.widget.LinearLayout.VERTICAL
    }.also(views::addView)
    private var renderedRequests = ""
    private val refresh=views.button("계정·마중 요청 새로 확인") { app.account.refresh() }
    private fun details(item: JSONObject) {
        val place=item.getJSONObject("place").let { PickupPlace(it.getString("label"),it.getDouble("latitude"),it.getDouble("longitude")) }
        val id=item.getString("requestId")
        val dialog=AlertDialog.Builder(views.context).setTitle("${item.optString("nickname")} · ${stateLabel(item.optString("status"))}")
            .setMessage("${item.optString("majungId")}\n만날 장소 · ${place.label}\n" + if (item.optString("status")=="INVITED") "탑승자의 수락을 기다리고 있어요. 수락 전에는 위치를 공유하지 않아요." else "현재 픽업을 끝내기 전에는 다음 픽업을 시작하지 않아요.")
            .setNegativeButton("돌아가기",null)
        when (item.optString("status")) {
            "INVITED" -> dialog.setPositiveButton("초대 취소") { _, _ -> if (views.canInteract) app.account.cancelInvitation(id) }
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
    fun render(settings: Boolean = false) {
        status.updateText(app.account.message+"\n받은 요청·진행 중인 픽업 ${app.account.requests.size}건")
        issue.visibility = if (settings) View.VISIBLE else View.GONE
        qr.visibility = if (settings) View.VISIBLE else View.GONE
        invite.visibility = if (settings) View.GONE else View.VISIBLE
        inbox.visibility = if (settings || app.account.requests.size <= 6) View.GONE else View.VISIBLE
        requestRows.visibility = if (settings) View.GONE else View.VISIBLE
        val requestSignature = app.account.requests.joinToString { it.toString() }
        if (requestSignature != renderedRequests) {
            renderedRequests = requestSignature
            requestRows.removeAllViews()
            app.account.requests.sortedBy { when(it.optString("status")) { "PENDING" -> 0; "ACTIVE" -> 1; "QUEUED" -> 2; else -> 3 } }.take(6).forEach { item ->
                val row = android.widget.Button(views.context).apply {
                    text = "${item.optString("nickname")} · ${stateLabel(item.optString("status"))}\n${item.getJSONObject("place").optString("label")}"
                    isAllCaps = false; textSize = 19f; gravity = android.view.Gravity.CENTER_VERTICAL or android.view.Gravity.START
                    setTextColor(android.graphics.Color.rgb(32,40,46))
                    setPadding(20,16,20,16)
                    backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.WHITE)
                    setOnClickListener { if (views.canInteract && !app.account.busy) details(item) }
                }
                requestRows.addView(row, android.widget.LinearLayout.LayoutParams(-1,(96*views.context.resources.displayMetrics.density).toInt()).apply { topMargin = (8*views.context.resources.displayMetrics.density).toInt() })
            }
        }
        val state=app.account.state.optString("status")
        issue.isEnabled=app.registration.vehicleToken!=null && !app.account.busy && state!="LINKED"
        qr.visibility=if (settings && app.account.qrLink!=null) View.VISIBLE else View.GONE
        confirm.visibility=if (settings && state=="AWAITING_VEHICLE_CONFIRMATION") View.VISIBLE else View.GONE
        confirm.isEnabled=!app.account.busy
        invite.isEnabled=state=="LINKED" && !app.account.busy && selectedPlace()!=null
        inbox.isEnabled=state=="LINKED" && !app.account.busy
        refresh.isEnabled=!app.account.busy && app.registration.vehicleToken!=null
        if (shownQr!=null && shownQr!=app.account.qrLink) { qrDialog?.dismiss(); qrDialog=null; shownQr=null }
    }
    private fun stateLabel(state: String)=when(state) { "INVITED"->"보낸 초대 · 탑승자 응답 대기"; "PENDING"->"응답 대기"; "QUEUED"->"픽업 대기"; "ACTIVE"->"픽업 진행 중"; else->state }
}
