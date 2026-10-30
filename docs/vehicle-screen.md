
# 차량 화면 코드 구조

마중 차량 앱의 화면 구성, 상태 갱신, 생명주기 및 내비게이션 연동 구조를 정리한 문서다.

`MainActivity`는 화면과 생명주기를 관리하고, 각 기능의 UI는 별도 Section 클래스로 구성한다.

픽업의 상태 전이와 서버 요청은 기존 컨트롤러가 담당하며, 화면 영역은 현재 상태를 읽어 표시하는 역할에 집중한다.

## 1. 전체 구조

차량 앱의 주요 구성은 다음과 같다.

| 구성 요소 | 역할 |
| --- | --- |
| `MainActivity` | 화면 배치, 생명주기, 상태별 화면 전환 및 주기 갱신 |
| UI Section | 기능별 화면 생성, 버튼 상태 및 표시 내용 갱신 |
| `VehicleViews` | 공통 UI 요소와 렌더링 보조 |
| `VehicleApplication` | 계정·내비게이션·픽업 컨트롤러 연결 |
| `PickupController` | 픽업 상태 전이, 서버 요청 및 접근 정보 관리 |
| `PickupSharingService` | 화면 밖 위치·ETA 공유, 서버 상태 조회 및 종료 감지 |
| `DestinationReader` | SDK 내비게이션 정보 조회 |
| `VehicleTelemetry` | 전송 가능한 위치·ETA 데이터 검증 |

### 화면과 상태 관리 관계

```text
MainActivity
    │
    ├── 화면 생명주기 및 주기 갱신
    │
    ├── VehicleViews
    │
    └── UI Section
          ├── PickupCreationSection
          ├── VehicleRegistrationSection
          ├── VehicleEnrollmentSection
          ├── VehicleAccountSection
          ├── PickupParticipantSection
          ├── PickupStatusSection
          └── PickupCompletionSection
                    │
                    ▼
             VehicleApplication
                    │
          ┌─────────┴──────────┐
          ▼                    ▼
    PickupController     DestinationReader
          │                    │
          ▼                    ▼
       서버 API          Pleos NaviHelper
          │
          ▼
    PickupSharingService
```

위 구조는 클래스의 역할을 이해하기 위한 개념도다. 실제 객체 생성 순서나 모든 호출 관계를 나타낸 것은 아니다.

---

## 2. 화면 구성 파일

### Activity 및 공통 UI

| 파일 | 담당 |
| --- | --- |
| `MainActivity.kt` | 왼쪽 메뉴·본문 구성, 상태별 화면 전환, `onStart`·`onStop`, 화면 갱신, 권한 및 재개 확인 |
| `VehicleViews.kt` | 공통 라벨·버튼·카드 스타일과 렌더링 보조 |
| `VehicleColumns.kt` | 가용 폭에 따른 가로·세로 배치 |

### 기능별 화면

| 파일 | 담당 |
| --- | --- |
| `PickupCreationSection.kt` | 내비 연결, 최근 장소 선택, 경유지 추가 및 픽업 생성 |
| `VehicleRegistrationSection.kt` | 차량 등록 상태 표시 및 재확인 |
| `VehicleEnrollmentSection.kt` | 최초 연결 QR, 휴대전화 연결 요청 및 운전자 본인 확인 |
| `VehicleAccountSection.kt` | 운전자 계정, 받은 요청 및 연결된 사람 선택 |
| `PickupParticipantSection.kt` | 초대 링크, HTTPS 공유 QR, 참여 확인 및 서버 상태 재조회 |
| `PickupStatusSection.kt` | 연결 상태, 픽업 진행, 장소, 준비 상태 및 차량 정보 |
| `PickupCompletionSection.kt` | 도착, 탑승 완료, 취소 확인 및 다음 픽업 준비 |

Section 클래스는 해당 기능의 화면을 구성하고 현재 상태에 따라 문구와 조작 가능 여부를 갱신한다.

