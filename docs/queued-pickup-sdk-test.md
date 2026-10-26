# 대기 요청에서 픽업 완료까지 확인

첫 탑승자의 픽업이 진행 중인 상태에서 새 탑승자의 요청을 받는다. 차량에서 수락해 대기에 넣고, 첫 픽업을 마친 뒤 새 요청의 장소를 SDK 경유지로 추가한다. 마지막에는 준비 상태 전달, 도착, 탑승 완료와 조회 차단까지 확인한다.

로컬 Spring 서버와 공식 Pleos 에뮬레이터를 사용하는 시험이다. 인증 공급자와 계정은 가상 데이터다. 실제 카카오 로그인·휴대전화 GPS·실차 시험을 대신하지 않는다.

## 실행

백엔드 저장소에서 다음 명령을 실행하고 서버가 준비될 때까지 기다린다. 서버는 `127.0.0.1:18081`에서만 열린다. 별도 터미널에서 차량 시험을 진행한다.

```sh
MAJUNG_BROWSER_FIXTURE=true ./gradlew test \
  --tests app.majung.server.pickup.AccountBrowserFixtureTest --rerun-tasks
```

백엔드 시험이 만든 `majung-browser-vehicle.json`은 JDK 임시 폴더에 있다. macOS에서는 `/var/folders/.../T/` 아래다. 파일 경로를 아래 `--fixture`에 넣는다. 내용에는 시험용 접근 정보가 있으므로 출력하거나 공유하지 않는다.

차량 저장소에서 실행한다. JDK 25, Android SDK의 `adb`, 부팅한 Pleos 에뮬레이터가 필요하다.

```sh
./gradlew :vehicle:assembleDebug :vehicle:assembleDebugAndroidTest \
  -PpickupApi=https://majung.cloud
adb install -r vehicle/build/outputs/apk/debug/vehicle-debug.apk
adb install -r -t vehicle/build/outputs/apk/androidTest/debug/vehicle-debug-androidTest.apk
python3 scripts/prepare-queue-sdk-test.py --fixture /임시폴더/majung-browser-vehicle.json
adb shell am get-current-user
# 아래 10은 바로 위 명령에서 확인한 사용자 번호로 바꾼다.
adb shell am instrument --user 10 -w \
  -e class app.majung.vehicle.QueuedPickupSdkTest -e queueSdk true \
  app.majung.vehicle.test/androidx.test.runner.AndroidJUnitRunner
```

공개 서버용 APK를 사용하더라도 이 시험의 컨트롤러는 로컬 API와 메모리 저장소를 사용한다. 공개 서버의 차량 등록 정보나 계정 연결을 바꾸지 않는다. SDK 경로는 시험 중 변경된다.

스크립트는 기존 연결이 없는 가상 탑승자 B로 로그인하고 새 요청을 보낸다. 세션과 차량 권한은 앱 내부 임시 파일로 전달하며, 시험 시작 시 파일을 삭제한다. 시험을 다시 실행하려면 백엔드 fixture도 재시작한다.

## 확인 항목

- 첫 픽업이 진행 중이어도 새 요청 수락 가능
- 대기자의 위치 조회와 동시 픽업 시작 차단
- 최종 목적지를 유지하면서 요청 장소를 경유지로 추가
- 외부 POI ID가 없는 장소를 앱 발급 ID로 식별
- 차량에서 계속하기 전 위치 비공개
- SDK가 확인한 경유지 ETA를 탑승자 조회 API로 전달
- 탑승자의 준비 완료를 차량 컨트롤러에서 수신
- 도착·탑승 완료 후 조회 차단, 새 픽업 준비 가능

결과는 앱 내부 `files/queue-sdk-summary.json`에 참·거짓 항목으로 남는다. 계정·토큰·좌표는 기록하지 않는다.

## 확인 결과

2026년 10월 25일 Pleos 2.1 에뮬레이터에서 위 흐름을 실행했다. 1개 시험이 62.743초에 통과했다. [결과 기록](evidence/queued-pickup-sdk-2026-10-25.json)에 확인 항목을 남겼다.

탑승자의 준비 상태와 위치·ETA 조회는 가상 로그인 세션으로 API를 호출했다. 화면 버튼을 처음부터 끝까지 누른 시험이나 실제 휴대전화 시험은 아니다. 도착은 차량 컨트롤러의 도착 처리를 호출했으며, 이번 시험에서 SDK의 실제 도착 콜백을 기다리지는 않았다.

백엔드 fixture는 15분 뒤 자동 종료된다. 먼저 끝내려면 JDK 임시 폴더에 `majung-browser-fixture.stop` 파일을 만든다. `adb reverse --remove tcp:18081`로 연결도 정리한다.
