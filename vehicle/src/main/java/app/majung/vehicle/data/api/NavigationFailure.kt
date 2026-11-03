package app.majung.vehicle

/** Safe display text only. SDK payloads, exception messages and locations are excluded. */
enum class NavigationFailure(val message: String) {
    DISCONNECTED("내비게이션 연결이 끊겼어요. 연결 버튼을 눌러 다시 연결해주세요."),
    CONNECTION_TIMEOUT("내비게이션 연결 응답이 없어요. 내비 앱을 실행한 뒤 다시 연결해주세요."),
    CONNECTION_FAILED("내비게이션에 연결하지 못했어요. 내비게이션 앱을 실행한 뒤 다시 연결해주세요."),
    PERMISSION_DENIED("내비게이션 접근 권한이 없어요. 앱 권한과 차량 연결 설정을 확인해주세요."),
    INVALID_PLACE("장소 이름이나 좌표를 확인할 수 없어요. 다른 장소를 선택해주세요."),
    ROUTE_UNVERIFIED("현재 경로를 확인하지 못했어요. 내비게이션에서 안내를 시작한 뒤 다시 확인해주세요."),
    WAYPOINT_LIMIT("경유지는 두 곳까지 추가할 수 있어요. 내비게이션에서 기존 경유지를 정리해주세요."),
    DUPLICATE_PLACE("같은 장소가 이미 경로에 있어요. 다른 장소를 선택해주세요."),
    WAYPOINT_COORDINATES_UNAVAILABLE("내비게이션에서 경유지 좌표를 받지 못했어요. 이 경유지로는 픽업을 만들 수 없어요."),
    ROUTE_CHANGED("경유지를 추가하는 동안 기존 경로가 바뀌었어요. 경로와 픽업 장소를 다시 확인해주세요."),
    PICKUP_NOT_FOUND("픽업 장소를 현재 경로에서 찾지 못했어요. 장소를 다시 선택해주세요."),
    RESPONSE_TIMEOUT("내비게이션 응답이 늦어지고 있어요. 반복되면 연결 상태를 확인한 뒤 다시 조회해주세요."),
    RECENT_PLACES_TIMEOUT("최근 장소 목록을 받지 못했어요. 내비게이션 연결을 확인한 뒤 다시 선택해주세요."),
    WAYPOINT_CONFIRMATION_TIMEOUT("추가한 경유지를 확인하지 못했어요. 내비게이션 경로를 확인한 뒤 장소를 다시 선택해주세요."),
    INVALID_REQUEST("내비게이션 요청을 처리할 수 없어요. 장소와 경로를 다시 확인해주세요."),
    SDK_REQUEST_FAILED("내비게이션 정보를 가져오지 못했어요. 연결 상태를 확인한 뒤 다시 조회해주세요."),
    LOW_BATTERY("배터리 부족으로 내비게이션 요청을 처리하지 못했어요. 차량 상태를 확인해주세요."),
    ROUTE_TOO_LONG("이동 거리가 내비게이션의 탐색 범위를 넘었어요. 더 가까운 장소를 선택해주세요."),
    ROUTE_TOO_SHORT("현재 위치와 장소가 너무 가까워 경로를 만들지 못했어요. 다른 장소를 선택해주세요."),
    ROUTE_SEARCH_FAILED("내비게이션에서 경로를 찾지 못했어요. 네트워크와 장소를 확인한 뒤 다시 시도해주세요.");
}
