package app.majung.vehicle

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Button

internal class PickupParticipantSection(private val app: VehicleApplication, private val views: VehicleViews) {
    private val inviteButton: Button
    private val approveButton: Button

    init {
        inviteButton = views.button("개발용 초대 링크 복사") {
            app.pickup.invitationForHostBrowser()?.let { link ->
                val clipboard = views.context.getSystemService(ClipboardManager::class.java)
                clipboard.setPrimaryClip(ClipData.newPlainText("마중 개발용 초대", link))
                AlertDialog.Builder(views.context).setMessage("이 Mac의 브라우저에서 붙여넣어주세요. 실제 휴대전화용 주소는 아직 없어요.")
                    .setPositiveButton("확인", null).show()
            }
        }
        approveButton = views.button("참여자 확인하고 공유 시작") {
            AlertDialog.Builder(views.context).setMessage("초대한 사람이 맞나요? 확인하면 이번 픽업의 장소를 공유해요.")
                .setPositiveButton("확인하고 시작") { _, _ -> app.pickup.approve() }
                .setNegativeButton("돌아가기", null).show()
        }
        views.button("서버 상태 다시 확인") { app.pickup.refresh() }
    }

    fun render() {
        inviteButton.isEnabled = app.pickup.invitationForHostBrowser() != null
        approveButton.isEnabled = app.pickup.canApprove
    }
}
