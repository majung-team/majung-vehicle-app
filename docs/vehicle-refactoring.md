# 차량 앱 구조 리팩터링 · 2026-11-18

## 작업 범위

`150f8ec`의 차량 앱을 기준으로 로컬 `refactor/vehicle-structure-cleanup` 브랜치에서 진행했다. 제출한 `main`, APK, 태그, 릴리스 및 서버·웹·iOS 저장소는 변경하지 않았다. 새 릴리스나 원격 푸시도 하지 않았다.

화면, Android 구성 요소, 내비 SDK 및 서버 요청에 걸친 책임을 분리했다. 새 프레임워크를 도입하거나 화면 흐름을 다시 구현하는 작업은 아니다.

## 코드를 찾는 방법

`vehicle/src/main/java/app/majung/vehicle/` 기준이다.

```text
MainActivity.kt                 화면 구성·전환, Android 생명주기 콜백
VehicleApplication.kt           구성 요소 생성과 이벤트 연결
feature/
  account/controller/           운전자 계정·사람 연결
  vehicle/controller/           차량 등록·최초 연결
  driving/                      주행 제한과 읽기 전용 픽업 요약
  pickup/
    controller/                 픽업 상태 관리·차량 정보 전송
    flow/                       요청 수락 이후 경로 준비·시작 흐름
    model/                      장소 변경·도착 판단
navi/
  connection/                   SDK 수명·콜백 세대·실패 분류
  ...                           경로·경유지 복구·위치와 ETA 검증
notification/
  PickupAlerts.kt               Android 알림 채널·버튼·종료 시 알림 정리
  PickupNotificationPolicy.kt   알림 종류별 동작
  voice/                        음성 정책·안내·Pleos TTS 출력
service/                        Foreground 공유, Application의 대기 중 조회
ui/
  sections/                     기능별 화면
  permission/                   권한 요청·최초 사용 플래그
  ...                           공통 화면 구성 요소
data/                         (아래 세 역할로 구분)
  api/                          HTTP 통신
  model/                        응답 필드 해석과 화면용 모델
  storage/                      암호화 저장소·저장 성공 후 캐시 갱신
util/                           QR·공유 안내 동의
```

`MainActivity`, `VehicleApplication`, Android Service 및 Receiver의 등록 이름은 유지했다. 기존 암호화 저장 파일도 같은 형식으로 읽는다.

## 나눈 책임

| 기존 위치 | 분리한 구성 요소 | 맡은 일 |
| --- | --- | --- |
| `VehicleApplication` | `PickupAlerts` | 메시지·장소 제안·준비 상태 알림, PendingIntent 및 알림 채널 |
| `VehicleApplication` | `BackgroundPickupPoller` | 화면이 보이지 않을 때 기존 주기로 조회, 공유 서비스가 없을 때 내비 정보 처리 |
| `VehicleApplication` | `ApplicationRequestPickupHost` | 계정·픽업·내비 컨트롤러를 독립적인 요청 준비 흐름에 연결 |
| `MainActivity` | `VehiclePermissions` | 위치·알림 권한 요청, 설정 화면 진입, 재생성 후 권한 요청 상태 복원 |
| `PickupController` | `VehicleStatePublisher` | 차량 정보 전송 순번, 전송 간격, 오래된 응답 무시, SDK 정보 무효화 전송 |
| `PickupController` | `PickupSessionPersistence` | 저장한 세션과 비교, 암호화 저장 성공 이후 캐시 변경 |
| `PickupController` | `PickupSnapshotResponse` | 응답 기본값·장소·탑승자·준비 상태 수신 순번·메시지 해석 |
| 계정 화면 | `VehicleAccountResponse`, `RideRequestResponse` | 계정과 요청을 타입이 있는 모델로 표시 |
| `DestinationReader` | `NavigationSdkConnection` | SDK 생성·초기화·12초 연결 제한·콜백 세대 검사·해제 |

요청 목록 응답은 화면에 적용하기 전에 필요한 필드를 검증한다. 형식이 잘못된 응답은 기존 오류 처리로 전달하며 UI에서 뒤늦게 JSON 변환을 시도하다 실패하지 않게 했다. 픽업 스냅샷의 필드 해석 순서와 기본값은 그대로 유지했다.

## 유지한 동작

- HTTP 경로와 필드 이름, `placeVersion`, 전송 순번 및 토큰 사용 방식
- 차량 정보 전송 전 순번을 저장하고, 이전 픽업·장소 버전의 응답을 무시하는 검사
- 차량 정보 전송 5초 간격·무효화 재전송 3초 간격과 기존 조회 주기
- SDK 연결 12초, 경유지 처리 45초, 화면 전환 중 연결 유지 유예 50초
- Android 권한 요청 코드 32·33, 권한 요청 이력 저장 키
- `pickup-alerts-v2` 채널, 알림 ID 33·34·35, 알림 버튼 및 Intent 동작
- 주행 제한, 재실행 후 공유 재개 확인, 픽업 완료·취소·접근 거절 시 정리
- 암호화 저장 파일, API 주소 선택, 앱 버전·서명·릴리스 빌드 설정

