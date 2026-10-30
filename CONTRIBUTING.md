
# 마중 차량 앱 기여 가이드

마중 차량 앱의 코드와 문서를 수정할 때 사용하는 개발 규칙이다.

커밋 메시지, 브랜치 및 Pull Request(PR) 관리, 코드 스타일, 테스트와 보안 기준을 정리했다.

이 문서의 로컬 Git 훅 설정은 **`majung-vehicle-app` 저장소에 적용**된다. 백엔드·웹·공통 저장소의 설정은 각각 별도로 관리한다.

---

## 1. 개발 환경 설정

### 최초 설치

Node.js 22 이상과 npm이 필요하다.

저장소 루트에서 다음 명령을 실행한다.

```sh
npm ci
git config --local commit.template .gitmessage
```

`npm ci`의 `prepare` 스크립트를 통해 Husky가 `commit-msg` 훅을 설치한다.

이후 터미널과 Git 훅을 실행하는 IDE에서 커밋 메시지 형식을 검사한다.

새로 저장소를 clone한 개발자도 자신의 로컬 환경에서 실행해야 한다.

> `npm ci`는 Husky·commitlint 등 개발 도구를 설치한다. 차량 앱 자체의 빌드와 테스트는 Gradle을 사용한다.

### Git 훅 확인

```sh
git config --get core.hooksPath
node --version
npm --version
```

GUI에서 Node.js를 찾지 못한다면 IDE의 실행 환경과 PATH를 확인한다.

기존 Git 훅이 있다면 충돌 여부를 확인하고 통합한다. 검사를 끄는 방식으로 해결하지 않는다.

---

## 2. 커밋 메시지

Conventional Commits 형식을 사용한다.

### 기본 형식

```text
type(scope): 변경 내용

필요한 경우 변경 이유와 제약을 설명한다.

Refs: #12
```

- `type` 필수
- `scope` 선택
- 제목 72자 이내
- 제목 끝 마침표 생략
- 한국어 설명 기본
- API·SDK·클래스 이름은 원래 표기 유지

### type 선택

| type | 용도 | 예시 |
| --- | --- | --- |
| `feat` | 새 기능 | `feat(vehicle): 연결된 사람 직접 초대 추가` |
| `fix` | 오류 수정 | `fix(vehicle): 복구 후 이전 ETA 표시 차단` |
| `refactor` | 동작 변경 없는 구조 개선 | `refactor(vehicle): 픽업 상태 렌더링 분리` |
| `test` | 테스트 추가 및 수정 | `test(sdk): 경로 재연결 상태 검증` |
| `docs` | 문서 변경 | `docs: SDK 콜백 측정 결과 정리` |
| `style` | 공백·정렬 등 형식 변경 | `style(vehicle): 코드 줄바꿈 정리` |
| `build` | 빌드 설정 및 의존성 변경 | `build(deps): NaviHelper 버전 변경` |
| `ci` | CI/CD 자동화 변경 | `ci: 빌드 검사 단계 수정` |
| `chore` | 기타 유지보수 | `chore(repo): 커밋 검사 설정 정리` |
| `revert` | 이전 변경 되돌리기 | `revert(vehicle): 화면 전환 변경 되돌리기` |

화면 배치나 버튼 색상 변경은 코드 형식만 변경하는 `style`과 구분한다.

사용자에게 보이는 동작이나 화면을 변경한 경우 목적에 따라 `feat` 또는 `fix`를 사용한다.

### scope 선택

| scope | 대상 |
| --- | --- |
| `vehicle` | 차량 앱 기능 및 UI |
| `web` | 휴대전화 웹 관련 변경 |
| `server` | 서버 API 관련 변경 |
| `prototype` | 화면·기능 초기 시안 |
| `sdk` | Pleos SDK 연동 및 시험 |
| `repo` | 저장소 공통 설정 |
| `ci` | 자동화 및 배포 |
| `deps` | 의존성 관리 |

scope는 한 개만 사용한다.

여러 영역을 함께 변경하거나 공통 문서를 수정한 경우 생략할 수 있다.

새 scope를 추가하거나 제거할 때는 `commitlint.config.cjs`와 이 문서를 함께 수정한다.

### 좋은 커밋 메시지

