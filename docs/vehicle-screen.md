# 차량 화면 코드 구조

MainActivity는 화면 생명주기와 주기 갱신을 연결하고, 화면의 각 영역은 별도 클래스에서 만든다.
이 변경은 기존 개발 화면을 나눈 것이다. 자동 연결·QR·주행 중 최소 표시·백그라운드 전송을 구현한 것은 아니다.

| 파일 | 담당 |
| --- | --- |
| `MainActivity.kt` | 루트 레이아웃, 영역 배치, onStart/onStop, 1초 화면 갱신·3초 픽업 조회·5초 SDK 조회 연결 |
| `PickupCreationSection.kt` | 내비 연결·목적지 확인·픽업 생성 버튼과 사용 가능 여부 |
| `PickupParticipantSection.kt` | 초대 링크 복사·참여 승인 확인 창·서버 상태 다시 확인 |
| `PickupStatusSection.kt` | 연결 상태, 픽업 진행·장소·준비 상태·차량 정보·안내 문구 |
| `PickupCompletionSection.kt` | 도착·탑승 완료·취소 확인 창, 다음 픽업 준비 |
| `VehicleViews.kt` | 공통 라벨·버튼 스타일, 버튼 동작 후 render 호출, 내용이 바뀔 때만 TextView 갱신 |

## 영역 연결 방법

네 영역은 `VehicleApplication`과 `VehicleViews`를 받아 생성한다. 생성자의 init에서 해당 영역의 뷰를 기존 순서로 추가한다.
각 영역의 `render()`는 앱의 현재 상태를 읽어 문구·버튼 활성화·표시 여부를 갱신한다.
`MainActivity.render()`는 네 영역의 render를 호출한다. 서버 요청과 픽업 상태 전이는 기존 `PickupController`가 담당한다.
각 영역이 별도 타이머나 API 클라이언트를 만들지 않는다.

`VehicleViews.button()`으로 만든 버튼은 동작 실행 뒤 MainActivity의 render를 호출한다.
비동기 요청 완료 시에는 기존 `pickup.changed` 콜백으로 다시 렌더링한다.
Activity가 멈추면 콜백과 타이머를 해제하고 SDK 연결을 끊는다. 화면 밖 전송은 후속 Foreground Service 작업에서 바꾼다.

## 함께 수정할 때

- 성모의 자동 연결·끊기·QR: 참여자 영역의 버튼·확인 창을 수정한다. 자동 연결 안내 문구는 상태 표시 영역도 함께 맞춘다.
- 승준의 주행 중 최소 표시: 각 영역의 render에 주행 상태를 전달할 경계를 정한 뒤 조작 제한을 연결한다.
- 승준의 토큰 복구·백그라운드: 상태 저장과 서비스 실행은 이 뷰 클래스 안에 넣지 않는다. 앱·컨트롤러·서비스에서 관리하고 화면은 결과를 표시한다.
- 도착 이벤트 연결: SDK 이벤트는 컨트롤러로 전달한다. 완료 영역의 수동 도착·탑승 확인과 같은 상태 규칙을 사용한다.

화면 영역은 같은 루트 LinearLayout에 기존 순서로 붙인다. 레이아웃을 감싸는 컨테이너를 추가하지 않아 여백·버튼 위치를 유지한다.
기존 `connection_status`, `pickup_details`, `arrive_pickup`, `complete_pickup`, `cancel_pickup` ID도 유지한다.

## 확인 결과

- 차량 단위 시험 8개, debug APK·androidTest APK 빌드, lint 통과.
- 공식 에뮬레이터의 기존 통합 시험 2개 통과: 목적지 생성·참여 승인·SDK 위치/ETA 전달·해제/재연결·준비 상태·취소, 도착·탑승 완료·종료 후 접근 차단.
- 반복 조회 중 본문 높이·버튼 위치·취소 가능 상태가 유지되는 기존 검사도 통과.

시험 환경은 같은 Mac의 Pleos 에뮬레이터와 로컬 API다. 실차·실제 휴대전화·주행 중 UI 허용 여부를 검증한 결과는 아니다.
