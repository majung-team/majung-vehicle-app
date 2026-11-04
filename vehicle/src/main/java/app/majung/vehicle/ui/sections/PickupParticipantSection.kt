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
import android.content.ClipData
import android.content.ClipboardManager
import android.view.View
import android.widget.Button

internal class PickupParticipantSection(private val app: VehicleApplication, private val views: VehicleViews) {
    private val inviteButton: Button
    private val qrButton: Button
    private val inlineQr: android.widget.ImageView
    private val invitationPanel: VehicleColumns
    private var inlineQrLink: String? = null
    private var qrDialog: AlertDialog? = null
    private var qrLink: String? = null
    private val approveButton: Button
    private val changePlaceButton: Button
    private val confirmPlaceButton: Button
    private val proposalPanel = android.widget.LinearLayout(views.context).apply {
        orientation = android.widget.LinearLayout.VERTICAL
        setPadding(24.dp, 20.dp, 24.dp, 24.dp)
        background = android.graphics.drawable.GradientDrawable().apply {
            setColor(android.graphics.Color.rgb(239, 245, 255))
            cornerRadius = 20.dp.toFloat()
            setStroke(1.dp, android.graphics.Color.rgb(191, 219, 254))
        }
    }
    private val proposalTitle = views.label("탑승자가 장소 변경을 요청했어요", 26f).apply {
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setTextColor(android.graphics.Color.rgb(30, 64, 175))
    }
    private val proposalDetails = views.label("", 22f)
    private val proposalHint = views.label("", 18f)
    private val disconnectButton: Button

