import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// 정산 엔진과 도메인 모델. 안드로이드에 의존하지 않는 순수 Kotlin이라 테스트가 빠르다.
plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
