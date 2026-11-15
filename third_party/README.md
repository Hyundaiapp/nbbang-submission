# 제3자 구성요소

공개 소스와 제출 APK에서 사용하는 주요 구성요소다. 원 프로젝트의 저작권 표시와 라이선스는 해당 저작권자에게 있다. 아래 목록은 직접 선언한 주요 의존성이며 전이 의존성은 Gradle 의존성 보고서에서 확인할 수 있다.

| 구성요소 | 사용 | 출처·라이선스 |
|---|---|---|
| Asta Sans | 앱 글꼴 | [42dot/Asta-Sans](https://github.com/42dot/Asta-Sans), [SIL OFL 1.1 및 원 저작권 표시](asta-sans/OFL.txt) |
| AndroidX / Jetpack Compose | 앱 UI·수명주기 | [AndroidX](https://android.googlesource.com/platform/frameworks/support/), Apache 2.0 |
| Kotlin / Kotlin Coroutines | 언어 런타임·비동기 처리 | [Kotlin](https://github.com/JetBrains/kotlin), [Coroutines](https://github.com/Kotlin/kotlinx.coroutines), Apache 2.0 |
| ZXing core 3.5.3 | QR 생성 | [ZXing](https://github.com/zxing/zxing), Apache 2.0 |
| Lottie Compose 6.4.1 | 앱 인트로 재생 | [Lottie Android](https://github.com/airbnb/lottie-android), Apache 2.0 |
| JUnit 4.13.2 | 단위 테스트 전용 | [JUnit 4](https://github.com/junit-team/junit4), EPL 1.0 |
| Gradle Wrapper | 빌드 실행 | [Gradle](https://github.com/gradle/gradle), Apache 2.0 |
| Pleos Connect SDK | 차량·내비·음성·위치 연동 | [Pleos Playground](https://developers.pleos.ai/playground), 제공자의 이용 조건 적용 |

라이선스 원문: [Apache License 2.0](APACHE-2.0.txt), [Eclipse Public License 1.0](EPL-1.0.html). Pleos SDK 바이너리를 소스 저장소에 별도로 복제하지 않으며 Gradle에서 공식 Maven 저장소로부터 받는다. 공개된 SDK의 공모전 사용 범위는 [운영국 안내](https://appdevchallenge.auto-hmg.io/notice/c0a1a000-2026-4923-8000-000000000008)를 따른다.

의존성 확인:

```bash
./gradlew :app:dependencies --configuration releaseRuntimeClasspath
./gradlew :core:dependencies --configuration testRuntimeClasspath
```
