# 차량 화면 코드 구조

MainActivity는 화면 생명주기와 주기 갱신을 연결하고, 화면의 각 영역은 별도 클래스에서 만든다.
화면 영역 분리 후 픽업 접근 정보 저장·재실행 복구를 연결했다. [복구 동작](pickup-recovery.md)을 함께 본다. [화면 밖 공유](background-sharing.md)를 연결했다. 차량 등록과 HTTPS 공유 QR을 연결했다. 정차 화면은 왼쪽 메뉴와 스크롤 본문으로 나뉜다. 주행 제한 또는 차량 상태 미확인 때는 메뉴와 본문을 함께 숨기고 읽기 전용 요약만 표시한다. [현재 화면](service-screens.md)을 함께 본다.

| 파일 | 담당 |
| --- | --- |
| `MainActivity.kt` | 왼쪽 메뉴·본문 배치, 상태별 화면 전환, onStart/onStop, 1초 화면 갱신, 서비스 미실행 시 3초 픽업 조회, 위치 권한·재개 확인 |
| `PickupSharingService.kt` | 화면 밖 SDK 연결·5초 조회·전송, 서버 상태 조회와 종료 감지 |
| `PickupCreationSection.kt` | 내비 연결·최근 장소 선택·경유지 추가·픽업 생성 버튼과 사용 가능 여부 |
| `VehicleRegistrationSection.kt` | 앱의 차량 연결 상태와 재확인 |
| `VehicleEnrollmentSection.kt` | 첫 연결 QR·휴대전화 요청·차량에서 본인 확인 |
| `VehicleAccountSection.kt` | 운전자 계정·받은 요청·연결된 사람 선택 |
| `VehicleColumns.kt` | 본문 폭에 따라 장소/초대, QR/안내를 가로 또는 세로로 배치 |
| `PickupParticipantSection.kt` | 초대 링크 복사·HTTPS 공유 QR·참여 승인 확인 창·서버 상태 다시 확인 |
| `PickupStatusSection.kt` | 연결 상태, 픽업 진행·장소·준비 상태·차량 정보·안내 문구 |
| `PickupCompletionSection.kt` | 도착·탑승 완료·취소 확인 창, 다음 픽업 준비 |
| `VehicleViews.kt` | 공통 라벨·버튼 스타일, 버튼 동작 후 render 호출, 내용이 바뀔 때만 TextView 갱신 |

## 영역 연결 방법

각 영역은 `VehicleApplication`과 `VehicleViews`를 받아 생성한다. 생성자의 init에서 해당 영역의 뷰를 기존 순서로 추가한다.
각 영역의 `render()`는 앱의 현재 상태를 읽어 문구·버튼 활성화·표시 여부를 갱신한다.
`MainActivity.render()`는 각 영역의 render를 호출한다. 서버 요청과 픽업 상태 전이는 기존 `PickupController`가 담당한다.
각 영역이 별도 타이머나 API 클라이언트를 만들지 않는다.

`VehicleViews.button()`으로 만든 버튼은 동작 실행 뒤 MainActivity의 render를 호출한다.
비동기 요청 완료 시에는 기존 `pickup.changed` 콜백으로 다시 렌더링한다.
Activity가 멈추면 화면 콜백과 타이머만 해제한다. 공유 서비스가 실행 중이거나 시작 요청 중이면 SDK 연결을 유지한다. 그 외에는 연결을 끊는다.

## 화면을 수정할 때

- 픽업·받은 요청·설정은 왼쪽 메뉴로 전환한다. 메뉴를 바꿨을 때만 본문을 맨 위로 이동한다. 주기 갱신으로 스크롤 위치를 바꾸지 않는다.
- `VehicleViews.section()`은 흰색 카드와 여백을 만들고 `button(primary = true)`는 화면의 주요 동작을 강조한다. 버튼은 최소 높이 72dp이며 글씨가 커지면 높이가 늘어난다.
- `VehicleColumns`의 실제 가용 폭이 960dp 이상이면 두 영역을 나란히 놓는다. 그보다 좁으면 위아래로 놓고 본문을 스크롤한다.
- 주행 제한은 `parkedRoot` 전체에 적용한다. 메뉴를 새로 추가해도 이 컨테이너 밖에 조작 버튼을 두지 않는다. 열린 창도 함께 닫는다.
- 장소 검증·권한 확인·API 호출은 기존 컨트롤러에서 처리한다. 화면을 빨리 표시하려고 검증 전 초대 버튼을 활성화하지 않는다.
- `parked_controls`, `pickup_invite_contact`, `create_pickup`, `arrive_pickup`, `complete_pickup` 등 동작 ID는 유지한다.

## 화면 영역 분리 당시 확인 결과

- 차량 단위 시험 8개, debug APK·androidTest APK 빌드, lint 통과.
- 공식 에뮬레이터의 기존 통합 시험 2개 통과: 목적지 생성·참여 승인·SDK 위치/ETA 전달·해제/재연결·준비 상태·취소, 도착·탑승 완료·종료 후 접근 차단.
- 반복 조회 중 본문 높이·버튼 위치·취소 가능 상태가 유지되는 기존 검사도 통과.

시험 환경은 같은 Mac의 Pleos 에뮬레이터와 로컬 API다. 실차·실제 휴대전화·주행 중 UI 허용 여부를 검증한 결과는 아니다.

## 픽업 경유지 확인

`DestinationReader`가 SDK 응답을 받아 아래 코드로 장소와 ETA를 확인한다.
화면은 결과를 표시하고, `PickupController`는 확인된 장소로만 픽업을 생성한다.

| 파일 | 담당 |
| --- | --- |
| `SdkPickupPlace.kt` | 선택 장소의 좌표·ID 보관, ID 없는 장소는 마중 식별자 발급, 응답 ID 대조 후 누락 좌표 연결 |
| `NavigationStops.kt` | 최종 목적지·경유지 구분, 좌표로 ETA 조회, 기존 경로 유지 확인 |
| `WaypointInsertion.kt` | 요청한 경유지 하나가 추가되고 기존 목적지·경유지가 유지됐는지 확인 |
| `RouteValidation.kt` | 두 차례 경로 확인, 응답 대기와 확인 간격 관리 |
| `NavigationFailure.kt`, `NavigationRequest.kt` | 오류별 화면 안내와 SDK 요청 예외 구분 |
| `VehicleTelemetry.kt` | 확정된 픽업 장소의 ETA와 최신 위치가 함께 있을 때만 전송할 값 생성 |

SDK가 반환한 좌표를 수정한 것으로 표현하지 않는다.
장소 ID로 연결할 수 있는 범위와 시험 결과는 [경유지 좌표 누락 대응](waypoint-coordinates.md)에 정리했다.