서버 요청이나 픽업 상태 전이를 별도의 UI 로직으로 중복 구현하지 않는다.

---

## 3. 화면 생성 및 렌더링

### Section 생성

각 Section은 `VehicleApplication`과 `VehicleViews`를 전달받아 생성한다.

생성자의 초기화 단계에서 해당 영역의 뷰를 기존 화면 순서에 맞게 추가한다.

### 상태 렌더링

각 Section의 `render()`는 현재 앱 상태를 읽고 다음 항목을 갱신한다.

- 화면에 표시할 문구
- 버튼 활성화 여부
- 영역 표시 및 숨김
- 진행 상태
- 오류 및 안내 메시지

`MainActivity.render()`는 각 Section의 `render()`를 호출한다.

### 버튼 동작

`VehicleViews.button()`으로 생성한 버튼은 동작 실행 후 `MainActivity.render()`를 호출한다.

비동기 서버 요청이 완료되면 기존 `pickup.changed` 콜백으로 화면을 다시 갱신한다.

```text
사용자 버튼 선택
       │
       ▼
기존 컨트롤러에 동작 요청
       │
       ├── 현재 상태 변경
       │        │
       │        ▼
       │   MainActivity.render()
       │
       └── 비동기 요청
                │
                ▼
           요청 완료
                │
                ▼
          pickup.changed
                │
                ▼
         MainActivity.render()
```

### 불필요한 화면 변경 방지

`VehicleViews`는 내용이 변경된 경우에만 `TextView`를 갱신한다.

주기적인 상태 조회가 발생해도 텍스트를 불필요하게 다시 설정하지 않도록 한다.

또한 Section마다 별도의 타이머나 API 클라이언트를 만들지 않는다.

화면 갱신과 데이터 조회는 기존 Activity 및 컨트롤러에서 관리한다.

---

## 4. 화면 갱신과 생명주기

### 갱신 주기

| 동작 | 기준 |
| --- | --- |
| 화면 표시 갱신 | 1초 |
| 공유 서비스 미실행 시 픽업 상태 조회 | 3초 |
| 백그라운드 공유 서비스 SDK 조회·전송 | 5초 |

위 주기는 각 구성 요소의 역할을 구분하기 위한 구현 기준이다.

`MainActivity`는 사용자에게 표시할 상태를 갱신하고, `PickupSharingService`는 화면 밖에서도 필요한 픽업 공유를 이어간다.

### Activity 시작

`onStart`에서 화면 표시와 필요한 상태 갱신을 연결한다.

기존 픽업이 복구 대상이라면 서버 상태와 사용자 확인 절차를 거친다.

### Activity 중단

`onStop`에서는 화면 콜백과 Activity 타이머를 해제한다.

다만 Activity가 멈췄다고 해서 진행 중인 픽업을 자동으로 종료하지 않는다.

다음 상황에서는 공유 서비스의 SDK 연결을 유지한다.

- `PickupSharingService`가 실행 중인 경우
- 픽업 공유 서비스 시작 요청이 진행 중인 경우

해당 조건이 없으면 사용하지 않는 SDK 연결을 해제한다.

### 프로세스 종료 및 재실행

앱 프로세스가 종료되었다면 저장된 픽업 접근 정보와 서버 상태를 확인해 복구한다.

이전 위치 공유를 사용자 확인 없이 자동으로 재개하지 않는다.

상세한 복구 조건은 [픽업 저장 및 복구](pickup-recovery.md)를 참고한다.

---

## 5. 정차 및 주행 제한 화면

차량 앱은 시스템의 CarUxRestrictions를 기준으로 조작 가능한 화면과 읽기 전용 화면을 구분한다.

### 정차 화면

정차 상태에서는 왼쪽 메뉴와 스크롤 가능한 본문을 표시한다.

주요 메뉴:

- 픽업
- 받은 요청
- 설정

### 주행 제한 상태

주행 제한이 적용되거나 필요한 차량 상태를 확인할 수 없는 경우에는 상세 조작을 숨긴다.