```text
feat(vehicle): 받은 요청 대기열 화면 추가
fix(sdk): 경로 변경 후 이전 ETA 전송 차단
test(vehicle): 픽업 종료 후 접근 정보 삭제 검증
docs: 차량 최초 연결 절차 보완
```

다음처럼 변경 내용을 알 수 없는 메시지는 피한다.

```text
수정
업데이트
작업 완료
wip
fix: 수정
```

관련 없는 기능과 문서 변경은 가능한 한 별도 커밋으로 분리한다.

### 이슈 연결

관련 이슈는 커밋 본문에 작성한다.

```text
Refs: #12
```

이슈의 완료 기준을 모두 충족한 경우에만 종료 키워드를 사용한다.

```text
Closes: #12
```

### 호환성 변경

기존 API나 데이터 형식과 호환되지 않는 변경은 `!`로 표시한다.

```text
feat(vehicle)!: 픽업 세션 저장 형식 변경

BREAKING CHANGE: 이전 저장 형식과 호환되지 않는다.
기존 세션 처리 및 앱 업데이트 영향을 확인해야 한다.
```

변경이 필요한 이유와 기존 사용자에게 미치는 영향을 함께 기록한다.

---

## 3. 커밋 검사

### 메시지 검사

커밋을 생성하지 않고 메시지 형식을 검사한다.

```sh
echo 'feat(vehicle): 받은 요청 화면 추가' | npm run lint:commit -- --verbose
```

### 마지막 커밋 검사

```sh
npm run lint:commit -- --from HEAD~1 --to HEAD --verbose
```

### 검사 실패 시

오류 메시지에서 다음 항목을 확인한다.

- 지원하지 않는 type 또는 scope
- 제목 길이 초과
- 빈 변경 설명
- 제목 끝 마침표
- 커밋 메시지 형식 오류

공유하지 않은 마지막 커밋은 다음 명령으로 수정할 수 있다.

```sh
git commit --amend
```

이미 공유한 커밋의 변경 이력을 다시 작성해야 한다면 다른 기여자에게 미치는 영향을 먼저 확인한다.

---

## 4. 브랜치와 Pull Request

`main`을 기준 브랜치로 사용한다.

현재 별도 `develop` 브랜치는 운영하지 않는다.

### 브랜치 이름

```text
feat/12-pickup-session
fix/18-route-validation
docs/contributing-guide
```

`작업종류/영문-주제` 형식을 사용하며, 관련 이슈가 있다면 주제 앞에 번호를 추가한다.

### 작업 순서

1. 관련 이슈 확인
2. 변경 범위 및 완료 기준 정리
3. `main`에서 작업 브랜치 생성
4. 기능 구현 및 테스트
5. 커밋 메시지 검사
6. Pull Request 생성
7. 변경 내용과 검증 결과 확인
8. Squash merge

이슈가 없다면 필요한 이유와 작업 범위를 기록한 후 진행한다.

### PR 작성

PR 제목도 Conventional Commits 형식을 사용한다.

```text
fix(vehicle): 위치 공유 중단 후 이전 ETA 숨김
```

본문은 `.github/pull_request_template.md`를 기준으로 작성한다.

**변경 이유**

기존 문제와 수정 목적 설명

**주요 변경 사항**

실제 변경된 기능과 동작 정리

**검증**

실행한 테스트와 결과 기록

**참고 사항**

남은 검증 항목과 알려진 제약 정리

화면을 변경한 경우에는 시험 환경과 함께 캡처를 첨부한다.

### 리뷰 및 병합

가능하면 작성자 외 다른 개발자의 리뷰를 요청한다.

리뷰어가 없는 경우에는 자체 검토한 범위와 실제 테스트 결과를 PR에 기록한다.

Squash merge를 기본으로 사용하고, 병합 전에 최종 제목과 본문을 확인한다.

과거 기여자의 커밋 이력은 소급하여 수정하지 않는다.

---

## 5. Kotlin 및 차량 앱 코드 스타일

### 기본 명명 규칙

| 대상 | 형식 | 예시 |
| --- | --- | --- |
| 클래스·타입 | `PascalCase` | `PickupController` |
| 함수·변수 | `camelCase` | `completePickup` |
| 상수 | `UPPER_SNAKE_CASE` | `MAX_RETRY_COUNT` |

