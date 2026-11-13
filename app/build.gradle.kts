plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "kr.meter29"
    compileSdk = 35
    useLibrary("android.car")

    defaultConfig {
        applicationId = "kr.meter29"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
        // Pleos Connect 실차(arm64)와 에뮬레이터(arm64 맥, x86_64 윈도우)만 지원 — 구형 ABI 제외로 APK 축소
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }
    // dex·네이티브 라이브러리를 압축해 넣는다(GitHub 웹 업로드 25MB 한도 안으로)
    packaging {
        jniLibs { useLegacyPackaging = true }
        dex { useLegacyPackaging = true }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // 공모전 심사용: 별도 키 없이 설치되도록 디버그 키로 서명
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    implementation("ai.pleos.playground:Vehicle:2.3.2")
    implementation("ai.pleos.playground:NaviHelper:3.0.5")
    implementation("ai.pleos.playground:TextToSpeech:3.3.1")
    implementation("ai.pleos.playground:FusedLocation:1.1.3")
    testImplementation("junit:junit:4.13.2")
}