`parkedRoot` 전체에 제한을 적용하여 왼쪽 메뉴와 본문을 함께 숨기고 읽기 전용 요약 화면을 표시한다.

기존에 열린 조작용 확인창도 닫는다.

새로운 UI를 추가할 때도 조작 버튼은 제한 대상 컨테이너 밖에 배치하지 않는다.

[주행 중 요약 화면](driving-summary.md)

---

## 6. 화면 배치 기준

### 공통 구성 요소

`VehicleViews.section()`은 흰색 카드와 여백을 구성한다.

`button(primary = true)`는 해당 화면의 주요 동작을 강조하는 데 사용한다.

버튼은 최소 높이 **72dp**를 기준으로 하며, 글자 크기가 커지면 필요한 높이를 확보한다.

### 반응형 배치

`VehicleColumns`는 실제 사용 가능한 화면 폭을 기준으로 영역을 배치한다.

| 가용 폭 | 배치 |
| --- | --- |
| 960dp 이상 | 관련 영역을 가로로 배치 |
| 960dp 미만 | 관련 영역을 세로로 배치 |

좁은 화면에서는 본문을 스크롤할 수 있도록 구성한다.

시스템 영역과 상태 바, 내비게이션 화면을 앱에서 직접 다시 그리지 않는다.

### 메뉴와 스크롤

메뉴를 전환할 때만 본문의 스크롤 위치를 맨 위로 이동한다.

상태 조회나 주기적 화면 갱신만으로 스크롤 위치를 초기화하지 않는다.

---

## 7. UI를 수정할 때 지킬 기준

### 상태 및 권한 확인

장소 검증, 차량 권한 확인, API 요청은 기존 컨트롤러를 사용한다.

화면 응답을 빠르게 보이게 하기 위해 검증이 끝나지 않은 버튼을 미리 활성화하지 않는다.

특히 다음 작업은 검증 완료 후에만 허용한다.

- 픽업 생성
- 탑승자 초대
- 장소 변경 확정
- 픽업 시작
- 도착 및 탑승 완료

### 주행 제한

새로운 메뉴나 버튼을 추가할 때는 `parkedRoot`의 조작 제한을 확인한다.

화면이 열려 있는 동안 주행 제한 상태가 변경돼도 조작이 계속 허용되지 않도록 한다.

### 기존 동작 ID

기존 테스트와 화면 자동화에서 사용하는 동작 ID를 유지한다.

```text
parked_controls
pickup_invite_contact
create_pickup
arrive_pickup
complete_pickup
```

ID를 변경해야 한다면 해당 ID를 참조하는 테스트도 함께 수정한다.

### 불필요한 구조 중복 방지

새 Section에 독립적인 상태 관리자, SDK 연결, 타이머 또는 API 클라이언트를 추가하기 전에 기존 컨트롤러에서 처리할 수 있는지 확인한다.

UI는 현재 상태를 표시하고 사용자 입력을 전달하는 역할에 집중한다.

---

## 8. SDK 경로 및 픽업 장소 처리

차량 내비게이션 경로는 `DestinationReader`를 통해 확인한다.

화면은 조회 결과를 표시하고, `PickupController`는 검증된 장소를 기준으로 픽업을 생성한다.

### 주요 파일

| 파일 | 역할 |
| --- | --- |
| `SdkPickupPlace.kt` | 장소 좌표 및 ID 관리, 식별자 연결 |
| `NavigationStops.kt` | 최종 목적지·경유지 구분 및 ETA 조회 |
| `WaypointInsertion.kt` | 새 경유지 추가 및 기존 경로 유지 확인 |
| `RouteValidation.kt` | 두 차례 경로 확인과 응답 간격 관리 |
| `NavigationFailure.kt` | 내비게이션 오류에 따른 안내 분류 |
| `NavigationRequest.kt` | SDK 요청 및 예외 구분 |
| `VehicleTelemetry.kt` | 위치·ETA의 유효성 및 전송 가능 여부 확인 |

### 장소 식별자 처리