기존 인접 코드의 구조와 작성 방식을 먼저 확인한다.

Kotlin 공식 코딩 스타일을 기본으로 사용한다.

### 역할 분리

차량 앱은 화면, 상태 관리, SDK 통신 및 백그라운드 공유를 구분한다.

| 구성 요소 | 주요 책임 |
| --- | --- |
| `MainActivity` | 화면 생명주기 및 UI |
| UI Section | 기능별 화면 구성 |
| `PickupController` | 픽업 상태 전이 및 서버 요청 |
| `DestinationReader` | 내비게이션 SDK 정보 조회 |
| `VehicleTelemetry` | 위치·ETA 유효성 확인 |
| `PickupSharingService` | 화면 밖 위치·ETA 공유 |
| `EncryptedPickupSessionStore` | 접근 정보 암호화 저장 |

화면을 수정하면서 픽업 상태 전이, SDK 호출 또는 접근 권한 검증을 중복 구현하지 않는다.

### 상태 구분

다음 상태는 서로 다른 의미로 관리한다.

- 픽업 진행 상태
- 탑승자 준비 상태
- 차량 연결 상태
- 내비게이션 경로 상태
- 위치·ETA 유효성

서로 다른 상태를 하나의 Boolean이나 화면 표시 조건으로 무리하게 합치지 않는다.

---

## 6. API 및 시간 처리

### API 규칙

- 복수 명사 기반 경로 사용
- JSON 필드 `camelCase`
- 오류 응답에 `code`, `message`, `requestId` 사용
- API 계약 변경 시 서버·웹과 영향 범위 확인

예시:

```text
/api/v1/pickups
/api/v1/pickups/{pickupId}
```

### 시간과 단위

| 항목 | 기준 |
| --- | --- |
| 서버와 주고받는 시각 | UTC ISO 8601 |
| 시간 길이 | 초 |
| 거리 | 미터 |
| 시간 간격 및 제한 | 코드에서 단위 명시 |

예시:

```text
durationSeconds
distanceMeters
```

SDK 응답 수신 시각과 실제 GPS 측정 시각은 구분한다.

위치·ETA 전송 시 `sourceTime`을 단순히 현재 시각으로 덮어쓰지 않는다.

---

## 7. 테스트 기준

변경한 기능과 관련된 정상 흐름뿐 아니라 권한·중복·만료·복구 조건을 확인한다.

### 우선 검증 항목

- 승인 전 접근 차단
- 다른 픽업 접근 차단
- 종료된 픽업의 위치·ETA 차단
- 초대 만료 및 권한 해제
- 중복 요청 및 이벤트 역순 처리
- SDK 응답 지연·누락
- 경로와 목적지 불일치
- 화면 중단 후 공유 유지
- 앱 재실행 후 사용자 확인
- 전송 순번 유지 및 중복 방지
- 주행 제한 시 상세 조작 차단

### 검증 환경 구분

| 환경 | 설명 |
| --- | --- |
| JVM 테스트 | 로직 단위 검증 |
| Android 계측 테스트 | 앱과 Android 구성 요소 검증 |
| 로컬 API | 개발용 서버 연동 |
| 공식 Pleos 에뮬레이터 | SDK 및 차량 UI 확인 |
| 실제 휴대전화 | 실기기 웹·계정 연동 |
| 실제 차량 | 실차 주행 및 시스템 동작 |

한 환경에서 통과한 테스트를 다른 환경의 검증 완료로 표현하지 않는다.

특히 에뮬레이터의 P → D → P 제한 전환 시험은 실제 차량 주행 검증과 구분한다.

### PR 검증 기록

PR에는 실제 실행한 명령과 결과를 기록한다.

```text
검증
- :vehicle:testDebugUnitTest 통과
- :vehicle:lintDebug 통과
- 공식 Pleos 에뮬레이터에서 화면 전환 확인

추가 확인
- 실제 차량 주행 제한 미검증
```

실행하지 않은 검사는 통과한 것으로 기록하지 않는다.

조건부로 건너뛴 시험도 별도로 명시한다.

---

## 8. 보안 및 개인정보

다음 정보는 Git 커밋, 공개 로그, PR 및 시연 자료에 포함하지 않는다.

