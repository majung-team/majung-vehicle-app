# 주행 중 픽업 화면

기능 8.1. 차량이 화면 조작을 제한하면 픽업 진행 상태, 탑승자 준비 상태, 남은 시간만 표시한다. 정차용 화면 전체를 숨기므로 QR, 등록 코드, 설정, 요청 목록은 보이지 않는다. 이미 열린 확인창도 닫는다.

P·D나 속도로 앱이 직접 판단하지 않는다. `CarUxRestrictionsManager`의 현재 제한과 변경 알림을 사용한다. 차량 상태를 받지 못했을 때도 정차용 화면을 열지 않는다. 시스템이 전달한 글자 수·항목 수 제한이 더 작으면 표시량을 줄인다.

![주행 제한 화면](media/driving-summary-restricted.png)

위 캡처의 픽업·준비 상태·7분은 시험 데이터다. 화면 전환은 공식 Pleos 에뮬레이터의 실제 제한 상태로 확인했다.

## 표시 기준

- ACTIVE: 픽업 진행 상태와 준비 상태. 위치 공유 중이고 SDK 위치·목적지 ETA를 모두 최근 10초 안에 받았을 때만 남은 시간 표시.
- ARRIVED: 도착 안내와 준비 상태. 탑승 완료 조작은 정차 후 진행.
- 서버 연결 실패·앱 재실행 후 확인 대기: 이전 준비 상태와 ETA를 감추고 확인 안내 표시.
- 공유 중단·SDK 정보 만료: 이전 ETA 대신 공유 중단 또는 남은 시간 확인 문구 표시.
- 종료·취소·만료: 종료 안내. ETA가 0초여도 앱이 임의로 도착 처리하지 않는다.

주행 화면에는 버튼, 입력창, 스크롤, 사람 이름, 좌표가 없다. 정차가 다시 허용되면 기존 화면으로 돌아간다. 기존 위치 공유 서비스의 실행·권한 조건은 변경하지 않았다.

## 확인한 내용

2026-10-26, Pleos 2.1 에뮬레이터에서 확인했다.

- JVM 테스트 42건 통과. 정보 만료·공유 중단·연결 실패·재개 대기·종료 표시 포함.
- 화면 테스트 2건 통과. 글자 수·항목 수 제한, 오래된 ETA 숨김, 실제 제한 전환 확인.
- 기존 계정·다중 요청·음성 안내·제한 동작 회귀 검사 통과. 구버전 공식 ON/OFF 명령을 요구하는 시험 1건은 에뮬레이터 미지원으로 건너뛰고, 위 Virtual Console 전환 시험으로 별도 확인.
- 공식 Virtual Console의 P → D → P 조작에 따라 정차 화면 → 읽기 전용 화면 → 정차 화면으로 전환. 제한 중 조작 가능한 뷰가 없는지 검사.

실차 시험과 앱 마켓 심사는 아직 진행하지 않았다. Manifest의 DO 선언은 제한 중 요약 화면을 제공하기 위한 것으로, 심사 통과를 뜻하지 않는다.

### 전환 시험 재실행

공식 에뮬레이터를 P에 두고 앱과 androidTest APK를 설치한다. 아래 user 번호는 `adb shell am get-current-user`로 확인한다.

```sh
adb forward tcp:8088 tcp:8088
adb shell am instrument --user 10 -w \
  -e class app.majung.vehicle.DrivingSummaryScreenTest \
  -e drivingSummaryTransition true \
  app.majung.vehicle.test/androidx.test.runner.AndroidJUnitRunner
```

`http://127.0.0.1:8088/`의 Virtual Console에서 D로 전환한 뒤 요약 화면이 나오면 P로 돌린다. 각 단계의 대기 시간은 90초다. 이 시험은 운영 서버 대신 메모리의 시험 세션을 사용한다. 결과는 앱 내부의 `driving-summary-result.json`에 기록된다. 전환 인자가 없으면 수동 조작이 필요한 시험은 건너뛴다.

## 동료 PC에서 확인하기

Virtual Console과 마중 서버는 서로 다른 역할이다.

| 주소 | 역할 | 접속하는 사람 |
| --- | --- | --- |
| `http://127.0.0.1:8088/` | 로컬 Pleos 에뮬레이터의 기어·속도 등 시험 입력 | 해당 에뮬레이터를 실행한 PC |
| `https://majung.cloud` | 공용 마중 서버·탑승자 웹 | 운전자·탑승자·동료의 휴대전화와 PC |

동료가 자기 PC에서 `127.0.0.1`을 열면 자기 PC에 접속한다. 승준 PC의 에뮬레이터가 열리는 주소가 아니다.

1. 공식 Pleos 에뮬레이터를 설치하고 실행한다.
2. 이 PR 반영 후 main을 받아 `./gradlew :vehicle:assembleDebug -PpickupApi=https://majung.cloud`로 빌드한다. JDK·SDK 설정은 README를 따른다.
3. APK를 에뮬레이터에 설치하고 [차량 최초 등록](vehicle-registration.md)을 진행한다. 다른 사람의 차량 권한 파일을 복사하지 않는다.
4. `adb forward tcp:8088 tcp:8088` 후 로컬 콘솔을 연다. 에뮬레이터가 여러 대면 `adb -s <기기 ID>`로 대상을 지정한다.
5. 실제 연동 확인은 차량 앱에서 픽업을 만들고 휴대전화로 초대에 참여한다. 휴대전화는 공개 HTTPS 주소를 사용하므로 같은 Wi-Fi일 필요가 없다.
6. 콘솔 D/P 전환으로 주행 요약과 정차 화면 복귀를 확인한다.

위의 자동 전환 시험은 시험용 세션을 사용하므로 공용 서버의 픽업을 생성하지 않는다. 실제 차량 앱과 휴대전화의 연동 시험은 5번 흐름으로 별도 진행한다. GCP 서버에 Virtual Console을 배포할 필요는 없다.

## 함께 확인한 main

같은 날 아래 커밋을 fetch해서 대조했다. 새 머지나 배포 후에는 다시 확인해야 한다.

| 저장소 | main | 이번 변경과 맞물리는 부분 |
| --- | --- | --- |
| majung-backend | `c07462e` | 초대 첫 접속자 자동 연결, 연결 끊기, 도착 3분 전 푸시 API |
| majung-web | `3e060f6` | 이름 입력·승인 없이 연결, 연결 끊김 안내, 오래된 차량 정보 표시 |
| majung-vehicle-app | `86d72f6` | 자동 연결 표시, 탑승자 끊기, 기존 준비·도착 음성 안내 |
| majung | `62b0bab` | 직접 초대와 팀 테스트 안내 |

이번 브랜치는 차량 `86d72f6` 위에서 작업했다. 자동 연결·탑승자 끊기·음성 안내 코드를 되돌리지 않는다. 주행 중에는 연결 끊기도 숨기며, 정차 화면의 기존 동작을 유지한다.

공개 서버 health의 revision은 backend `c07462e`, 공개 `release.json`은 web `3e060f6`와 일치했다. 웹 지도·푸시 화면은 main에 아직 없고, 서버 도착 푸시도 운영 키 설정 전에는 비활성이다. 서버 코드가 올라간 것과 실제 휴대전화 알림 수신 완료는 구분한다.

## 참고

- [Pleos 주행 제한 개발 가이드](https://document.pleos.ai/en/docs/pleos-only/vehicle-app-developer-guide/driving-restriction-development-guide)
- [Pleos 차량 에뮬레이터와 Virtual Console](https://document.pleos.ai/en/docs/connect/guide/getting-started/application-development/use-vehicle-emulator)
