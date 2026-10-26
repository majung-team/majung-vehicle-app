# Release APK 만들기

제출·팀 시험용 release APK는 마중 HTTPS 주소와 전용 서명 키로 빌드한다.

```sh
python3 tools/build-release.py
```

설정 파일은 기본적으로 `~/.local/share/majung-signing/release-signing.json`에서 읽는다. 다른 위치라면 `--config`로 지정한다. 파일 형식은 아래와 같고, 실제 값은 저장소에 넣지 않는다.

```json
{
  "storeFile": "/저장소밖/majung-release.jks",
  "storePassword": "개인적으로 보관한 값",
  "keyAlias": "majung-release",
  "keyPassword": "개인적으로 보관한 값"
}
```

설정 파일과 키 파일은 본인 소유·권한 600이어야 한다. 빌드 스크립트가 환경변수로 전달한다. 설정이 없거나 HTTPS 주소를 지정하지 않으면 release 패키징을 중단한다. 비밀번호는 명령줄 인자나 빌드 로그로 출력하지 않는다.

결과는 `vehicle/build/outputs/apk/release/vehicle-release.apk`다. Android SDK의 `apksigner verify --print-certs`로 서명을 확인하고, 공개 서버 주소가 지정된 빌드인지 확인한 뒤 전달한다.

기존 debug APK와는 서명이 다르다. `install -r`이 서명 불일치로 실패하면 기존 앱을 바로 지우지 말고, 진행 중 픽업을 끝내고 차량 재등록을 준비한다. 앱 삭제는 저장된 차량·픽업 접근 정보도 지운다.

서명 키를 잃으면 같은 앱을 같은 서명으로 업데이트할 수 없다. 키와 설정은 별도로 안전하게 백업하고 팀 공용 문서·저장소·APK 옆에 올리지 않는다. APK 자체에는 서명 개인 키가 들어가지 않는다.

## 0.2.0 시험 버전

[설치 파일 받기](https://github.com/majung-team/majung-vehicle-app/releases/tag/v0.2.0-preview.20261027)

main `72c5c3e`의 서명 APK를 새 Pleos AVD에 설치해 최초 설정 화면이 열리는 것을 확인했다. 기존 개발 에뮬레이터의 앱·등록 정보는 변경하지 않았다. APK v2 서명, versionCode 2, 디버그 비활성, 평문 HTTP 비활성도 확인했다. 이 시험에서는 운영 차량을 등록하지 않았고 실차를 사용하지 않았다.

![새 에뮬레이터의 release 최초 실행](media/release-first-launch-2026-10-27.png)