    init {
        inviteButton = views.button("탑승자 초대 링크 복사") {
            app.pickup.invitationForHostBrowser()?.let { link ->
                val clipboard = views.context.getSystemService(ClipboardManager::class.java)
                clipboard.setPrimaryClip(ClipData.newPlainText("마중 픽업 초대", link))
                AlertDialog.Builder(views.context).setMessage(if (link.startsWith("https://")) "탑승자에게 링크를 보내주세요. 링크를 열고 참여하면 바로 연결돼요." else "로컬 시험 주소예요. 이 Mac의 브라우저에서 열어주세요.")
                    .setPositiveButton("확인", null).show().also(views::trackDialog)
            }
        }
        qrButton = views.button("초대 QR 크게 보기") {
            val link = app.pickup.invitationForSharingBrowser()?.takeIf { it.startsWith("https://") } ?: return@button
            try {
                val image = android.widget.ImageView(views.context).apply {
                    setImageBitmap(InvitationQr.create(link))
                    adjustViewBounds = true
                    minimumHeight = 320
                    contentDescription = "운전자 휴대전화로 스캔하는 픽업 초대 QR"
                }
                qrLink = link
                qrDialog = AlertDialog.Builder(views.context).setTitle("운전자 휴대전화로 스캔해주세요")
                    .setMessage("열린 화면에서 카카오톡 등으로 탑승자에게 초대를 보내주세요. 이 QR을 열기만 해서는 참여되지 않아요.")
                    .setView(image).setPositiveButton("닫기", null).show().also(views::trackDialog)
            } catch (_: Exception) {
                AlertDialog.Builder(views.context).setMessage("QR을 만들지 못했어요. 초대 링크 복사를 사용해주세요.")
                    .setPositiveButton("확인", null).show().also(views::trackDialog)
            }
        }
        inlineQr = android.widget.ImageView(views.context).apply {
            adjustViewBounds = true
            maxHeight = (240 * resources.displayMetrics.density).toInt()
            contentDescription = "운전자 휴대전화로 열어 탑승자에게 전달하는 초대 QR"
            setPadding(16,16,16,16)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.WHITE)
                cornerRadius = 16.dp.toFloat()
                setStroke(1.dp, android.graphics.Color.rgb(225, 235, 249))
            }
        }
        invitationPanel = VehicleColumns(views.context)
        invitationPanel.leading.addView(inlineQr, android.widget.LinearLayout.LayoutParams(-1, 240.dp))
        invitationPanel.trailing.addView(views.label("휴대전화로 초대해주세요", 24f).apply { setTypeface(typeface, android.graphics.Typeface.BOLD) })
        invitationPanel.trailing.addView(views.label("1. 운전자 휴대전화로 QR 스캔\n2. 열린 화면에서 초대 링크 공유\n3. 탑승자가 참여하면 연결 완료", 19f))
        invitationPanel.trailing.addView(views.label("QR을 열기만 해서는 픽업에 참여되지 않아요.", 16f))
        views.prependView(invitationPanel)
        approveButton = views.button("참여자 확인하고 공유 시작") {
            AlertDialog.Builder(views.context).setMessage("초대한 사람이 맞나요? 확인하면 이번 픽업의 장소를 공유해요.")
                .setPositiveButton("확인하고 시작") { _, _ -> if (views.canInteract) app.pickup.approve() }
                .setNegativeButton("돌아가기", null).show().also(views::trackDialog)
        }
        disconnectButton = views.button("탑승자 끊기") {
            AlertDialog.Builder(views.context).setMessage("연결된 탑승자를 끊을까요? 이 탑승자는 더 이상 차량 위치를 볼 수 없고, 새 초대로 다시 연결할 수 있어요.")
                .setPositiveButton("끊기") { _, _ -> if (views.canInteract) app.pickup.disconnectParticipant() }
                .setNegativeButton("돌아가기", null).show().also(views::trackDialog)
        }
        proposalPanel.addView(proposalTitle)
        proposalPanel.addView(proposalDetails)
        proposalPanel.addView(proposalHint)
        views.prependView(proposalPanel)
        confirmPlaceButton = views.button("새 장소 확정", primary = true, parent = proposalPanel) {
            app.pickup.autoConfirmProposalId = null
            if (views.canInteract) app.pickup.confirmProposedPlace(app.destination)
        }
        changePlaceButton = views.button("제안 장소로 경로 확인", primary = true, parent = proposalPanel) {
            val old = app.pickup.place ?: return@button
            val next = app.pickup.placeProposal ?: return@button
            val proposal = app.pickup.proposalId
            AlertDialog.Builder(views.context).setTitle("${next.label}로 바꿀까요?")
                .setMessage("최종 목적지와 다른 경유지는 유지하고 추천 경로를 다시 찾아요. 내비 경로와 도착 시간이 확인되면 약속 장소도 함께 바뀌어요.")
                .setPositiveButton("내비 경로 확인") { _, _ ->
                    if (views.canInteract && app.pickup.status == "ACTIVE" && app.pickup.proposalId == proposal && proposal != null) {
                        app.pickup.autoConfirmProposalId = proposal
                        if (!app.destination.changePickupPlace(old, next, proposal)) {
                            app.destination.reviewProposedPlace(next, proposal)
                        }
                        render()
                    }
                }.setNegativeButton("돌아가기", null).show().also(views::trackDialog)
        }
        views.button("픽업 상태 새로고침") { app.pickup.refresh() }
    }

    private val Int.dp: Int get() = (this * views.context.resources.displayMetrics.density).toInt()

    fun render() {
        val inviting = app.pickup.status == "INVITING"
        inviteButton.visibility = if (inviting) View.VISIBLE else View.GONE
        qrButton.visibility = if (inviting) View.VISIBLE else View.GONE
        disconnectButton.visibility = if (app.pickup.canDisconnect) View.VISIBLE else View.GONE
        inviteButton.isEnabled = app.pickup.invitationForHostBrowser() != null
        val currentLink = app.pickup.invitationForSharingBrowser()
        val displayQr = currentLink?.takeIf { inviting && it.startsWith("https://") }
        if (displayQr != inlineQrLink) {
            inlineQrLink = displayQr
            if (displayQr == null) inlineQr.setImageDrawable(null)
            else try { inlineQr.setImageBitmap(InvitationQr.create(displayQr)) } catch (_: Exception) { inlineQrLink = null }
        }
        inlineQr.visibility = if (displayQr != null && inlineQrLink != null) View.VISIBLE else View.GONE
        invitationPanel.visibility = inlineQr.visibility

        qrButton.isEnabled = currentLink?.startsWith("https://") == true
        if (qrLink != null && qrLink != currentLink) { qrDialog?.dismiss(); qrDialog = null; qrLink = null }
        // New participants connect without approval; the button only serves pickups an older server left waiting.
        approveButton.visibility = if (app.pickup.canApprove) View.VISIBLE else View.GONE
        approveButton.isEnabled = app.pickup.canApprove
        val proposed = app.pickup.placeProposal
        if (! app.pickup.online || app.pickup.status != "ACTIVE" || app.pickup.proposalId == null || app.destination.failure != null) {
            if (app.pickup.proposalId != app.pickup.autoConfirmProposalId || app.destination.failure != null) app.pickup.autoConfirmProposalId = null
        }
        val canChange = app.pickup.status == "ACTIVE" && app.pickup.online && !app.pickup.busy &&
            !app.pickup.needsSharingConfirmation && app.pickup.proposalId != null && proposed != null
        val hasProposal = app.pickup.proposalId != null && proposed != null && app.pickup.status == "ACTIVE"
        val verified = proposed?.let {
            app.destination.verifiedPlaceChange(it) != null &&
                app.destination.telemetry.current(it, android.os.SystemClock.elapsedRealtime()) != null
        } == true
        val checkingRoute = hasProposal && !verified && app.destination.checkingPlaceProposal
        if (verified && canChange && app.pickup.autoConfirmProposalId != null && app.pickup.autoConfirmProposalId == app.pickup.proposalId) {
            app.pickup.autoConfirmProposalId = null
            app.pickup.confirmProposedPlace(app.destination)
            return
        }
        proposalPanel.visibility = if (hasProposal) View.VISIBLE else View.GONE
        proposalDetails.updateText("현재 약속 · ${app.pickup.place?.label.orEmpty()}\n제안 장소 · ${proposed?.label.orEmpty()}")
        proposalHint.updateText(when {
            !app.pickup.online -> "연결을 다시 확인하고 있어요. 연결되면 제안을 확인할 수 있어요."
            app.pickup.needsSharingConfirmation -> "이전 픽업을 계속한 뒤 장소 변경을 확인해주세요."
            app.pickup.busy -> "새 약속 장소를 반영하고 있어요. 잠시만 기다려주세요."
            checkingRoute -> "내비게이션에 제안 장소 경유 경로를 반영하고 도착 시간을 확인하고 있어요…"
            verified -> "내비 경로를 확인했어요. 아래에서 확정하면 탑승자의 약속 장소도 바뀌어요."
            else -> "아직 약속 장소는 그대로예요. 먼저 제안 장소로 경로를 확인해주세요."
        })
        views.styleButton(changePlaceButton, primary = !verified)
        changePlaceButton.updateText(when {
            checkingRoute -> "내비 경로 확인 중…"
            verified -> "내비 경로 다시 확인"
            else -> "제안 장소로 경로 확인"
        })
        changePlaceButton.isEnabled = canChange && !checkingRoute
        confirmPlaceButton.visibility = if (verified) View.VISIBLE else View.GONE
        confirmPlaceButton.isEnabled = canChange && verified
        disconnectButton.isEnabled = app.pickup.canDisconnect
    }
}
