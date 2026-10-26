package app.majung.vehicle

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.view.View
import android.widget.Button

internal class PickupParticipantSection(private val app: VehicleApplication, private val views: VehicleViews) {
    private val inviteButton: Button
    private val qrButton: Button
    private var qrDialog: AlertDialog? = null
    private var qrLink: String? = null
    private val approveButton: Button
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
        qrButton = views.button("휴대전화로 초대 공유 · QR") {
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
        views.button("서버 상태 다시 확인") { app.pickup.refresh() }
    }

    fun render() {
        inviteButton.isEnabled = app.pickup.invitationForHostBrowser() != null
        val currentLink = app.pickup.invitationForSharingBrowser()
        qrButton.isEnabled = currentLink?.startsWith("https://") == true
        if (qrLink != null && qrLink != currentLink) { qrDialog?.dismiss(); qrDialog = null; qrLink = null }
        // New participants connect without approval; the button only serves pickups an older server left waiting.
        approveButton.visibility = if (app.pickup.canApprove) View.VISIBLE else View.GONE
        approveButton.isEnabled = app.pickup.canApprove
        disconnectButton.isEnabled = app.pickup.canDisconnect
    }
}