화면·Application·Service의 조회를 새로운 스케줄러 하나로 합치지는 않았다. 조회가 실행되는 조건과 기존 중복 요청 차단은 유지하고, Application의 대기 중 조회를 별도 구성 요소로 옮겼다.

## 검증

### 빌드·JVM

기존 기준: 단위 테스트 72개, 디버그 APK 및 Lint 통과.

변경 후:

- 단위 테스트 75개 통과, 실패·제외 0개
- 디버그 앱과 계측 테스트 APK 빌드 통과
- Lint 통과: 오류 0개, 경고 34개. 경고가 없는 상태로 표현하지 않는다.
- `probe` 모듈의 디버그 앱·계측 테스트 APK 빌드 통과
- Kotlin 코드는 ktfmt 0.54의 Kotlin 공식 스타일로 정렬하고 프로젝트 내부 import를 명시했다.
- 최상위 실행 데이터 제외 규칙을 `/data/`로 한정해, 소스의 `data/` 패키지가 Git에서 누락되지 않도록 했다.

세션 저장 실패 후 재시도, 삭제 실패 후 캐시 유지, 복원한 전송 순번 유지 시험을 추가했다.

### 공식 Pleos 에뮬레이터

`Majung_Pleos_2_1` · Android 14/API 34. 기존 설치본을 보존하기 위해 읽기 전용 임시 실행 환경에서 시험했다. 운영 데이터 대신 각 테스트가 제공하는 로컬 HTTP fixture를 사용했다. QR 화면 검사는 HTTPS 주소가 필요하므로 빌드 속성에 `https://example.com`을 사용했다.

관련 계측 테스트 36개 중 **35개 통과·1개 환경 조건으로 제외**, 실패 0개:

| 시험 클래스 | 개수 | 결과 |
| --- | ---: | --- |
| `NavigationConnectionTimeoutTest` | 1 | 통과 |
| `PickupRecoveryTest` | 5 | 통과 |
| `ReadinessReceiptTest` | 1 | 통과 |
| `VehicleSharingFeedbackIntegrationTest` | 1 | 통과 |
| `VehicleRegistrationTest` | 6 | 통과 |
| `VehicleEnrollmentTest` | 3 | 통과 |
| `VehicleAccountTest` | 6 | 통과 |
| `PickupVoiceGuideTest` | 4 | 통과 |
| `PickupSharingPermissionTest` | 1 | 통과 |
| `DrivingRestrictionTest` | 3 | 2개 통과·1개 제외 |
| `ApiResponseModelTest` | 4 | 통과 |
| `PickupAlertsTest` | 1 | 통과 |

`DrivingRestrictionTest.liveRestrictionOnOffChangesGate`는 `hmg_car_service`가 없는 환경이어서 제외됐다. 주차 상태의 제한 조회와 연결 해제 시 차단 시험은 통과했다. 실차 주행 확인을 대신하지 않는다.

응답 모델 시험은 이전 응답의 기본값, 준비 상태 수신 순번, 장소 제안, 메시지 시각, 필수 필드 누락을 확인한다. 알림 시험은 실제 NotificationManager에서 채널 중요도·메시지·장소 제안 버튼과 완료 후 제안 알림 제거를 확인한다. 차량 정보 통합 시험에는 실제 전송 JSON의 `placeVersion` 확인도 추가했다.

### 기존 테스트의 초기 조건 보완

초기 묶음 실행에서 실패한 항목을 리팩터링 전 `150f8ec` APK와 비교했다. QR 검사는 로컬 HTTP 주소가 아닌 HTTPS 빌드 주소에서 통과했고, 다음 세 항목은 기존 코드에서도 재현됐다.

- 음성 안내 두 시험: 기본값이 켜짐인데 꺼짐을 전제로 검사하고 있어 시험 전 설정을 명시했다.
- 차량 연결 화면: 공유 안내 동의와 탭 애니메이션 완료를 기다리지 않았고, 이미 변경된 버튼 문구를 검사하고 있어 초기 조건·대기·현재 동작 이름을 보완했다.

계측 시험은 클래스별 새 앱 프로세스에서 실행해 교체한 전역 컨트롤러와 화면 상태가 다른 클래스에 남지 않게 했다. 전체 계측 테스트나 실제 서버·실차까지 모두 검증한 결과는 아니다.
