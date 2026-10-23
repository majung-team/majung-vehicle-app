# 경로 재요청 후 위치 공유 복구

## 왜 바꿨나

10분 SDK 측정에서 경로를 다시 요청한 뒤 `onRouteStarted`는 왔지만 `onRoutingCompleted`와 `onReRouteEnded`는 오지 않았다. 기존 코드는 완료 콜백이 와야 `routing`을 해제했다. 그동안 목적지·위치 조회도 막혀 서버가 계속 `WAITING`으로 남았다.

완료 이벤트를 기다리는 대신 현재 경로와 목적지를 다시 조회한다. 기다린 시간이 길어졌다는 이유로 이전 위치나 예상 시간을 되살리지는 않는다.

## 복구 순서

1. 경로 시작·목적지 변경·취소·SDK 오류가 오면 선택 장소와 위치·예상 시간을 버린다.
2. 5초 간격으로 `getRouteStateInfo()`를 요청한다. 해당 요청을 기다리는 동안 중복 조회는 보내지 않는다.
3. 안내 모드인지 확인한 뒤 `getDestinationInfo()`를 요청한다.
4. 같은 목적지를 최소 5초 간격으로 두 번 확인해야 새 위치 조회를 시작한다. 조회 응답은 각 단계에서 4초 이내에 받아야 한다. 실패·응답 지연·연결 해제는 앞서 확인한 결과도 무효로 만든다.
5. 이후에는 공유 서비스의 주기 조회마다 경로 상태 → 목적지 → 현재 위치 순서로 갱신한다. 상태 재확인이 끝난 뒤에도 픽업의 확정 장소와 SDK 목적지가 다르면 `VehicleTelemetry`가 전송을 막는다.

첫 연결도 같은 확인을 거친다. 따라서 목적지가 화면에 보이기까지 기존보다 시간이 더 걸릴 수 있다. 픽업 생성 전에는 확인이 끝나면 자체 재시도를 멈춘다. 공유 중에는 기존 서비스가 5초마다 조회한다. 화면을 닫고 공유 서비스도 실행 중이 아니면 SDK 연결과 재시도를 정리한다.

`RouteValidation.kt`는 확인 순서·응답 기한·같은 장소의 반복 확인을 담당한다. `DestinationReader.kt`는 SDK 조회와 콜백을 연결한다. 서버 전송 주기와 픽업 상태 전이는 바꾸지 않았다.

## SDK 값에서 주의한 점

NaviHelper 3.0.5 소스의 `isRouting` 설명은 활성 경로 여부를 가리킨다. 그러나 공식 Pleos 2.1 에뮬레이터에서는 `ROUTE_GUIDING` 중에도 `false`였다. 이번 변경은 이 값을 단독 완료 신호로 사용하지 않는다. 확인한 조합인 `termsAgreement=true`, `isRouting=false`, `ROUTE_GUIDING` 또는 `SIMULATION_GUIDING`일 때만 다음 확인으로 넘어간다. `true`를 받으면 검증되지 않은 상태로 보고 공유를 열지 않는다.

이는 모든 차량 내비게이션에 대한 보장이 아니다. 다른 SDK·내비게이션 구현에서는 같은 조합이 제공되는지 확인해야 한다. SDK 응답에는 앱 요청을 구분할 ID나 경로 버전이 없으므로, 두 번의 조회도 공급자가 내부적으로 보관한 값을 반환하지 않았다는 증명은 아니다. GPS 취득 시각을 측정한 것도 아니다.

공식 문서:
- [NaviHelper 이벤트](https://document.pleos.ai/en/api-reference/connect-sdk-pleos/NaviHelper/event-listeners)
- [getRouteStateInfo](https://document.pleos.ai/api-reference/connect-sdk-pleos/NaviHelper/navi-helper/ai.pleos.playground.navi.helper/navi-helper/get-route-state-info)

## 검증

단위 시험은 완료 콜백 없는 복구, 시간 경과만으로 해제되지 않는 동작, 요청하지 않은 응답·응답 지연·오류 후 기존 확인 결과 폐기, 목적지 변경, 중복 조회를 확인한다. 기존 `VehicleTelemetryTest`는 확정 장소와 다른 목적지의 예상 시간 전송 차단과 새 위치만으로 오래된 예상 시간을 갱신하지 않는 동작을 확인한다.

실제 SDK·로컬 API 시험은 `RouteRecoveryTest`에 있다. 공식 SDK 예제 장소를 설정하고 참여·승인한 뒤 화면을 내린 상태에서 재탐색, 취소 후 위치 숨김, 새 경로, SDK 재연결을 확인한다. 실행 결과는 `route-recovery-summary.json`에 콜백 개수와 소요 시간만 기록한다. 토큰·좌표·픽업 ID는 기록하지 않는다.

### 2026-10-23 실행 결과

공식 Pleos 2.1 ARM64 에뮬레이터·NaviHelper 3.0.5·로컬 API에서 전체 시나리오 1개가 93.616초에 통과했다. [기록 JSON](evidence/route-recovery-2026-10-23.json)에는 시간과 콜백 개수만 남겼다.

| 확인 | 결과 |
| --- | --- |
| 화면 밖 경로 재요청 | 서버가 이전 자료를 숨긴 것을 확인한 뒤 20.100초 후 `FRESH` 복구 |
| 완료 콜백 | `onRoutingCompleted` 0회, `onReRouteEnded` 0회 |
| 경로 취소 | 서버 위치가 숨겨진 뒤 12초 동안 계속 `WAITING`·좌표 없음 |
| 새 경로 요청 | 요청부터 17.075초 후 `FRESH` |
| SDK 연결 해제·재연결 | 서버의 숨김 확인 뒤 15.066초 후 `FRESH` |

각 시간은 시험 코드의 해당 단계 시작부터 서버 응답을 확인할 때까지다. 1초 간격으로 확인했으므로 정확한 SDK 처리 시간이나 보장 시간으로 해석하지 않는다. 초기 시험에는 경로 준비 단계 실패와 재연결 확인 중 HTTP 요청 거절이 있었다. 거절된 응답의 상태 코드는 당시 기록하지 않아 원인을 확정하지 않는다. 시험이 직접 경로를 준비하도록 바꾸고, 서버를 지나치게 조회하지 않도록 간격을 1초로 늘린 뒤 전체를 다시 실행했다.

기존 픽업 생성·참여 승인·공유·재연결·도착·탑승 완료 시험 2개도 47.286초에 통과했다. 재연결 확인은 화면의 순간적인 전송 문구 대신 실제 서버의 `FRESH` 응답과 증가한 순번으로 판단하도록 바꿨다. 시험 조회 간격도 1초로 맞췄다.

단위 시험은 기존 8개와 새 확인 로직 5개, 총 13개가 통과했다. Debug 앱·계측 APK 빌드와 Android lint도 통과했다. lint에는 기존 SDK·시험 도구 버전 경고 4개가 남아 있다.

```sh
./gradlew :vehicle:testDebugUnitTest :vehicle:assembleDebug :vehicle:assembleDebugAndroidTest :vehicle:lintDebug
adb shell am instrument --user 10 -w \
  -e verifyRouteRecovery true \
  -e class app.majung.vehicle.RouteRecoveryTest \
  app.majung.vehicle.test/androidx.test.runner.AndroidJUnitRunner
```

로컬 API와 위치 권한을 먼저 준비해야 한다. 경로 요청 권한은 debug 매니페스트에만 있다. 실제 차량·주행·실패한 경로 검색의 모든 SDK 오류 코드는 이번 에뮬레이터 시험 범위에 포함하지 않는다.
