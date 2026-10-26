package app.majung.vehicle

import kotlin.math.ceil

/** Read-only status: no identity, location coordinates or estimated queue ETA. */
internal data class DrivingPickupSummary(val phase: String, val readiness: String, val eta: String) {
    companion object {
        fun from(known: Boolean, status: String, online: Boolean, needsConfirmation: Boolean,
                 sharing: Boolean, readiness: String, durationSeconds: Long?): DrivingPickupSummary {
            if (!known) return DrivingPickupSummary("차량 상태 확인 중", "", "정차 후 확인해주세요")
            if (status.isBlank()) return DrivingPickupSummary("진행 중인 픽업 없음", "", "정차 후 픽업을 준비해주세요")
            if (status in setOf("COMPLETED", "CANCELLED", "EXPIRED"))
                return DrivingPickupSummary("픽업 공유 종료", "", "")
            if (!online) return DrivingPickupSummary("서버 연결 확인 중", "", "새 정보를 기다리고 있어요")
            if (needsConfirmation) return DrivingPickupSummary("이전 픽업 확인 필요", "", "정차 후 계속하기를 눌러주세요")
            if (status !in setOf("ACTIVE", "ARRIVED"))
                return DrivingPickupSummary("탑승자 연결 대기", "", "정차 후 확인해주세요")
            val ready = when (readiness) {
                "READY" -> "탑승자 준비 완료"
                "PREPARING" -> "탑승자 준비 중"
                else -> "탑승자 준비 상태 대기"
            }
            val time = when {
                status == "ARRIVED" -> "정차 후 탑승을 확인해주세요"
                !sharing -> "위치 공유가 멈춰 있어요"
                durationSeconds == null || durationSeconds < 0 -> "남은 시간 확인 중"
                durationSeconds < 60 -> "곧 도착"
                else -> "약 ${ceil(durationSeconds / 60.0).toLong()}분 남음"
            }
            return DrivingPickupSummary(if (status == "ARRIVED") "약속 장소에 도착했어요" else "픽업 진행 중", ready, time)
        }
    }
}