`SdkPickupPlace`는 선택한 장소의 좌표와 ID를 관리한다.

필요한 경우 자체 식별자를 부여하고 SDK 응답의 장소 ID를 대조하여 관련 좌표를 연결한다.

이 과정은 SDK가 반환한 좌표값을 임의로 수정하는 처리와 구분한다.

### 경유지 확인

`WaypointInsertion`은 요청한 경유지 한 건이 추가됐는지 확인한다.

기존 최종 목적지와 다른 경유지가 유지됐는지도 함께 검사한다.

### 경로 검증

`RouteValidation`은 SDK 응답의 대기 시간과 경로 재확인 간격을 관리한다.

한 번의 경로 응답만으로 픽업 장소를 확정하지 않는다.

### 위치 및 ETA 전송

`VehicleTelemetry`는 확정된 픽업 장소에 대한 ETA와 최신 위치가 함께 유효한 경우에만 전송할 데이터를 구성한다.

관련 정보가 부족하면 이전 ETA를 현재 정보처럼 전송하지 않는다.

[경유지 좌표 누락 대응](waypoint-coordinates.md)

---

## 9. 화면 영역 분리 당시 검증 결과

화면을 Section 단위로 분리하고 기존 픽업 기능과 연결한 당시 수행한 시험 기록이다.

### 빌드 및 단위 테스트

| 항목 | 결과 |
| --- | --- |
| 차량 단위 테스트 | 8개 통과 |
| Debug APK 빌드 | 성공 |
| AndroidTest APK 빌드 | 성공 |
| Android Lint | 통과 |

### 공식 에뮬레이터 통합 테스트

**기존 통합 테스트 2개 통과**

확인한 주요 흐름:

- 내비게이션 목적지 기반 픽업 생성
- 탑승자 참여 승인
- SDK 위치·ETA 전달
- SDK 연결 해제 및 재연결
- 탑승자 준비 상태 수신
- 픽업 취소
- 도착 및 탑승 완료
- 종료 후 접근 차단

### UI 상태 유지

반복적인 상태 조회 중 다음 항목이 유지되는지 확인했다.

- 본문 높이
- 버튼 위치
- 스크롤 상태
- 픽업 취소 가능 상태

### 시험 환경과 한계

시험은 같은 Mac에서 실행한 Pleos 공식 에뮬레이터와 로컬 API를 사용했다.

실제 차량이나 실제 휴대전화에서 수행한 결과는 아니다.

또한 주행 중 UI의 실제 허용 여부를 검증한 결과로 해석하지 않는다.

위 테스트 수치는 화면 구조 분리 당시의 기록이며, 이후 전체 테스트 개수나 최신 배포 검증 결과를 뜻하지 않는다.

---

## 10. 관련 문서

- [차량 앱 README](../README.md)
- [현재 차량 화면](service-screens.md)
- [차량 최초 연결](vehicle-enrollment.md)
- [운전자 계정 및 요청](vehicle-account-requests.md)
- [요청에서 픽업 시작하기](request-pickup-flow.md)
- [픽업 저장 및 복구](pickup-recovery.md)
- [백그라운드 위치 공유](background-sharing.md)
- [주행 중 요약 화면](driving-summary.md)
- [경유지 좌표 누락 대응](waypoint-coordinates.md)
- [전체 시스템 아키텍처](https://github.com/majung-team/majung/blob/main/docs/architecture.md)
- [개발 현황과 PR 근거](https://github.com/majung-team/majung/blob/main/docs/development-status.md)

---

## 문서 관리 기준

이 문서는 UI의 내부 코드 구조와 상태 갱신 책임을 설명한다.

화면별 사용자 이용 방법은 `service-screens.md`, 픽업 저장 및 재개는 `pickup-recovery.md`, 화면 밖 공유는 `background-sharing.md`에서 별도로 관리한다.

UI 구조를 변경할 때는 기존 동작 ID, 주행 제한, SDK 연결 생명주기 및 반복 렌더링 테스트를 함께 확인한다.
