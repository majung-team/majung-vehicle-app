# majung-vehicle-app

공식 Pleos 에뮬레이터에서 실행하는 마중 차량 앱이다.
내비에서 만날 장소를 확인하고 사람을 초대하거나 받은 요청을 수락한다.
이동 중에는 위치·ETA를 서버에 전송하고 탑승자의 준비 상태를 보여준다.

[전체 사용 안내 Wiki](https://github.com/majung-team/majung/wiki) · [실제 PR·현재 담당](https://github.com/majung-team/majung/blob/main/docs/development-status.md)

## 바로 시험하기

1. [서명 APK 0.2.12-preview](https://github.com/majung-team/majung-vehicle-app/releases/tag/v0.2.12-preview.20261029)를 공식 Pleos 에뮬레이터에 설치한다.
2. 앱 목록의 **마중**을 연다. **마중 SDK 확인**은 진단용이며 일반 이용에 필요 없다.
3. 처음 한 번 **휴대전화로 시작하기 · QR 만들기** → 운전자 본인 카카오 로그인 → 차량에서 계정 확인 순서로 연결한다. 관리자 코드는 필요 없다.
4. 내비에서 경로 안내를 시작한 뒤 **픽업**으로 돌아온다. 장소 확인이 끝나면 **연결된 사람 선택** 또는 **QR로 초대 링크 공유**를 고른다.
5. 탑승자가 먼저 보낸 요청은 **받은 요청**에서 확인한다. 경로 준비·검증 후 시작하며 여러 요청은 대기열에 둔다.

운영 API는 `https://majung.cloud`다. 탑승자는 휴대전화의 [내 픽업](https://majung.cloud/account)을 사용한다.
화면을 내리면 공유 서비스가 전송을 이어가지만 프로세스 종료 뒤 재개는 서버 상태 확인과 사용자 확인이 필요하다.

## 직접 빌드·설치

JDK 25, Android SDK API 34, 공식 Pleos AVD와 `local.properties`의 `sdk.dir`을 준비한다.

```sh
npm ci
./gradlew :vehicle:testDebugUnitTest :vehicle:lintDebug :vehicle:assembleDebug -PpickupApi=https://majung.cloud
adb devices
adb -s 장치이름 install -r vehicle/build/outputs/apk/debug/vehicle-debug.apk
adb -s 장치이름 shell am start --user current -n app.majung.vehicle/.MainActivity
```

`장치이름`은 `adb devices`에 나온 값으로 바꾼다. `device` 상태일 때 설치하며 이미 실행한 AVD를 다시 켤 필요는 없다.
`OFFLINE`·장치 없음은 빌드 오류와 구분한다. 공개 서버를 시험할 때 `-PpickupApi=https://majung.cloud`를 빼지 않는다.
기본 debug API는 `10.0.2.2:8080`이다. `vehicle/`가 실제 앱, `probe/`가 SDK 진단 모듈이다.

## 작동 원리·시험 기록

| 문서 | 내용 |
| --- | --- |
| [화면과 사용 순서](docs/service-screens.md) | 픽업·받은 요청·설정 |
| [QR 최초 연결](docs/vehicle-enrollment.md) | 등록·운전자 계정 연결 |
| [요청에서 시작·다음 픽업](docs/request-pickup-flow.md) | 경로 준비·명시적 시작·대기열·복구 |
| [화면 구조](docs/vehicle-screen.md) | MainActivity와 영역별 화면 |
| [저장·복구](docs/pickup-recovery.md) | Keystore 암호화·서버 상태 확인·재개 |
| [백그라운드 공유](docs/background-sharing.md) | 공유 중 서비스·종료 감지 |
| [주행 요약](docs/driving-summary.md) | 시스템 제한에 따른 읽기 전용 표시 |
| [SDK 콜백 측정](docs/sdk-callback-timing.md) | 에뮬레이터 측정 결과와 제약 |
| [계정·요청](docs/vehicle-account-requests.md) | 운전자·여러 탑승자 요청 |
| [음성 안내](docs/pickup-voice.md) | 기본 꺼짐, 정차 중 설정 |
| [팝업·새로고침 수정](docs/dialog-refresh-2026-10-28.md) | 버튼 잘림·지연 응답·중복 조회 시험 |
| [release 서명](docs/release-signing.md) | 서명 키 관리·APK 빌드 |

실제 휴대전화 전체 흐름·실차·장시간 실행·음성 청취는 별도 확인이 필요하다.
SDK 내비 취소 후 안내 유지 [이슈 #56](https://github.com/majung-team/majung-vehicle-app/issues/56)은 미해결이다.
[현재 개발 기준](docs/revised-plan.md) · [기여 규칙](CONTRIBUTING.md)
