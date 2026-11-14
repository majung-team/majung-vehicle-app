# 기본 내비 지도 초기화 실패 안내

2026년 11월 13일 정리. 아래 현상은 11월 12일에 관찰했다. 수정본 제공 여부는 아직 확인되지 않았다.

## 확인한 환경과 증상

- Pleos Connect Emulator 2.1.0, Android 14 / API 34, arm64-v8a
- 기본 내비 Navi42 `3.3.2-rc5-hotfix.3`
- 마중 사용 SDK: NaviHelper `3.0.5`, TextToSpeech `3.3.1`
- 스냅샷을 사용하지 않는 콜드부팅 및 마중 미실행 상태에서도 기본 내비 실패 재현
- 인증·내비 엔진 초기화 이후 `mapCredential` 응답 처리 중 실패

```text
AuthClient: Encountered an unknown key 'profile' at path: $.credentials[1].provider
Map42View: Map42 is not initialized
Navi42: Map onInitialized false
```

인증 응답 원문과 자격 증명은 싣지 않았다. 위 로그는 기본 내비가 서버 응답의 항목을 처리하지 못한 사실을 보여준다. 서버 변경 시점이나 제공 측의 정확한 수정 대상까지 확인한 것은 아니다.

## 직접 확인할 순서

1. 마중을 닫고 기본 내비를 실행한다. 같은 오류가 나면 마중의 픽업 상태나 APK 설치 문제와 구분한다.
2. AVD 이름과 공식 시스템 이미지 버전을 확인하고 콜드부팅한다. 아래 명령 자체는 정상 실행 명령이다.
3. 같은 오류가 계속되면 APK 삭제·재설치나 위치 권한 변경을 반복하지 말고 제공 측의 수정 이미지·내비 업데이트 안내를 확인한다.

```sh
"$HOME/Library/Android/sdk/emulator/emulator" \
  -avd Majung_Pleos_2_1 \
  -no-snapshot-load \
  -no-snapshot-save
```

AVD 이름은 설치한 이름으로 바꾼다. `ignoreUnknownKeys`는 기본 내비 내부 파서의 설정이므로 마중 코드에 추가해도 해당 파서가 바뀌지 않는다. 마중 SDK만 올려 해결됐다고 확인하지 않았다.

## 공식 문의와 남은 확인

11월 12일 운영사무국에 재현 환경·오류 발췌를 보내고, 개발자가 직접 적용할 수 있는 수정 Navi42 APK·에뮬레이터 이미지의 다운로드 경로와 업데이트 절차를 문의했다. 수정본이나 복구 일정은 확인 전까지 완료로 표시하지 않는다.

- [추적 이슈 #134](https://github.com/majung-team/majung-vehicle-app/issues/134)
- [별도 경유지 원본 좌표 0 응답 추적](waypoint-provider-report.md)
- [11월 10일 정상 시연 기록](https://github.com/majung-team/majung/blob/main/docs/pickup-demo.md)

기본 내비 초기화 오류, 경유지 원본 좌표 오류, 마중 앱의 좌표 복구·경로 검증은 서로 다른 문제다. 실제 차량 환경에서 같은 오류가 발생한다고 확인한 것은 아니다.
