import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// 서명 파일과 키는 저장소 밖에 둔다. 지정하지 않으면 release APK는 서명하지 않는다.
val signingPropertiesPath = providers.gradleProperty("nbbang.signingProperties")
    .orElse(providers.environmentVariable("NBBANG_SIGNING_PROPERTIES"))
    .orNull
val signingPropertiesFile = signingPropertiesPath?.let { rootProject.file(it).canonicalFile }
val releaseSigningProperties = signingPropertiesFile?.let { propertiesFile ->
    if (!propertiesFile.isFile) throw GradleException("Release signing properties file does not exist.")
    if (propertiesFile.toPath().startsWith(rootProject.projectDir.canonicalFile.toPath())) {
        throw GradleException("Release signing properties must be outside the repository.")
    }
    Properties().apply {
        propertiesFile.reader(Charsets.UTF_8).use { load(it) }
        listOf("storeFile", "storePassword", "keyAlias", "keyPassword").forEach { name ->
            if (getProperty(name).isNullOrBlank()) {
                throw GradleException("Release signing properties must include $name.")
            }
        }
    }
}
val releaseKeystore = releaseSigningProperties?.let { properties ->
    signingPropertiesFile!!.parentFile.resolve(properties.getProperty("storeFile")).canonicalFile.also { keystore ->
        if (!keystore.isFile) throw GradleException("Release signing keystore does not exist.")
        if (keystore.toPath().startsWith(rootProject.projectDir.canonicalFile.toPath())) {
            throw GradleException("Release signing keystore must be outside the repository.")
        }
    }
}

android {
    namespace = "com.nbbang"
    compileSdk = 34
    useLibrary("android.car")

    defaultConfig {
        applicationId = "com.nbbang"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        // 동승자가 휴대폰으로 여는 청구서 페이지(web/pay.html을 올린 주소). 비어 있으면 QR에 금액 글만 담는다.
        val payUrl = (project.findProperty("nbbang.payUrl") as String?).orEmpty()
        val escapedPayUrl = payUrl.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "\\r")
        buildConfigField("String", "PAY_URL", "\"$escapedPayUrl\"")

        // F-C07 오피넷 유가 API 인증키(저장소에 커밋하지 않지만 APK에는 포함된다). local.properties의 nbbang.opinetKey, -Pnbbang.opinetKey, 환경 변수 OPINET_KEY 순서로 찾는다.
        // 커밋하지 않는다. 없으면 공시 유가 조회를 끄고 설정 단가를 쓴다(CI 빌드도 키 없이 된다).
        val localProps = Properties().apply {
            rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
        }
        val opinetKey = (localProps.getProperty("nbbang.opinetKey") ?: project.findProperty("nbbang.opinetKey") as String? ?: System.getenv("OPINET_KEY"))
            .orEmpty().trim().filter { it.isLetterOrDigit() }
        buildConfigField("String", "OPINET_KEY", "\"$opinetKey\"")
    }

    signingConfigs {
        releaseSigningProperties?.let { properties ->
            create("submission") {
                storeFile = releaseKeystore
                storePassword = properties.getProperty("storePassword")
                keyAlias = properties.getProperty("keyAlias")
                keyPassword = properties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isDebuggable = false
            signingConfig = signingConfigs.findByName("submission")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(project(":core"))

    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // 정산 QR. 바코드 그리기만 쓴다(Apache 2.0).
    implementation("com.google.zxing:core:3.5.3")
    // 앱 시작 인트로(팀 디자인 로티 파일 res/raw/intro.json). Apache 2.0.
    implementation("com.airbnb.android:lottie-compose:6.4.1")

    implementation("ai.pleos.playground:Vehicle:2.3.2")
    implementation("ai.pleos.playground:NaviHelper:3.0.5")
    implementation("ai.pleos.playground:TextToSpeech:3.3.1")
    implementation("ai.pleos.playground:FusedLocation:1.1.3")

    testImplementation("junit:junit:4.13.2")
}
