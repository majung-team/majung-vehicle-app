package app.majung.vehicle

/** HTTP acceptance and visibility to the passenger are different states. */
internal object VehicleSharingFeedback {
    fun accepted(hasSample: Boolean, freshness: String, validForMillis: Long): String = when {
        !hasSample && freshness == "WAITING" -> "서버의 이전 위치·ETA를 지웠어요"
        hasSample && freshness == "FRESH" && validForMillis > 0 -> "최신 위치·ETA를 탑승자에게 공유하고 있어요"
        freshness == "STALE" -> "서버에서 만료된 정보로 판단해 공유하지 않아요. 차량 날짜·시간과 SDK 갱신 상태를 확인해주세요"
        freshness == "WAITING" -> "서버에 공유할 최신 위치·ETA가 없어요"
        else -> "서버의 공유 상태를 확인하지 못했어요. 전송 성공으로 처리하지 않아요"
    }

    fun rejected(code: String?): String = when (code) {
        "FUTURE_SOURCE_TIME" -> "차량 정보의 시각이 서버보다 빨라 전송이 거절됐어요. 차량의 자동 날짜·시간 설정을 확인해주세요"
        "STALE_EVENT" -> "서버가 이전 순서·시각의 차량 정보를 거절했어요. 최신 SDK 정보를 다시 확인하고 있어요"
        else -> "차량 정보 전송을 확인하지 못했어요. 이전 정보는 서버에서 만료돼요."
    }
}
