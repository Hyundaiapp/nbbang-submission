# 빌드와 검증

## 실행 환경

- Pleos Connect 에뮬레이터 2.1.0, Android API 34
- 전체 화면 2560 × 1440px, 밀도 160dpi, 앱 영역 약 1700 × 1184px
- JDK 17, Android SDK Platform 34, Build Tools 34.0.0, Gradle Wrapper 8.7
- 앱 ID `com.nbbang`, 버전 `0.1.0`

최초 빌드에는 Gradle 및 Google·Maven Central·Pleos Maven 저장소 접속이 필요하다. `ANDROID_HOME` 또는 로컬 `local.properties`의 `sdk.dir`로 SDK 경로를 지정한다.

## 단위 테스트와 디버그 빌드

```bash
./gradlew --no-daemon :core:test :app:testDebugUnitTest :app:assembleDebug
```

테스트는 에뮬레이터 없이 실행한다. `core`는 안드로이드에 의존하지 않는 구간·정산·청구 로직, `app` 단위 테스트는 SDK 배터리 단위 변환과 실제 전비 회귀를 확인한다.

## release 빌드

제출 APK는 저장소 밖에 보관한 전용 키로 서명했다. 서명 키·비밀번호·오피넷 인증키는 공개하지 않는다. 서명 설정 없이 `:app:assembleRelease`를 실행하면 unsigned APK가 생성된다. 디버그 키로 자동 대체하지 않는다.

직접 설치 가능한 release를 다시 만들 때는 자신의 키를 사용한다. 아래 명령은 저장소 밖에 테스트용 키를 만들며 비밀번호는 입력 프롬프트에서 지정한다.

```bash
keytool -genkeypair -keystore ../nbbang-local-release.p12 -storetype PKCS12 \
  -alias nbbang-local -keyalg RSA -keysize 3072 -validity 10000 \
  -dname "CN=Nbbang Local Test, O=Nbbang, C=KR"
```

저장소 밖 `../nbbang-signing.properties`에 다음 항목을 작성한다. `storeFile`은 이 설정 파일 기준 상대 경로 또는 절대 경로다.

```properties
storeFile=nbbang-local-release.p12
storePassword=키를_만들_때_입력한_비밀번호
keyAlias=nbbang-local
keyPassword=키를_만들_때_입력한_비밀번호
```

```bash
./gradlew --no-daemon :core:test :app:testReleaseUnitTest :app:assembleRelease :app:lintRelease \
  -Pnbbang.signingProperties=../nbbang-signing.properties -Pnbbang.opinetKey=
```

설정 파일 경로는 `NBBANG_SIGNING_PROPERTIES` 환경변수로도 지정할 수 있다. 결과는 `app/build/outputs/apk/release/app-release.apk`다. 키가 바뀌면 기존 설치본 위에 업데이트할 수 없으므로 기존 기록이 있는 환경 대신 별도 테스트 에뮬레이터를 사용한다.

오피넷 키는 `local.properties` → Gradle 속성 → 환경변수 순서로 읽으며 APK에 포함된다. 공개 APK를 다시 만들 때는 `local.properties`에 인증키가 없는지 먼저 확인한다. 키가 없어도 설정 단가로 앱을 사용할 수 있다.

## 제출 APK 검증

2026-11-15에 이 저장소의 소스로 다음 검증을 완료했다.

| 항목 | 결과 |
|---|---|
| `:core:test` | 155개 통과, 실패·오류·건너뛰기 0 |
| `:app:testDebugUnitTest` | 4개 통과 |
| `:app:testReleaseUnitTest` | 동일한 4개를 release에서도 통과 |
| `:app:assembleRelease` | 성공, 전용 인증서로 서명 |
| `:app:lintRelease` | 오류 0, 경고 13, 정보 4 |
| `apksigner verify` | APK Signature Scheme v2 검증 통과 |
| APK Manifest | `com.nbbang` 0.1.0, minSdk 28 / targetSdk 34, 디버그 비활성, `DemoReceiver` 없음 |
| release 설정 | `OPINET_KEY` 빈 문자열 |

서로 다른 테스트는 총 159개이며, 앱의 두 빌드 변형을 합쳐 163회 실행했다. Lint 경고는 의존성 버전, Compose 상태·입력 처리, Gleo 외부 수신기, 백업 설정, 벡터 복잡도 관련이다. Gleo 수신기는 외부 연동을 위해 공개되어 있으며, 호출자 인증 강화는 SDK 연동 방식 확인이 필요한 기존 한계다.

제출 파일은 [`N빵_N빵_release.apk`](../apk/N빵_N빵_release.apk)이며 크기는 44,375,981바이트다.

- APK SHA-256: `e4efbd359f98c4b5002017dc020ecd6a159cee3335116983497f097a538f9f27`
- 서명 인증서: `CN=Nbbang Submission, O=Nbbang, C=KR`
- 인증서 SHA-256: `31c02bf9a6991cada5636f74c0adf1f23a861403bb13cc1c9ad891e53fbbff40`

최종 release APK를 새로 설치해 실행하는 검증은 이번 빌드 점검에 포함하지 않았다. 개발 중 에뮬레이터에서 확인한 범위와 구분한다. APK 무결성은 [`apk/SHA256SUMS`](../apk/SHA256SUMS)로 확인한다.

```bash
cd apk
sha256sum -c SHA256SUMS
```

## 재현 범위

- 개발 중에는 에뮬레이터의 차량 신호와 디버그 시나리오를 이용해 흐름을 확인했다.
- 디버그의 `DemoReceiver`는 경로·톨게이트·도착 등 시나리오 신호를 넣기 위한 도구이며 release APK에는 포함하지 않는다.
- 이번 제출본의 단위 테스트·release 빌드·서명·Manifest 검사는 실제 차량 시험을 뜻하지 않는다.
- 실제 차량은 이번 공모전 심사의 필수 조건이 아니다. Gleo Interface도 참가팀은 별도 승인 없이 사용할 수 있다. [운영국 안내](https://appdevchallenge.auto-hmg.io/notice/c0a1a000-2026-4923-8000-000000000008)
