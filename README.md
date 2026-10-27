# majung-vehicle-app

**마중(Majung) 서비스의 Pleos Android Automotive(AAOS) 전용 차량 애플리케이션 저장소입니다.**

차량 내비게이션 경로 데이터를 바탕으로 실시간 위치와 도착 예정 시간(ETA)을 전송하고, 주행 안전 상태에 맞추어 탑승자의 외출 준비 상태를 운전자에게 직관적으로 제공합니다.

- **서비스 통합 안내**: [majung 메인 저장소](https://github.com/majung-team/majung)
- **운영 서버 주소**: https://majung.cloud
- **최신 릴리즈**: [Releases 페이지에서 시험용 APK 다운로드](https://github.com/majung-team/majung-vehicle-app/releases)

[현재 차량 화면과 사용 순서](docs/service-screens.md)에서 픽업·요청·설정 화면을 볼 수 있다.

## 개발 환경 및 실행 방법

- **개발 요구사항**: JDK 25, Android SDK API 34, 공식 Pleos AVD 에뮬레이터
- **로컬 프로퍼티 설정**: `local.properties` 파일에 로컬 Android SDK 경로 설정 (`sdk.dir=...`)
- **빌드 및 유닛 테스트**:
  ```bash
  ./gradlew :vehicle:testDebugUnitTest :vehicle:assembleDebug :vehicle:lintDebug
  ```
  - `vehicle/`: Pleos 차량용 메인 앱 모듈
  - `probe/`: Pleos SDK 인터페이스 진단 모듈
- **API 서버 대상 설정**:
  - 로컬 에뮬레이터 기본 API: `10.0.2.2:8080`
  - 운영 서버 연결 빌드: `./gradlew assembleDebug -PpickupApi=https://majung.cloud`

## 핵심 기능 및 상세 문서

- **[차량 등록 및 서버 연동](docs/vehicle-registration.md)**: 차량 연결 정보 저장·권한 확인 및 통신 설정
- **[차량 화면 UI 및 아키텍처](docs/vehicle-screen.md)**: 정차/주행 상태별 렌더링 구조 및 화면 컴포넌트 설계
- **[픽업 복구 및 재실행 안정성](docs/pickup-recovery.md)**: 앱 비정상 종료 시 세션 암호화 복구 및 상태 동기화
- **[백그라운드 위치·ETA 공유](docs/background-sharing.md)**: 화면 이탈 및 백그라운드 환경에서도 포그라운드 서비스를 통한 전송 유지
- **[주행 중 안전 요약 모드](docs/driving-summary.md)**: 기어 변속(P → D단)에 따른 주행 방해 방지 및 읽기 전용 요약 화면 전환
- **[SDK 콜백 및 경로 재탐색 처리](docs/sdk-callback-timing.md)**: SDK 경로 변경 콜백 계측 및 예외 상황 복구 전략
- **[다중 탑승자 요청 관리](docs/vehicle-account-requests.md)**: 들어온 마중 요청 수락 및 순차 픽업 대기열 제어
- **[음성 안내(TTS)](docs/pickup-voice.md)**: 준비 완료 및 도착 시 차량 스피커를 통한 음성 알림 지원
- **[릴리즈 빌드 및 서명](docs/release-signing.md)**: 프로덕션용 APK 빌드 및 서명 절차 안내

## 협업 규칙

`npm ci`를 통해 커밋 검사 훅을 설치하여 코드 컨벤션을 유지합니다.  
서버 및 웹과의 연동 규격은 [개정 명세 대조 및 작업 순서](docs/revised-plan.md)를 기준으로 관리합니다.

## 처음 사용하는 운전자

차량의 **휴대전화로 시작하기 · QR 만들기**를 누르고 본인 휴대전화로 QR을 연다. 카카오 로그인 후 연결을 요청하고, 차량에서 닉네임·마중 ID를 확인하면 등록과 운전자 연결이 함께 끝난다. 관리자 등록 코드는 필요 없다. 기존 등록 차량은 다시 등록하지 않는다. [첫 연결 안내](docs/vehicle-enrollment.md)를 참고한다.
