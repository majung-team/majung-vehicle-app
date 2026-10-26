package app.majung.vehicle

import android.widget.TextView

internal class PickupStatusSection(private val app: VehicleApplication, private val views: VehicleViews) {
    private val connectionText: TextView
    private val pickupText: TextView

    init {
        connectionText = views.label("", 14f).apply {
            id = R.id.connection_status
            setLines(1)
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        views.addView(connectionText)
        pickupText = views.label("", 18f).apply { id = R.id.pickup_details }
        views.addView(pickupText)
    }

    fun render() {
        val pickup = app.pickup
        val readiness = when (pickup.readiness) {
            "READY" -> "준비 완료"
            "PREPARING" -> "출발 준비 중"
            else -> "아직 준비 상태를 바꾸지 않았어요"
        }
        val phase = when (pickup.status) {
            "RESTORING" -> "서버에서 픽업 상태를 확인 중이에요"
            "INVITING" -> "탑승자 연결을 기다리고 있어요"
            "WAITING_APPROVAL" -> "참여 요청을 확인해주세요"
            "ACTIVE" -> if (pickup.participantName != null) "탑승자 연결됨 · 픽업 진행 중" else "픽업 진행 중"
            "ARRIVED" -> "약속 장소에 도착했어요. 탑승 확인 전까지 위치 공유를 유지해요."
            "INVITE_EXPIRED" -> "초대가 만료됐어요. 픽업 취소 후 새 약속을 준비해주세요."
            "COMPLETED" -> "탑승 확인 완료 · 위치 공유 종료"
            "CANCELLED", "EXPIRED" -> "위치 공유 종료"
            else -> "아직 만든 픽업이 없어요"
        }
        connectionText.updateText(when {
            pickup.busy -> "요청 처리 중…"
            pickup.pickupId == null -> "픽업을 만들면 서버와 연결해요"
            pickup.online -> "서버 연결됨"
            else -> "서버 연결 확인 필요"
        })
        pickupText.updateText(listOfNotNull(
            pickup.place?.let { "이번 픽업 장소: ${it.label}" }, phase,
            if (pickup.needsSharingConfirmation) "재개 확인 전에는 위치·ETA를 보내지 않아요"
                else if (pickup.status in setOf("ACTIVE", "ARRIVED")) app.sharingMessage else null,
            if (pickup.status in setOf("ACTIVE", "ARRIVED") && app.sharingRunning) pickup.vehicleMessage else null,
            pickup.participantName?.let { "$it 님 · $readiness" }, pickup.message.takeIf { it.isNotBlank() }
        ).joinToString("\n\n"))
    }
}
