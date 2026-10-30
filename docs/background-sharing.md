
# 화면 밖 위치·ETA 공유

마중 차량 앱은 픽업 진행 중 운전자가 다른 화면으로 이동하더라도 차량 위치와 예상 도착 시간(ETA)을 계속 공유할 수 있도록 Android Foreground Service를 사용한다.

차량의 내비게이션 SDK에서 받은 위치·ETA를 서버에 전달하며, 픽업 완료·취소·만료가 확인되면 공유 서비스를 종료한다.

**화면이 보이지 않는 상태의 공유 유지와 앱 프로세스 종료 후 복구는 서로 다른 기능이다.**

- 화면 밖 공유: `PickupSharingService`
- 앱 재실행 복구: [픽업 접근 정보 저장 및 복구](pickup-recovery.md)

---

## 1. 사용자 이용 흐름

1. 정차 상태에서 내비게이션 목적지 확인 및 픽업 생성
2. 탑승자 참여 승인
3. 위치 권한 확인 및 공유 시작
4. 픽업 진행 중 Foreground Service 실행
5. 다른 앱으로 이동해도 위치·ETA 공유 유지
6. 차량 도착 안내
7. 탑승 완료·취소·만료 시 공유 종료

### 위치 공유 시작

진행 중인 픽업에 필요한 위치 권한이 없다면 차량 화면에서 **화면 밖에서도 공유 시작**을 선택해 권한을 요청한다.

위치 권한을 허용하면 앱이 보이는 상태에서 공유 서비스를 시작한다.

서비스가 실행 중이면 **마중 · 픽업 공유 중** 알림을 표시한다.

### 위치 권한 거부

위치 권한을 거부하면 차량 위치와 ETA를 전송하지 않는다.

픽업 자체는 유지하며, 운전자는 다음 중 하나를 선택할 수 있다.

- 위치 권한을 다시 허용하고 공유 시작
- 픽업 취소

### 도착 후 공유

차량이 픽업 장소에 도착했다고 해서 즉시 위치 공유가 종료되지는 않는다.

실제 탑승 완료 전까지는 해당 픽업의 공유를 유지한다.

탑승 완료·취소·만료가 확인되면 공유 서비스와 SDK 연결, 관련 알림을 종료한다.

---

## 2. Activity와 프로세스 종료 구분

| 상황 | 처리 |
| --- | --- |
| Activity가 화면에서 사라짐 | 실행 중인 Foreground Service가 공유 유지 |
| SDK 연결 종료 또는 최신 자료 중단 | 오래된 위치·ETA 삭제 요청 |
| 앱 프로세스 종료 | 자동 재실행하지 않음 |
| 사용자 강제 중지 | 자동 재실행하지 않음 |
| 앱 재실행 | 암호화된 접근 정보로 서버 상태 확인 |
| 진행 중인 픽업 확인 | **이전 픽업 계속하기** 표시 |
| 사용자 재개 선택 | 새로운 SDK 정보 수신 후 공유 재개 |
| 사용자 종료 선택 | 픽업 취소 확인 후 서버 종료 처리 |
| 이미 완료·취소·만료된 픽업 | 공유 재개 없이 접근 정보 삭제 |

### 화면 종료

`MainActivity`가 `onStop` 상태가 되어도 이미 실행 중인 `PickupSharingService`는 필요한 SDK 연결과 서버 통신을 유지한다.

Activity의 화면 갱신과 서비스의 위치 공유는 독립적으로 관리한다.

### 프로세스 종료

앱 프로세스가 종료되거나 사용자가 강제로 중지한 경우, 서비스가 자동으로 다시 실행된다고 가정하지 않는다.

프로세스 종료만으로 서버 픽업이 취소된 것으로 처리하지 않는다.

### 앱 재실행

앱을 다시 열면 암호화된 픽업 접근 정보를 확인하고 서버에서 현재 상태를 조회한다.

픽업이 진행 중이면 운전자의 재개 확인을 기다린다.

**사용자가 계속하기를 누르기 전에는 위치 공유를 재개하지 않는다.**

재개 후에도 저장된 과거 위치나 ETA를 보내지 않고 새로운 SDK 응답을 사용한다.

### 시동 OFF/ON

차량 시동 OFF 이벤트를 직접 감지하는 기능은 아직 없다.

SDK 연결이 끊어졌다는 사실만으로 시동이 꺼졌다고 판단하지 않는다.

