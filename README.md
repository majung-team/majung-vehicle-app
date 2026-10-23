# majung-vehicle-app

마중 차량 앱·SDK 진단 앱 저장소다.

기존 majung 저장소의 vehicle/ 커밋 이력을 추출했다. 기존 main의 동작을 옮긴 것으로, 새 기능명세의 모든 기능을 완료한 상태는 아니다.

- 기획·진행 안내: https://github.com/majung-team/majung
- 기준 명세: https://app.notion.com/p/4026da9ae26a805abd45c0ee5885ef2f

## 실행

JDK 25, Android SDK API 34, 공식 Pleos 에뮬레이터가 필요하다.
`local.properties`에 개인 sdk.dir를 설정한 뒤 `./gradlew :vehicle:testDebugUnitTest :vehicle:assembleDebug :vehicle:lintDebug`를 실행한다.
vehicle/는 실제 앱, probe/는 SDK 진단용이다. 현재 debug API는 에뮬레이터의 10.0.2.2:8080이다.

## 협업

`npm ci`로 커밋 검사 훅을 설치한다. 성모·승준 모두 기능별로 서버·웹·차량 변경을 함께 맡는다.
각 저장소의 PR에서 관련 저장소 PR과 기능 번호를 연결한다. 다른 저장소의 준비가 필요하면 먼저 API 요청·응답을 합의한다.

[개정 명세 대조와 작업 순서](docs/revised-plan.md)를 확인한다. 아직 구현하지 않은 기능과 현재 기준 동작을 구분해 적었다.

[차량 화면 코드 구조](docs/vehicle-screen.md)에 영역별 파일, render 연결 방식과 함께 수정할 지점을 정리했다.

[픽업 저장·재실행 복구](docs/pickup-recovery.md): 접근 정보의 암호화 저장, 서버 상태 재확인, 종료·권한 거절 시 삭제와 검증 방법.
