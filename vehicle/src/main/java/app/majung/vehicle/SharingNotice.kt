package app.majung.vehicle

import android.content.Context

/** A local, versioned choice. It is not a server-side identity or legal signature. */
internal class SharingNotice(context: Context) {
    private val preferences = context.getSharedPreferences("sharing-notice", Context.MODE_PRIVATE)
    val accepted: Boolean get() = preferences.getString("version", null) == VERSION
    fun accept(): Boolean = preferences.edit().putString("version", VERSION)
        .putLong("acceptedAt", System.currentTimeMillis()).commit()
    fun withdraw(): Boolean = preferences.edit().clear().commit()
    companion object {
        const val VERSION = "2026-10-29"
        const val TEXT = "픽업이 시작되면 차량 위치·도착 예상 시간과 탑승자의 준비 상태를 마중 서버를 통해 전달해요. 해당 픽업의 권한이 있는 탑승자만 확인할 수 있어요.\n\n픽업 완료·취소·만료 뒤에는 위치 공유가 끝나요. 종료된 픽업 기록은 7일이 지난 다음 정리 작업에서 삭제해요. 계정과 사람 연결 정보는 별도로 유지돼요.\n\n앱을 내려도 진행 중인 픽업은 공유 서비스를 통해 전송할 수 있어요. 앱 재실행 뒤에는 서버 상태와 본인 확인을 거쳐 다시 시작해요.\n\n위치 권한은 실제 공유를 시작할 때 별도로 요청해요. 동의하지 않으면 새 위치 전송을 시작하지 않아요. 설정에서 안내를 다시 보거나, 현재 픽업을 종료한 뒤 사용 동의를 철회할 수 있어요.\n\n자세한 처리방침: https://majung.cloud/privacy\n운영자 송승준(개인) · 문의 john091122@naver.com"
    }
}