차량 시동 OFF/ON 이후 앱의 실행 상태와 내비게이션 복구 동작은 별도 검증이 필요하다.

또한 앱 재접속 시 내비게이션 경로 안내를 자동으로 다시 시작하지 않는다.

---

## 3. 내부 실행 구조

### 주요 구성 요소

| 구성 요소 | 역할 |
| --- | --- |
| `MainActivity` | 위치 권한 요청, 공유 서비스 시작, 화면 갱신 |
| `PickupSharingService` | Foreground Service 실행, SDK 조회 및 서버 전송 |
| `PickupController` | 픽업 상태 관리, 화면·서비스 관찰자 연결 |
| `VehicleApplication` | SDK Reader 및 Controller 공유 |

### MainActivity

화면이 보이는 동안 필요한 위치 권한을 확인하고 승인된 픽업의 공유 서비스를 시작한다.

서비스 실행 후에는 화면 표시와 사용자 조작을 담당한다.

### PickupSharingService

`location` 유형의 Foreground Service로 실행한다.

주요 역할:

- 서버 픽업 상태 조회
- 내비게이션 SDK 위치·ETA 조회
- 유효한 차량 정보 전송
- 오래된 차량 정보 삭제 요청
- 완료·취소·만료 감지
- 공유 종료 시 서비스 및 알림 정리

### PickupController

Activity와 서비스의 상태 관찰자를 별도로 관리한다.

서버에서 픽업 종료 상태가 확인되면 화면이 열려 있지 않아도 공유 서비스가 종료를 감지할 수 있도록 구성한다.

### VehicleApplication

화면과 공유 서비스가 동일한 SDK Reader와 픽업 컨트롤러를 사용하도록 연결한다.

SDK 콜백에 따른 차량 정보 전송은 공유 서비스가 실행 중인 경우에만 허용한다.

---

## 4. 상태 조회와 전송 주기

| 항목 | 기준 |
| --- | --- |
| 서버 픽업 상태 조회 | 약 3초 |
| SDK 위치·ETA 조회 | 약 5초 |
| 정상 차량 정보 전송 간격 | 최소 5초 |
| SDK 자료 유효성 | 최근 10초 이내 수신 |
| 이전 자료 삭제 재시도 | 실패 시 약 3초 |

위 수치는 구현된 조회·전송 정책의 기준이다.

네트워크 지연이나 SDK 응답 시간에 따라 실제 전송 시점은 달라질 수 있다.

**정확히 5초마다 서버에 도착하는 것을 보장하는 방식은 아니다.**

### 최신 정보 전송

정차 중이라 차량 위치와 ETA 값이 동일하더라도 최근 10초 이내에 받은 SDK 자료라면 다음 순번으로 전송할 수 있다.

동일한 값이라는 이유만으로 전송을 중단하지 않는다.

### sourceTime

`sourceTime`은 SDK 자료를 실제로 수신한 시각을 사용한다.

네트워크 요청을 보낼 때 현재 시각으로 덮어쓰지 않는다.

이를 통해 서버에서 해당 정보가 언제 수집됐는지 판단할 수 있도록 한다.

### 요청 중복 실행 방지

기존 요청이 아직 처리 중이라면 새로운 전송 요청을 겹쳐 보내지 않는다.

이전 요청이 종료된 후 다음 전송을 진행한다.

네트워크가 느린 상황에서 요청이 무제한 누적되지 않도록 한다.

---

## 5. 오래된 위치·ETA 처리

차량의 위치와 ETA는 시간에 따라 유효성이 달라진다.

다음 상황에서는 기존 데이터를 최신 차량 정보처럼 다시 전송하지 않는다.

- SDK 자료가 유효 시간보다 오래된 경우
- 현재 픽업 장소와 SDK 목적지가 다른 경우
- SDK 연결이 종료된 경우
- 유효한 위치 또는 ETA를 확인할 수 없는 경우

### 자료 유효성 확인

전송 전에 SDK 자료의 수신 시각과 목적지 일치 여부를 확인한다.

유효하지 않은 자료는 정상 heartbeat로 전송하지 않는다.

### 이전 자료 삭제

최신 자료를 확인할 수 없으면 서버에 이전 위치·ETA의 삭제를 요청한다.

삭제 요청이 실패하면 약 3초 간격으로 재시도한다.

서버의 별도 만료 처리도 함께 적용된다.

### 웹 표시

탑승자 웹에서 최신 차량 정보를 받지 못한 경우 오래된 ETA를 현재 정보처럼 표시하지 않아야 한다.