- 운전자 및 차량 접근 토큰
- 초대 링크 원문
- 카카오 인증 비밀 값
- 실제 사용자 위치·주소
- 서명용 Keystore 및 비밀번호
- 사용자 개인정보

시험에는 가상 계정과 시험용 장소를 우선 사용한다.

### 차량 접근 정보

픽업 접근 정보는 Android Keystore 기반 암호화 저장소에 관리한다.

저장 실패 시 평문 저장으로 우회하지 않는다.

### 위치 공유

픽업이 종료되거나 접근 권한이 유효하지 않으면 위치·ETA 공유를 중단한다.

이전에 수신한 위치·ETA를 새로운 정보처럼 재전송하지 않는다.

### 시연 및 문서

기능 상태는 다음을 구분한다.

- 구현 완료
- 로컬 테스트 확인
- 에뮬레이터 확인
- 실제 휴대전화 확인
- 실차 검증
- 미검증 및 향후 계획

개발 문서에 테스트 결과를 적을 때는 실행 환경과 기준 버전을 함께 남긴다.

---

## 9. 현재 자동화 적용 범위

| 항목 | 상태 |
| --- | --- |
| 커밋 type·scope 검사 | 로컬 commitlint 적용 |
| 제목 길이·빈 설명·마침표 검사 | 로컬 commitlint 적용 |
| 로컬 커밋 훅 | `npm ci` 후 Husky 적용 |
| PR 본문 작성 안내 | PR 템플릿 적용 |
| 변경 내용과 제목 일치 여부 | 수동 리뷰 |
| GitHub PR 제목·커밋 CI 검사 | 아직 미적용 |
| `main` 직접 푸시 차단 | 아직 미적용 |
| 검사 실패 시 병합 차단 | 아직 미적용 |

로컬 Git 훅은 개발자 환경에서 동작한다.

GitHub에서 PR 병합 조건을 강제하려면 별도 CI 검사와 브랜치 보호 규칙이 필요하다.

적용하지 않은 자동화나 접근 제한 기능을 적용된 것으로 표시하지 않는다.

---

## 10. 기여 이력 관리

마중은 공동 개발로 시작한 프로젝트다.

초기 기능 개발과 이후 유지보수는 실제 커밋 및 PR을 기준으로 구분한다.

기여를 확인할 때는 다음 자료를 함께 확인한다.

- 실제 작성 커밋
- 변경 파일
- Pull Request
- 기능별 개발 기록
- 테스트 및 배포 이력

PR을 생성하거나 병합한 사람이 모든 코드를 직접 작성했다고 판단하지 않는다.

이후 리팩터링이나 기능 통합이 진행되더라도 기존 작성자의 기여 이력을 유지한다.

[개발 현황 및 기여 기록](https://github.com/majung-team/majung/blob/main/docs/development-status.md)

---

## 11. 관련 문서

- [차량 앱 README](README.md)
- [차량 화면 코드 구조](docs/vehicle-screen.md)
- [픽업 저장 및 복구](docs/pickup-recovery.md)
- [백그라운드 위치 공유](docs/background-sharing.md)
- [주행 중 픽업 요약](docs/driving-summary.md)
- [SDK 콜백 측정](docs/sdk-callback-timing.md)
- [Release APK 빌드 및 서명](docs/release-signing.md)
- [전체 프로젝트](https://github.com/majung-team/majung)

### 공식 참고 자료

- [Conventional Commits](https://www.conventionalcommits.org/ko/v1.0.0/)
- [commitlint](https://commitlint.js.org/guides/local-setup.html)
- [Husky](https://typicode.github.io/husky/get-started.html)
- [Kotlin 코딩 스타일](https://kotlinlang.org/docs/coding-conventions.html)

---

## 문서 관리 기준

커밋 규칙을 변경할 때는 `commitlint.config.cjs`와 이 문서를 함께 수정한다.

Git 훅이나 자동화 설정을 변경하면 실제 적용 여부를 확인한 후 문서에 반영한다.

규칙 도입 이전의 커밋 이력은 소급해서 변경하지 않는다.

코드 작성 규칙과 테스트 기준은 실제 구현 및 실행 결과를 중심으로 관리한다.
