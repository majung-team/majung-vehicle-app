# majung-vehicle-app

마중 차량 앱·SDK 진단 앱 저장소다.

기존 majung 저장소의 vehicle/ 커밋 이력을 추출했다. 기존 main의 동작을 옮긴 것으로, 새 기능명세의 모든 기능을 완료한 상태는 아니다.

- 기획·진행 안내: https://github.com/majung-team/majung
- 기준 명세: https://app.notion.com/p/4026da9ae26a805abd45c0ee5885ef2f

## 실행

JDK 25, Android SDK API 34, 공식 Pleos 에뮬레이터가 필요하다.
`local.properties`에 개인 sdk.dir를 설정한 뒤 `./gradlew :vehicle:testDebugUnitTest :vehicle:assembleDebug :vehicle:lintDebug`를 실행한다.
vehicle/는 차량 앱, probe/는 SDK 진단용이다. 기본 debug API는 에뮬레이터의 10.0.2.2:8080이다. 공개 서버는 `-PpickupApi=https://majung.cloud`로 선택한다.

[차량 최초 등록과 공개 서버 연결](docs/vehicle-registration.md)에 코드 입력, 안전한 권한 저장, 오류 처리와 실제 확인 범위를 정리했다.

## 협업

`npm ci`로 커밋 검사 훅을 설치한다. 성모·승준 모두 기능별로 서버·웹·차량 변경을 함께 맡는다.
각 저장소의 PR에서 관련 저장소 PR과 기능 번호를 연결한다. 다른 저장소의 준비가 필요하면 먼저 API 요청·응답을 합의한다.

[개정 명세 대조와 작업 순서](docs/revised-plan.md)를 확인한다. 아직 구현하지 않은 기능과 현재 기준 동작을 구분해 적었다.

[차량 화면 코드 구조](docs/vehicle-screen.md)에 영역별 파일, render 연결 방식과 함께 수정할 지점을 정리했다.

[픽업 저장·재실행 복구](docs/pickup-recovery.md): 접근 정보의 암호화 저장, 서버 상태 재확인, 종료·권한 거절 시 삭제와 검증 방법.

[화면 밖 위치·ETA 공유](docs/background-sharing.md): 서비스 실행 조건, 권한, 화면 종료와 프로세스 종료의 차이, 재개 확인과 시험 방법.

[SDK 콜백 간격 측정](docs/sdk-callback-timing.md): 10분 실측, 5초 조회 응답과 경로 재요청·재연결 결과.

[경로 재요청 후 공유 복구](docs/route-recovery.md): 완료 콜백이 없을 때 경로·목적지 재확인, 취소 후 위치 숨김과 검증 범위.

[탑승자 자동 연결과 끊기](docs/auto-connect.md): 승인 없는 연결 표시, 탑승자 끊기와 새 초대, 확인 범위.

[경유지 좌표 누락 대응](docs/waypoint-coordinates.md): ID 없는 장소의 식별자 자동 발급, 픽업 경유지·ETA 연결, 재실행 복구와 에뮬레이터 시험 결과.

## 탑승자가 먼저 보내는 요청

차량 QR이나 기존 연결 없이 운전자를 검색해 장소와 함께 요청한다. 운전자는 여러 요청을 수락해 대기에 넣고 현재 픽업을 마친 뒤 차례대로 시작한다. [사용 방법](docs/vehicle-account-requests.md)과 [SDK 연동 시험](docs/queued-pickup-sdk-test.md)을 참고한다.

준비 완료·도착 [음성 안내](docs/pickup-voice.md)는 차량에서 켜고 끌 수 있다.
연결된 탑승자에게 QR 없이 보내는 [직접 초대](docs/direct-invitation.md)를 지원한다.

[주행 중 픽업 요약](docs/driving-summary.md): 시스템 제한에 따른 정차 화면 숨김, 준비 상태·ETA 표시와 실제 전환 시험.

[준비 상태 수신 확인](docs/readiness-receipt.md)

## 화면과 설치 파일

[픽업·요청·설정 화면](docs/service-screens.md) · [Release APK 서명 빌드](docs/release-signing.md)

사용할 때는 Releases의 APK를 공식 Pleos 에뮬레이터에 설치한다. 탑승자는 https://majung.cloud 에서 시작한다. 차량 최초 등록과 운전자 계정 연결은 처음 한 번 필요하다.
