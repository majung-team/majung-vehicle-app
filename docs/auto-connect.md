# 탑승자 자동 연결과 끊기

기능: 2.1 자동 연결, 2.2 탑승자 끊기. 서버 API는 [majung-backend 자동 연결과 끊기](https://github.com/majung-team/majung-backend/blob/main/docs/auto-connect.md)를 따른다.

## 차량 화면

- 초대 링크로 탑승자가 참여하면 승인 없이 바로 `ACTIVE`가 된다. 상태에는 "탑승자 연결됨 · 픽업 진행 중"이 표시되고 위치·ETA 공유가 시작된다.
- **탑승자 끊기**: 연결된 탑승자가 있고 `ACTIVE`일 때 활성화된다. 확인창에서 끊으면 서버가 새 초대를 발급하고 차량은 `INVITING`으로 돌아간다. 같은 QR 버튼으로 새 초대를 다시 공유한다.
- 끊은 뒤에는 위치·ETA 전송이 멈추고, 새 탑승자가 연결되면 이어서 증가하는 순번으로 다시 보낸다.
- **참여자 확인하고 공유 시작** 버튼은 `WAITING_APPROVAL` 상태의 이전 픽업이 있을 때만 보인다. 새 참여는 이 상태를 거치지 않는다.
- 마중 요청·직접 초대로 시작한 픽업은 서버가 끊기를 거절한다(`RIDE_REQUEST_PICKUP`). 같은 앱 실행 중에는 버튼을 비활성으로 두고, 앱을 다시 켠 뒤 누르면 안내 문구를 보여주고 이후 비활성으로 둔다.
- `ARRIVED` 이후에는 끊기 버튼을 쓰지 않는다. 도착 상태가 초대 대기로 되돌아가는 것을 막기 위해서다.

## 확인 범위

- `./gradlew :vehicle:testDebugUnitTest :vehicle:compileDebugAndroidTestKotlin :vehicle:assembleDebug :vehicle:lintDebug` 통과.
- 계측 테스트(`androidTest`)는 승인 대기 상태를 기다리던 부분을 자동 연결 기준으로 고쳤고 컴파일만 확인했다. 에뮬레이터에서 실행하지 않았다.
- 끊기 버튼과 확인창, 재초대 후 전송 재개는 에뮬레이터에서 확인하지 않았다. 서버와 웹은 로컬에서 확인했다.