웹의 **갱신 중단** 및 **마지막 갱신 시각** 표시는 웹 구현·검증과 연결된 항목으로 관리한다.

---

## 6. 경로 재요청 후 공유 복구

내비게이션 경로를 다시 요청한 뒤 완료 콜백이 전달되지 않을 수 있다.

이 경우 경로 재요청 성공 여부를 콜백 하나만으로 판단하지 않고 현재 SDK 경로와 목적지를 다시 확인한다.

기존 픽업 장소와 새 경로가 일치하는지 검증한 후 유효한 ETA를 사용한다.

경로 확인 순서와 SDK 응답 제약은 아래 문서에서 별도로 다룬다.

[경로 재요청 후 공유 복구](route-recovery.md)

---

## 7. Android 권한 및 서비스 설정

### 선언 권한

- `FOREGROUND_SERVICE`
- `FOREGROUND_SERVICE_LOCATION`
- 위치 관련 Runtime Permission

Android 14의 location Foreground Service 실행 조건에 따라 사용자가 앱을 보고 있는 상태에서 공유 서비스를 시작한다.

`ACCESS_BACKGROUND_LOCATION`은 요청하지 않는다.

### 위치 권한과 SDK 접근

Android 위치 권한을 허용했다고 해서 차량 SDK 접근 권한까지 자동으로 부여되는 것은 아니다.

실제 차량 위치와 ETA는 기존 Pleos `NAVI_ROUTE` SDK 정보를 사용한다.

위치 권한 확인과 SDK 연결 상태 확인을 구분한다.

### 알림

Foreground Service 실행 중에는 **마중 · 픽업 공유 중** 알림을 사용한다.

알림에 다음 정보를 포함하지 않는다.

- 실제 차량 위치
- 픽업 장소
- 접근 토큰
- 초대 링크 원문

Android 알림 권한이 거부된 경우 일반 알림 서랍에서 표시되지 않을 수 있지만, 이를 Foreground Service 실행 권한과 동일하게 취급하지 않는다.

### 서비스 접근 제한

`PickupSharingService`는 `exported=false`로 설정한다.

외부 애플리케이션이 해당 서비스를 임의로 실행할 수 있도록 공개하지 않는다.

### 공식 참고 자료

