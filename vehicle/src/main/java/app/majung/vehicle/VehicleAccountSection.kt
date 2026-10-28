package app.majung.vehicle

import android.app.AlertDialog
import android.view.View
import android.widget.ImageView
import org.json.JSONObject

internal class VehicleAccountSection(private val app: VehicleApplication,private val views: VehicleViews) {
    private val people=VehiclePeopleSection(app,views,::showConnectionQr)
    private val heading = views.label("", 24f).apply { setTypeface(typeface, android.graphics.Typeface.BOLD) }.also(views::addView)
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
    fun inviteSelectedPlace() {
        val selected = selectedPlace() ?: return
        if (!views.canInteract || app.account.busy || app.account.state.optString("status") != "LINKED") return
        loadContacts(selected)
    }
    private fun loadContacts(place: PickupPlace, page: Int = 0) {
        if (!views.canInteract || app.account.busy) return
        if (app.registration.vehicleToken==null || app.account.state.optString("status")!="LINKED") {
            AlertDialog.Builder(views.context).setTitle("운전자 연결을 확인해주세요")
                .setMessage("연결 상태가 바뀌었어요. 설정에서 차량과 운전자를 다시 연결한 뒤 초대해주세요.")
                .setPositiveButton("확인",null).show().also(views::trackDialog)
            return
        }
        val vehicleToken=app.registration.vehicleToken
        val driverId=app.account.state.optString("majungId")
        fun sameDriver()=vehicleToken==app.registration.vehicleToken &&
            driverId==app.account.state.optString("majungId") && app.account.state.optString("status")=="LINKED"
        val waiting=AlertDialog.Builder(views.context).setTitle("초대할 사람을 불러오고 있어요")
            .setMessage("연결된 사람 중에서 초대할 사람을 고를 수 있어요.")
            .setNegativeButton("닫기",null).show().also(views::trackDialog)
        app.account.loadContacts(page, failed = {
            val visible=waiting.isShowing
            waiting.dismiss()
            if(visible && views.canInteract && sameDriver()) AlertDialog.Builder(views.context)
                .setTitle("연결 목록을 불러오지 못했어요")
                .setMessage("인터넷 연결을 확인한 뒤 다시 시도해주세요. 초대는 보내지 않았어요.")
                .setPositiveButton("다시 시도") { _,_-> if(invitationPlaceStillCurrent(place)) loadContacts(place,page) }
                .setNegativeButton("닫기",null).show().also(views::trackDialog)
        }) {
            val visible=waiting.isShowing
            waiting.dismiss()
            if(visible && views.canInteract && sameDriver() && selectedPlace()?.sameCoordinates(place)==true) showContacts(place)
        }
    }
    private fun selectedPlace(): PickupPlace? {
        val selected = app.destination.selection.current(android.os.SystemClock.elapsedRealtime()) ?: return null
        return selected.takeIf { app.destination.currentStops()?.resolve(it) != null && !app.destination.addingWaypoint }
    }
    private fun invitationPlaceStillCurrent(place: PickupPlace): Boolean {
        if (!views.canInteract) return false
        if (selectedPlace()?.sameCoordinates(place) == true) return true
        AlertDialog.Builder(views.context).setMessage("만날 장소가 바뀌었거나 경로 확인이 끝나지 않았어요. 새 장소를 확인한 뒤 다시 초대해주세요.")
            .setPositiveButton("확인", null).show().also(views::trackDialog)
        return false
    }
    private fun showContacts(place: PickupPlace) {
        val items = app.account.contacts
        val page = app.account.contactsPage
        val dialog = AlertDialog.Builder(views.context).setTitle("누구를 ${place.label}에서 태울까요?")
            .setMessage(if (items.isEmpty()) "연결된 사람이 아직 없어요.\n닉네임으로 찾거나 QR 초대 링크를 보내주세요." else null)
            .setNegativeButton("닫기", null)
        if (items.isEmpty()) {
            dialog.setPositiveButton("사람 찾기") { _, _ -> people.find() }
            dialog.setNeutralButton("QR로 초대") { _, _ ->
                if (invitationPlaceStillCurrent(place) && app.pickup.canCreate)
                    app.pickup.create(place, app.destination.referenceFor(place))
            }
        }
        if (items.isNotEmpty()) dialog.setItems(items.map { "${it.optString("nickname")} · ${it.optString("majungId")}" }.toTypedArray()) { _, index ->
            if (!invitationPlaceStillCurrent(place)) return@setItems
            val person = items[index]
            AlertDialog.Builder(views.context).setTitle("${person.optString("nickname")} 님께 초대할까요?")
                .setMessage("${person.optString("majungId")}\n만날 장소 · ${place.label}\n탑승자가 마중 웹의 ‘픽업’에서 수락하면 픽업 대기에 들어가요. 웹을 닫아두면 알림이 오지 않아요.")
                .setPositiveButton("초대 보내기") { _, _ ->
                    if (invitationPlaceStillCurrent(place))
                        app.account.invite(person.getString("connectionId"), place)
                }.setNegativeButton("돌아가기", null).show().also(views::trackDialog)
        }
        if (page == 0 && items.isNotEmpty()) dialog.setNeutralButton("사람 추가") { _, _ -> people.open() }
        if (page > 0) dialog.setNeutralButton("이전 사람들") { _, _ ->
            loadContacts(place,page-1)
        }
        if (items.size == 20 && page < 50) dialog.setPositiveButton("다음 사람들") { _, _ ->
            loadContacts(place,page+1)
        }
        dialog.show().also(views::trackDialog)
    }
    private fun showConnectionQr() {
        if (!views.canInteract) return
        val image=ImageView(views.context).apply {
            setImageBitmap(InvitationQr.create("${BuildConfig.PICKUP_WEB_ORIGIN}/account"))
            adjustViewBounds=true
            minimumHeight=320
            contentDescription="사람 연결을 위한 마중 웹 QR"
        }
        AlertDialog.Builder(views.context).setTitle("휴대전화로 사람 연결하기")
            .setMessage("1. QR을 운전자 휴대전화로 열어 차량에 연결한 계정으로 로그인해주세요.\n2. ‘사람 찾기’에서 연결을 요청하고 상대방의 승인을 받아주세요.\n3. 아래 버튼으로 차량에서도 연결된 사람을 확인할 수 있어요.")
            .setView(image)
            .setPositiveButton("연결된 사람 보기") { _, _ -> if (views.canInteract) people.showConnections() }
            .setNegativeButton("닫기",null).show().also(views::trackDialog)
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
    private val emptyRequests=views.label("아직 받은 요청이 없어요.\n새 초대는 ‘픽업’에서 만들 수 있어요. 요청이 오면 이 화면에 표시돼요.",19f).also(views::addView)
    private var renderedRequests = ""
    private val manageConnections=views.button("사람 찾기 · 연결 관리") { people.open() }
    private val refresh=views.button("요청 목록 새로고침", refreshAfterAction = false) { if (!app.account.busy) app.account.refresh(showProgress = true) }
    private fun details(item: JSONObject) {
        val place=item.getJSONObject("place").let { PickupPlace(it.getString("label"),it.getDouble("latitude"),it.getDouble("longitude")) }
        val id=item.getString("requestId")
        val dialog=AlertDialog.Builder(views.context).setTitle("${item.optString("nickname")} · ${stateLabel(item.optString("status"))}")
            .setMessage("${item.optString("majungId")}\n만날 장소 · ${place.label}\n" + if (item.optString("status")=="INVITED") "탑승자의 수락을 기다리고 있어요. 수락 전에는 위치를 공유하지 않아요." else "현재 픽업을 끝내기 전에는 다음 픽업을 시작하지 않아요.")
            .setNegativeButton("돌아가기",null)
        when (item.optString("status")) {
            "INVITED" -> dialog.setPositiveButton("초대 취소") { _, _ -> if (views.canInteract) app.account.cancelInvitation(id) }
            "PENDING" -> dialog.setPositiveButton(if ((app.pickup.pickupId == null || app.pickup.status in setOf("COMPLETED", "CANCELLED", "EXPIRED")) && app.account.requests.none { it.optString("status") == "ACTIVE" }) "수락하고 마중 준비" else "수락하고 대기에 추가") { _, _ ->
                if (views.canInteract) app.account.changeRequest(id, "accept") {
                    if (views.canInteract) app.requestFlow.begin(id)
                }
            }
                .setNeutralButton("거절") { _,_ -> if (views.canInteract) app.account.changeRequest(id,"reject") }
            "QUEUED" -> dialog.setPositiveButton("마중 가기") { _, _ ->
                if (views.canInteract) app.requestFlow.begin(id)
            }
            "ACTIVE" -> dialog.setPositiveButton("진행 중인 픽업 복구") { _,_ -> if (views.canInteract && app.pickup.canStartRequested(id)) app.pickup.startRequested(id,place,app.destination.referenceFor(place)) }
        }
        dialog.show().also { shown ->
            if (item.optString("status")=="QUEUED") {
                shown.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled=!app.pickup.busy &&
                    (app.pickup.canStartRequested(id) || app.pickup.status in setOf("COMPLETED", "CANCELLED", "EXPIRED")) &&
                    app.account.requests.none { it.optString("status") == "ACTIVE" && it.optString("requestId") != id }
            }
            if (item.optString("status")=="ACTIVE") shown.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled=app.pickup.canStartRequested(id)
            views.trackDialog(shown)
        }
    }
    fun render(settings: Boolean = false) {
        heading.updateText(if (settings) "운전자와 사람 연결" else "받은 요청 · ${app.account.requests.size}건")
        status.updateText(app.account.message+"\n받은 요청·진행 중인 픽업 ${app.account.requests.size}건")

        issue.visibility = if (settings && app.account.state.optString("status") != "LINKED") View.VISIBLE else View.GONE
        qr.visibility = if (settings) View.VISIBLE else View.GONE
        emptyRequests.visibility = if (!settings && app.account.requests.isEmpty()) View.VISIBLE else View.GONE
        inbox.visibility = if (settings || app.account.requests.size <= 6) View.GONE else View.VISIBLE
        requestRows.visibility = if (settings) View.GONE else View.VISIBLE
        val requestSignature = app.account.requests.joinToString { it.toString() }
        if (requestSignature != renderedRequests) {
            renderedRequests = requestSignature
            requestRows.removeAllViews()
            app.account.requests.sortedBy { when(it.optString("status")) { "ACTIVE" -> 0; "PENDING" -> 1; "QUEUED" -> 2; else -> 3 } }.take(6).forEach { item ->
                val row = android.widget.Button(views.context).apply {
                    flattenButtonSurface()
                    text = "${item.optString("nickname")} · ${stateLabel(item.optString("status"))}\n${item.getJSONObject("place").optString("label")}"
                    isAllCaps = false; textSize = 20f; minimumHeight = dp(96); gravity = android.view.Gravity.CENTER_VERTICAL or android.view.Gravity.START
                    setTextColor(android.graphics.Color.rgb(32,40,46))
                    setPadding(dp(24), dp(16), dp(24), dp(16))
                    backgroundTintList = null
                    background = android.graphics.drawable.StateListDrawable().apply {
                        fun shape(color: Int) = android.graphics.drawable.GradientDrawable().apply {
                            setColor(color); cornerRadius = dp(14).toFloat()
                            setStroke(dp(1), android.graphics.Color.rgb(208, 225, 237))
                        }
                        addState(intArrayOf(android.R.attr.state_pressed), shape(android.graphics.Color.rgb(218, 236, 247)))
                        addState(intArrayOf(android.R.attr.state_focused), shape(android.graphics.Color.rgb(218, 236, 247)).apply { setStroke(dp(3), android.graphics.Color.rgb(50, 112, 155)) })
                        addState(intArrayOf(), shape(android.graphics.Color.rgb(241, 247, 251)))
                    }
                    setOnClickListener { if (views.canInteract && !app.account.busy) details(item) }
                }
                requestRows.addView(row, android.widget.LinearLayout.LayoutParams(-1,-2).apply { topMargin = (8*views.context.resources.displayMetrics.density).toInt() })
            }
        }
        val state=app.account.state.optString("status")
        manageConnections.visibility=if (settings && state=="LINKED") View.VISIBLE else View.GONE
        manageConnections.isEnabled=state=="LINKED" && !app.account.busy
        issue.isEnabled=app.registration.vehicleToken!=null && !app.account.busy && state!="LINKED"
        qr.visibility=if (settings && app.account.qrLink!=null) View.VISIBLE else View.GONE
        confirm.visibility=if (settings && state=="AWAITING_VEHICLE_CONFIRMATION") View.VISIBLE else View.GONE
        confirm.isEnabled=!app.account.busy
        inbox.isEnabled=state=="LINKED" && !app.account.busy
        refresh.updateText(when {
            app.account.refreshing -> "새로운 정보 확인 중…"
            settings -> "운전자 연결 상태 새로고침"
            else -> "요청 목록 새로고침"
        })
        // Keep the pressed surface and layout stable; the controller rejects duplicate requests.
        refresh.isEnabled=app.registration.vehicleToken!=null
        refresh.contentDescription = if (app.account.refreshing) "새로운 정보를 확인하고 있어요" else refresh.text
        refresh.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        if (shownQr!=null && shownQr!=app.account.qrLink) { qrDialog?.dismiss(); qrDialog=null; shownQr=null }
    }
    private fun dp(value: Int) = (value * views.context.resources.displayMetrics.density).toInt()
    private fun stateLabel(state: String)=when(state) { "INVITED"->"보낸 초대 · 탑승자 응답 대기"; "PENDING"->"응답 대기"; "QUEUED"->"픽업 대기"; "ACTIVE"->"픽업 진행 중"; else->state }
}
