
# 마중 차량 앱 (Majung Vehicle App)

#- [기존 경유지 좌표 복구·장소 다시 선택](docs/waypoint-coordinates.md#2026-10-31-기존-경유지-보완)

## 차량 내비게이션과 탑승자를 연결하는 픽업 지원 애플리케이션

마중 차량 앱은 **Android Automotive OS(AAOS)와 Pleos Connect SDK**를 기반으로 개발한 차량용 애플리케이션이다.

운전자는 내비게이션에서 픽업 장소를 확인하고, 연결된 탑승자를 초대하거나 새로운 픽업 요청을 수락할 수 있다.

픽업이 시작되면 차량 SDK에서 수신한 위치와 예상 도착 시간(ETA)을 서버에 전달하고, 탑승자가 모바일 웹에서 변경한 준비 상태를 차량 화면에 표시한다.

주행 중에는 시스템 제한에 따라 상세 조작을 줄이고 필요한 정보만 간략하게 제공한다.

> 현재 차량 앱은 공식 Pleos Connect 에뮬레이터를 중심으로 개발·검증하고 있다. 실제 차량 환경에서의 동작 및 앱마켓 배포는 아직 검증하지 않았다.

**바로가기**

- [마중 프로젝트 소개](https://github.com/majung-team/majung)
- [전체 사용 안내 Wiki](https://github.com/majung-team/majung/wiki)
- [차량 앱 APK 0.2.15-preview 다운로드](https://github.com/majung-team/majung-vehicle-app/releases/tag/v0.2.15-preview.20261030)
- [화면 글자·버튼 가독성 변경](docs/readability-2026-10-30.md)
- [모바일 웹](https://majung.cloud/account)
- [개발 현황 및 기여 기록](https://github.com/majung-team/majung/blob/main/docs/development-status.md)

---

## 1. 주요 기능

### 차량 최초 연결

운전자 본인의 휴대전화와 차량을 QR코드로 연결한다.

1. 차량 앱에서 **휴대전화로 시작하기 · QR 만들기** 선택
2. 운전자 휴대전화로 QR코드 열기
3. 카카오 로그인 및 닉네임 설정
4. 휴대전화에서 차량 연결 요청
5. 차량에서 닉네임·마중 ID 확인 및 승인

일반적인 최초 연결 과정에서 관리자 코드는 필요하지 않다.

### 픽업 초대 및 요청

**연결된 탑승자 초대**

차량에서 연결된 사람을 선택해 픽업 초대를 보낸다. 탑승자는 모바일 웹에서 초대를 수락한다.

**QR 기반 초대**

처음 이용하는 탑승자에게 차량 QR코드를 통해 초대 링크를 전달할 수 있다.

**탑승자 요청 수락**

탑승자가 모바일 웹에서 먼저 보낸 요청은 차량의 **받은 요청**에서 확인한다.

요청을 수락한 뒤 내비게이션 경로와 장소를 확인하고 픽업을 시작한다.

### 내비게이션 연동

Pleos NaviHelper SDK를 활용해 다음 정보를 확인한다.

- 현재 내비게이션 경로
- 최종 목적지 및 경유지
- 픽업 장소
- 차량 위치
- 예상 도착 시간(ETA)

기존 최종 목적지를 유지하면서 픽업 장소를 경유지로 반영하는 흐름을 지원한다.

위치와 ETA는 SDK에서 제공하는 정보를 사용하며, 서버에서 별도의 경로 탐색이나 교통 상황 계산을 수행하지 않는다.

### 장소 변경 제안

탑승자가 모바일 웹에서 새로운 픽업 장소를 제안하면 차량에 별도 카드로 표시한다.

1. 탑승자의 장소 변경 제안 수신
2. 기존 장소와 제안 장소 비교
3. 내비게이션 SDK를 통한 경로 및 ETA 확인
4. 운전자가 **새 장소 확정** 선택
5. 변경된 장소와 버전 서버 반영

운전자가 확정하기 전까지는 기존 픽업 장소를 유지한다.

### 순차 픽업 관리

여러 탑승자의 요청을 대기열에서 관리하고, 실제 픽업은 한 번에 한 건씩 진행한다.

- 진행 중인 픽업과 대기 요청 분리
- 다음 픽업의 장소 및 경로 확인
- 중복 시작 방지
- 픽업 완료 후 다음 요청 진행
- 탑승자별 접근 권한 구분

### 준비 상태 및 음성 안내

탑승자가 웹에서 선택한 **준비 중 / 준비 완료** 상태를 차량 앱에 반영한다.

차량 앱의 상태 수신 확인은 운전자가 실제로 화면을 읽었다는 의미와 구분한다.

사용자가 음성 안내를 활성화한 경우 Pleos TTS로 준비 상태 및 도착 관련 정보를 안내할 수 있다.

음성 안내는 기본적으로 꺼져 있으며, 정차 상태에서 설정할 수 있다.

### 주행 제한

차량 시스템의 CarUxRestrictions를 사용한다.

주행 제한이 적용되면 상세 조작 대신 읽기 전용 요약 화면을 표시한다.

장소 변경, 새로운 요청 수락 및 계정 연결 등의 조작은 안전하게 정차한 상태에서 진행한다.

---

## 2. 차량 앱 설치 및 사용

### APK 다운로드

현재 안내 기준 버전:

**[v0.2.14-preview (2026.10.29)](https://github.com/majung-team/majung-vehicle-app/releases/tag/v0.2.14-preview.20261029)**

APK 파일명:

`Majung_Majung_release.apk`

새 버전은 [GitHub Releases](https://github.com/majung-team/majung-vehicle-app/releases)에서 확인할 수 있다.

### 설치 방법

1. Pleos Connect 공식 에뮬레이터 실행
2. Releases에서 서명 APK 다운로드
3. ADB로 에뮬레이터 연결 상태 확인
4. APK 설치
5. 앱 목록에서 **마중** 실행

```sh
adb devices
```

기기가 `device` 상태로 표시되면 APK를 설치한다.

```sh
adb -s emulator-5554 install -r ~/Downloads/Majung_Majung_release.apk
```

`emulator-5554`는 실제 기기 이름으로 변경한다.

기존 개발용 APK와 서명이 다른 경우에는 덮어쓰기가 실패할 수 있다. 앱을 삭제하기 전에 진행 중인 픽업과 차량 재등록 필요 여부를 확인한다.

### 처음 사용하기

1. 차량 앱 실행
2. 위치 공유 안내 확인 및 동의
3. QR코드를 통한 운전자 계정 연결
4. 내비게이션에서 경로 안내 시작
5. 마중 **픽업** 화면에서 장소 확인
6. 연결된 사람 초대 또는 받은 요청 수락
7. 경로 검증 후 픽업 시작

탑승자는 별도 앱을 설치하지 않고 [모바일 웹](https://majung.cloud/account)을 사용한다.

[차량 앱 설치와 최초 설정 상세 안내](https://github.com/majung-team/majung/wiki/차량-앱-설치)

---

## 3. 개발 환경

| 항목 | 기술 및 환경 |
| --- | --- |
| 개발 언어 | Kotlin |
| 플랫폼 | Android Automotive OS |
| 차량 환경 | Pleos Connect 공식 에뮬레이터 |
| Android SDK | API 34 |
| JDK | 25 |
| 빌드 도구 | Gradle |
| 내비게이션 | NaviHelper 3.0.5 |
| 음성 안내 | Pleos TextToSpeech 3.3.1 |
| QR 처리 | ZXing |
| 커밋 규칙 도구 | Node.js 22+, Husky, commitlint |

`vehicle/`은 실제 차량 애플리케이션 모듈이고, `probe/`는 SDK 동작을 확인하기 위한 진단 모듈이다.

---

## 4. 직접 빌드 및 실행

### 저장소 준비

```sh
git clone https://github.com/majung-team/majung-vehicle-app.git
cd majung-vehicle-app
```

커밋 메시지 검사 도구를 설치한다.

```sh
npm ci
```

`npm ci`는 Husky와 commitlint 등 저장소 개발 도구를 설치하기 위한 명령이다.

차량 애플리케이션 자체는 Gradle로 빌드한다.

### Android SDK 설정

프로젝트 루트의 `local.properties`에 SDK 경로를 지정한다.

```properties
sdk.dir=/사용자/환경에/맞는/Android/sdk
```

### 테스트 및 빌드

공개 서버를 대상으로 빌드할 때는 `pickupApi`를 명시한다.

```sh
./gradlew \
  :vehicle:testDebugUnitTest \
  :vehicle:lintDebug \
  :vehicle:assembleDebug \
  -PpickupApi=https://majung.cloud
```

각 작업의 역할:

| 작업 | 내용 |
| --- | --- |
| `testDebugUnitTest` | 차량 앱 단위 테스트 |
| `lintDebug` | Android Lint 검사 |
| `assembleDebug` | Debug APK 생성 |

생성 경로:

```text
vehicle/build/outputs/apk/debug/vehicle-debug.apk
```

### APK 설치

```sh
adb devices
```

```sh
adb -s emulator-5554 install -r \
  vehicle/build/outputs/apk/debug/vehicle-debug.apk
```

### 앱 실행

```sh
adb -s emulator-5554 shell am start \
  --user current \
  -n app.majung.vehicle/.MainActivity
```

기기 이름과 Android 사용자는 자신의 에뮬레이터 환경에 맞게 확인한다.

### API 주소

| 빌드 | 연결 주소 |
| --- | --- |
| 기본 Debug | `http://10.0.2.2:8080` |
| 공개 서버 Debug | `https://majung.cloud` |
| Release | 명시한 HTTPS API 주소 |

`10.0.2.2`는 Android 에뮬레이터에서 개발 PC의 로컬 서버에 접근하기 위한 주소다.

공개 서버에서 시험할 때는 다음 옵션을 생략하지 않는다.

```sh
-PpickupApi=https://majung.cloud
```

### ADB 연결 문제

`No connected devices` 또는 `OFFLINE`이 표시되면 빌드 오류와 구분한다.

- 에뮬레이터 실행 여부 확인
- `adb devices`에서 기기 상태 확인
- 여러 장치가 있다면 `-s`로 대상 지정
- 이미 실행된 AVD 중복 실행 방지

기기 상태가 `device`로 표시된 뒤 설치한다.

---

## 5. 내부 동작 구조

차량 앱은 화면, SDK 조회, 픽업 상태 관리, 서버 통신 및 복구 처리를 분리해 구성했다.

### 주요 구성 요소

| 구성 요소 | 역할 |
| --- | --- |
| `MainActivity` | 화면 생명주기 및 사용자 조작 |
| `VehicleAccountController` | 차량 계정 연결 및 사람 관리 |
| `RequestPickupFlow` | 요청 장소 경로 준비·검증 |
| `DestinationReader` | SDK 연결 및 내비게이션 정보 조회 |
| `VehicleTelemetry` | 위치·ETA 유효성 및 목적지 일치 확인 |
| `PickupController` | 픽업 상태·권한·전송 순번 관리 |
| `PickupSharingService` | 백그라운드 위치·ETA 공유 |
| `PickupApiClient` | 서버 HTTPS API 통신 |
| `EncryptedPickupSessionStore` | 암호화된 픽업 복구 정보 저장 |

### 백그라운드 공유

화면이 내려간 상태에서도 진행 중인 픽업의 위치 및 ETA 공유를 유지할 수 있도록 `PickupSharingService`를 사용한다.

픽업이 완료되거나 취소되면 공유 서비스를 종료한다.

### 프로세스 종료 후 복구

차량 앱 프로세스가 종료된 경우에는 앱을 다시 실행한 뒤 서버 상태와 저장된 접근 권한을 확인한다.

진행 중인 픽업이 확인되면 운전자가 재개 또는 취소를 선택한다.

사용자 확인 없이 이전 픽업의 위치 공유를 자동으로 재개하지 않는다.

복구에 필요한 접근 정보는 Android Keystore 기반 암호화 저장소에서 관리한다.

차량 시동 OFF/ON 이후 자동 실행이나 내비게이션 자동 재시작은 별도 검증 범위다.

---

## 6. 현재 구현 및 검증 범위

**기준일: 2026년 10월 30일**

### 구현된 주요 기능

- 차량 최초 QR 등록 및 운전자 계정 연결
- 연결된 사람 직접 초대
- QR 기반 초대 링크 전달
- 탑승자의 픽업 요청 수락
- 여러 요청의 순차 처리
- SDK 경로·위치·ETA 확인
- 장소 변경 제안 및 경로 검증
- 준비 상태 수신
- 주행 제한에 따른 화면 전환
- 선택적 음성 안내
- 백그라운드 공유
- 픽업 완료·취소 및 복구 처리

### 수행한 검증

- 공식 Pleos 에뮬레이터 기반 주요 기능 확인
- SDK 경로·ETA 및 연결 시험
- 로컬 API 기반 픽업 흐름 시험
- 차량 상태 및 화면 관련 자동화 테스트
- 백그라운드 공유와 재실행 복구 관련 시험

### 추가 검증이 필요한 항목

- 실제 휴대전화와 차량 앱 간 전체 픽업 흐름
- 실제 차량 환경에서의 동작
- 차량 시동 OFF/ON 및 장시간 실행
- 실제 스피커 음성 출력
- 통화와 TTS 안내 우선순위
- 실차 주행 제한 상태
- 앱마켓 배포 및 심사

### 알려진 이슈

**내비게이션 취소 후 상태 유지**

공식 에뮬레이터에서 `cancelRoute` 호출 후 일정 시간 `ROUTE_GUIDING` 상태가 유지되는 현상이 확인됐다.

[GitHub Issue #56](https://github.com/majung-team/majung-vehicle-app/issues/56)

이 문제는 SDK 경로 취소 동작의 시험 결과다.

마중의 픽업 완료·취소는 서버 위치 공유를 종료하며, 이 과정에서 내비게이션 안내를 취소하는 명령을 호출하지 않는다.

현재 정확한 원인은 확인되지 않았으며 추가 검증이 필요하다.

---

## 7. 기술 문서

### 사용자 화면 및 기능

| 문서 | 내용 |
| --- | --- |
| [화면과 사용 순서](docs/service-screens.md) | 픽업, 받은 요청, 설정 화면 |
| [QR 최초 연결](docs/vehicle-enrollment.md) | 차량 등록 및 운전자 계정 연결 |
| [요청 및 다음 픽업](docs/request-pickup-flow.md) | 경로 검증, 명시적 시작, 대기열 및 복구 |
| [계정 및 요청](docs/vehicle-account-requests.md) | 운전자와 탑승자 연결 및 요청 |
| [음성 안내](docs/pickup-voice.md) | Pleos TTS 설정 및 동작 |

### 내부 구조 및 SDK

| 문서 | 내용 |
| --- | --- |
| [차량 화면 구조](docs/vehicle-screen.md) | MainActivity 및 화면 영역 |
| [저장 및 복구](docs/pickup-recovery.md) | Keystore 암호화, 서버 확인 및 재개 |
| [백그라운드 공유](docs/background-sharing.md) | 공유 서비스 및 종료 감지 |
| [주행 중 요약 화면](docs/driving-summary.md) | 시스템 제한 및 읽기 전용 UI |
| [SDK 콜백 측정](docs/sdk-callback-timing.md) | 에뮬레이터 측정 및 제약 사항 |
| [팝업·새로고침 개선](docs/dialog-refresh-2026-10-28.md) | 지연 응답, 중복 조회 및 UI 개선 |
| [Release 서명](docs/release-signing.md) | 서명 설정 및 APK 빌드 |

### 프로젝트 공통 문서

- [전체 아키텍처](https://github.com/majung-team/majung/blob/main/docs/architecture.md)
- [개발 현황 및 PR 근거](https://github.com/majung-team/majung/blob/main/docs/development-status.md)
- [시연 영상](https://github.com/majung-team/majung/blob/main/docs/pickup-demo.md)
- [팀 테스트 안내](https://github.com/majung-team/majung/blob/main/docs/team-testing.md)
- [개발 계획](docs/revised-plan.md)
- [기여 가이드](CONTRIBUTING.md)

---

## 8. 관련 저장소

| 저장소 | 역할 |
| --- | --- |
| [majung](https://github.com/majung-team/majung) | 프로젝트 소개, 설계 및 시연 |
| [majung-backend](https://github.com/majung-team/majung-backend) | Spring Boot API, 픽업 및 계정 관리 |
| [majung-web](https://github.com/majung-team/majung-web) | 탑승자 모바일 웹 |
| [majung-vehicle-app](https://github.com/majung-team/majung-vehicle-app) | 차량 앱 및 Pleos SDK 연동 |

---

## 참고 사항

마중 차량 앱은 공식 Pleos 에뮬레이터를 기반으로 개발한 프로젝트다.

현재 주요 기능 구현 및 에뮬레이터 검증 결과를 제공하지만, 실제 차량에서의 동작과 상용 배포 가능 여부는 별도 확인이 필요하다.

기능별 구현 이력과 검증 범위는 GitHub PR 및 개발 문서에서 확인할 수 있다.