- [Foreground Service 유형 및 location 실행 조건](https://developer.android.com/develop/background-work/services/fgs/service-types#location)
- [Foreground Service 시작 제한](https://developer.android.com/develop/background-work/services/fgs/launch)

---

## 8. 테스트 환경

공식 Pleos 에뮬레이터와 동일 Mac에서 실행한 로컬 API를 사용했다.

실제 차량 환경의 절전 정책, 시동 OFF/ON, 공개 HTTPS 서버 및 스토어 배포 검증과는 구분한다.

### 빌드 및 정적 검사

```sh
./gradlew \
  :vehicle:testDebugUnitTest \
  :vehicle:assembleDebug \
  :vehicle:assembleDebugAndroidTest \
  :vehicle:lintDebug
```

### 시험 권한 설정

개발용 에뮬레이터에서는 테스트 실행 전에 필요한 권한을 미리 부여할 수 있다.

```sh
adb shell pm grant --user 10 \
  app.majung.vehicle \
  android.permission.ACCESS_FINE_LOCATION

adb shell pm grant --user 10 \
  app.majung.vehicle \
  android.permission.ACCESS_COARSE_LOCATION

adb shell pm grant --user 10 \
  app.majung.vehicle \
  android.permission.POST_NOTIFICATIONS
```

일반 사용자는 차량 앱의 권한 요청 화면에서 직접 허용한다.

### 계측 테스트 실행

```sh
adb shell am instrument --user 10 -w \
  -e class app.majung.vehicle.PickupSharingServiceTest \
  app.majung.vehicle.test/androidx.test.runner.AndroidJUnitRunner
```

Android 사용자 번호 `10`은 당시 시험 환경 기준이다.

여러 장치가 연결된 경우에는 `adb -s`로 대상 에뮬레이터를 지정한다.

---

## 9. 장시간 공유 시험

### 시험 목적

Activity가 화면에서 사라진 이후에도 Foreground Service가 실제 SDK 위치·ETA를 서버에 계속 전송하는지 확인한다.

### 시험 방법

1. 공식 에뮬레이터에서 픽업 시작
2. 실제 SDK 목적지·위치·ETA 수신
3. `PickupSharingService` 실행
4. Activity를 `CREATED` 상태로 이동
5. `onStop` 이후 310초 동안 공유 유지 확인
6. 서버의 freshness 및 차량 정보 순번 확인
7. 서버에서 픽업 취소
8. Activity를 다시 열지 않고 서비스 종료 및 저장 정보 삭제 확인

### 확인 결과

| 검증 항목 | 결과 |
| --- | --- |
| Activity 중단 후 공유 유지 | 310초 확인 |
| 서버 차량 정보 freshness | `FRESH` 유지 |
| 전송 순번 | 5초 간격 관측에서 61회 증가 |
| 서버 측 픽업 취소 | 서비스 종료 확인 |
| 종료 후 암호화 세션 | 삭제 확인 |

310초 시험은 **실제 Pleos SDK 위치·ETA를 사용한 에뮬레이터 시험**이다.

합성 데이터를 이용한 복구 시험과 구분한다.

---

## 10. 추가 테스트 결과

### 위치 권한 거부

위치 권한을 부여하지 않은 상태에서 활성 픽업을 확인했다.

- 픽업 상태: `ACTIVE`
- 공유 서비스: 실행되지 않음
- 차량 정보 전송 순번: `0`
- 서버 위치 좌표: 없음
- 서버 ETA: 없음

위치 권한을 받지 않은 상태에서 공유가 시작되지 않는지 확인했다.

### 기존 기능 회귀 테스트

다음 시험 결과도 확인했다.

- 기존 SDK 통합 흐름 2개 통과
- Keystore 및 픽업 복구 계측 테스트 4개 통과
- 강제 중지 전·후 두 프로세스 복구 시험 통과
- 차량 단위 테스트 8개 통과
- Debug APK 빌드 성공
- AndroidTest APK 빌드 성공
- Android Lint 통과

복구 시험 중 일부는 합성 장소·ETA를 사용한다.

실제 SDK를 이용한 장시간 공유 시험과 동일한 검증으로 취급하지 않는다.

### 복구 화면

![앱 재실행 후 이전 픽업 재개 확인](media/pickup-resume.png)

위 이미지는 합성 장소를 사용한 복구 시험 화면이다.

실제 차량에서 시동을 껐다 켠 뒤 복구한 장면은 아니다.

### 만료 상태 처리

서버가 `EXPIRED` 상태를 반환했을 때 공유 서비스를 중단하는 처리 경로는 연결했다.

다만 실제 시간이 경과하여 만료될 때까지 기다리는 장시간 시험은 이번 검증에서 수행하지 않았다.

---

## 11. 검증하지 않은 범위

현재 시험은 공식 Pleos 에뮬레이터 및 로컬 API 환경을 기준으로 한다.

다음 항목은 별도 검증이 필요하다.

- 실제 차량 시동 OFF/ON
- 실차의 절전 및 백그라운드 실행 제한
- 실제 차량의 장시간 SDK 위치·ETA 수신
- 프로세스 종료 후 플랫폼의 자동 실행 동작
- 공개 HTTPS 서버를 통한 장시간 공유
- 실제 휴대전화에서의 갱신 중단 및 복구 표시
- 스토어 배포 및 권한 심사

Activity가 화면에서 사라진 상태의 서비스 동작이 검증됐다고 해서 앱 프로세스 종료 또는 차량 재부팅 이후 자동 복구까지 검증된 것은 아니다.

---

## 12. 관련 문서

- [차량 앱 README](../README.md)
- [차량 화면 코드 구조](vehicle-screen.md)
- [픽업 접근 정보 저장 및 복구](pickup-recovery.md)
- [경로 재요청 후 공유 복구](route-recovery.md)
- [차량 화면 및 이용 흐름](service-screens.md)
- [주행 중 요약 화면](driving-summary.md)
- [전체 시스템 아키텍처](https://github.com/majung-team/majung/blob/main/docs/architecture.md)
- [개발 현황 및 PR 근거](https://github.com/majung-team/majung/blob/main/docs/development-status.md)

---

## 문서 관리 기준

이 문서는 Foreground Service를 이용한 화면 밖 위치·ETA 공유와 해당 시험 결과를 설명한다.

픽업 접근 정보 저장 및 재실행 복구는 `pickup-recovery.md`에서 별도로 관리한다.

실제 SDK 데이터 기반 장시간 시험과 합성 데이터 기반 복구 시험을 구분하고, Activity 중단·프로세스 종료·차량 시동 OFF/ON을 서로 다른 상황으로 기록한다.
