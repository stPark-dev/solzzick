import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.zzick"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.zzick"
        minSdk = 29
        targetSdk = 35
        versionCode = 11
        versionName = "0.4.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        manifestPlaceholders["appLabel"] = "솔찍"
    }
    // 릴리스 서명: keystore.properties(로컬 전용, 저장소에 넣지 않음). 없으면 릴리스 서명 없이 빌드된다.
    val keystoreProps = rootProject.file("keystore.properties").takeIf { it.exists() }?.let { f ->
        Properties().apply { f.inputStream().use(::load) }
    }
    signingConfigs {
        if (keystoreProps != null) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        // 디버그는 패키지를 분리해 정식판과 한 폰에 같이 깔린다(테스트하려고 정식판을 지울 필요 없음)
        debug {
            applicationIdSuffix = ".debug"
            manifestPlaceholders["appLabel"] = "솔찍 dev"
        }
        release {
            signingConfig = signingConfigs.findByName("release")
            // MediaPipe가 JNI·리플렉션을 써서 난독화(R8)는 보류 — 켜려면 keep 규칙 검증 필요
            isMinifyEnabled = false
            // 지인 배포용: 요즘 폰 대부분인 64비트 ARM만 넣어 용량을 줄인다
            ndk { abiFilters += "arm64-v8a" }
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
    }
    androidResources {
        noCompress += listOf("task", "tflite")
    }
}

dependencies {
    implementation(project(":core"))
    implementation("com.google.mediapipe:tasks-vision:0.10.20")
    implementation("androidx.media3:media3-transformer:1.5.1")
    implementation("androidx.media3:media3-effect:1.5.1")
    implementation("androidx.media3:media3-common:1.5.1")
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.12.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
