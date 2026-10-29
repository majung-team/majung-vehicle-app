# NaviHelper 경유지 원본 좌표 확인 요청 자료

2026-10-29. **2026-10-29 공식 문의처에 발송한 재현 내용**이다. SDK 바이너리·내부 구현 코드·실제 사용자 위치·접근 토큰은 포함하지 않는다.

## 환경

- Pleos Connect Emulator 2.1.0 ARM64 / Android API 34
- NaviHelper 3.0.5, 내비게이션 앱 3.3.2-rc5-hotfix.3
- Kotlin DEBUG 시험 앱, 공개 SDK API 사용
- 공식 [SDK 추가 안내](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/NaviHelper/importing-sdk)와 [경유지 안내](https://document.pleos.ai/en/api-reference/connect-sdk-pleos/NaviHelper/waypoint-settings) 기준

## 현상과 재현 순서

1. `requestRoute(RouteInfo(...))`로 시험 최종 목적지를 설정하고 안내 상태를 확인한다.
2. 최종 목적지와 다른 정상 위도·경도를 가진 경유지를 추가한다.
3. 방법 A: `addWaypoint(RequestWaypointInfo(...))`. 방법 B: 같은 목적지·경유지로 `requestRouteWithWaypoints(...)`.
4. `getWaypointInfo()`와 `onWayPointInfo`를 확인한다.
5. 두 방법 모두 경유지 원본 `latitude`, `longitude`가 0으로 반환된다. 요청한 `poiId`는 유지되고 해당 경유지의 `remainTime`은 최종 목적지와 별도로 반환된다.

현재 재확인 시험에서는 외부 업체 ID를 임의로 만들지 않고 `majung:` 무작위 요청 ID와 `poiProvider="majung"`을 사용했다.
외부 장소 ID를 이용한 이전 시험에서도 좌표 누락을 관찰했다. 둘을 같은 날 다시 시험한 결과로 합치지 않는다.

## 앱에서 처리한 범위

선택 좌표와 요청 식별자를 보관하고, 같은 ID의 경유지가 정확히 하나이며 기존 경로가 유지되는지 재검증한다.
이 조건을 만족한 0 좌표 응답만 요청 좌표에 연결하고, ETA는 해당 경유지의 `remainTime`을 사용한다.
새 연결에서도 보관한 식별자와 서버 확정 장소를 다시 대조한다. 이름·순번·최종 목적지 ETA로 추정하지 않는다.
응답 ID 누락·불일치·중복이나 좌표 출처를 확인할 수 없는 경유지는 차단한다.

이것은 SDK 원본 좌표를 수정한 결과가 아니다. 앱이 정상 지점을 확인하는 처리와 SDK 제공 측의 원본 응답 문제를 분리해 추적한다.

## 제공 측에 확인할 내용

1. 위 에뮬레이터·내비·SDK 조합에서 0 좌표 반환이 알려진 현상인지, 수정 버전 또는 설정이 있는지
2. `latitude/longitude`와 `displayLatitude/displayLongitude`의 경유지별 공식 보장 범위
3. 외부 장소 ID가 없는 선택 좌표에 앱 고유 ID·provider를 넣는 방식이 공식 지원되는지
4. 앱이 추가하지 않은 기존 경유지의 좌표가 0일 때 사용할 공식 좌표 조회 경로가 있는지

원인이나 수정 버전은 답변 전까지 단정하지 않는다.
공모전 공식 문의 창구는 [도움말·문의](https://appdevchallenge.auto-hmg.io/guide/help)의 `appdevchallenge@hyundai-partners.com`이며 SDK 담당자 연결을 요청할 수 있다. 2026-10-29 네이버 메일에서 발송 완료를 확인했다. 제목은 `[마중 / SDK 문의] NaviHelper 경유지 좌표 (0, 0) 반환 및 공식 대응 방식 확인 요청`이다. SDK 담당자 전달을 요청했으며, 제공 측 답변·수정 버전은 아직 확인되지 않았다.

## 재현 근거

- `WaypointResponseRegressionTest`, 76.231초, OK (1 test)
- [10월 29일 결과](evidence/waypoint-response-recheck-2026-10-29.json)
- [앱 처리·이전 시험](waypoint-coordinates.md)
- 관련 이슈: [#13](https://github.com/majung-team/majung-vehicle-app/issues/13), [#82](https://github.com/majung-team/majung-vehicle-app/issues/82)

운영 서버·실제 휴대전화·실차 시험은 포함하지 않는다.
