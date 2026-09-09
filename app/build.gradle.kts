import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("kotlin-kapt")
}

android {
    namespace = "com.senkiro.translateapp"
    compileSdk = 34

    // local.properties에 GOOGLE_TRANSLATE_API_KEY / UPDATE_CHECK_PAT를 넣으면 로컬 빌드에
    // 반영된다. 이 repo는 public이라 release.yml(CI)은 두 값을 절대 주입하지 않는다 —
    // 누구나 다운로드해 디컴파일할 수 있는 공개 APK에 키를 넣으면 그대로 유출되기
    // 때문이다(자세한 내용은 release.yml, README.md 참고). 로컬에서 직접 빌드해 이
    // 키들을 채운 APK를 만들었다면, 그 APK 자체는 절대 다른 사람과 공유하지 않는다
    // (BuildConfig에 평문으로 박히므로 공유 즉시 키가 유출된다).
    // GOOGLE_TRANSLATE_API_KEY가 없으면 앱은 자동으로 ML Kit 온디바이스 번역으로
    // 폴백하고(GoogleTranslateEngine 참고), UPDATE_CHECK_PAT가 없으면 업데이트 확인
    // API 호출 시 인증 헤더 없이 요청한다(public repo는 인증 없이도 호출 가능).
    val localProps = Properties().apply {
        val file = rootProject.file("local.properties")
        if (file.exists()) file.inputStream().use { load(it) }
    }
    val googleTranslateApiKey = localProps.getProperty("GOOGLE_TRANSLATE_API_KEY", "")
    val githubUpdatePat = localProps.getProperty("UPDATE_CHECK_PAT", "")
    // 둘 다 선택 사항 — 번역 결과를 문맥과 함께 다듬어주는 LLM 후처리용 (ClaudePostProcessor /
    // GptPostProcessor). 없으면 후처리 없이 1차 번역(ML Kit/Cloud Translation) 결과를 그대로 쓴다.
    val anthropicApiKey = localProps.getProperty("ANTHROPIC_API_KEY", "")
    val openAiApiKey = localProps.getProperty("OPENAI_API_KEY", "")

    defaultConfig {
        applicationId = "com.senkiro.translateapp"
        minSdk = 26
        targetSdk = 34
        versionCode = 24
        versionName = "24.0"
        buildConfigField("String", "GOOGLE_TRANSLATE_API_KEY", "\"$googleTranslateApiKey\"")
        buildConfigField("String", "GITHUB_UPDATE_PAT", "\"$githubUpdatePat\"")
        buildConfigField("String", "ANTHROPIC_API_KEY", "\"$anthropicApiKey\"")
        buildConfigField("String", "OPENAI_API_KEY", "\"$openAiApiKey\"")
    }

    // CI(GitHub Actions)에서 환경변수로 keystore 정보를 주입한다.
    // 로컬에 이 환경변수들이 없으면 release는 서명되지 않은 채로 빌드된다(로컬 테스트용).
    val releaseKeystorePath = System.getenv("RELEASE_KEYSTORE_PATH")
    val releaseKeystorePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
    val releaseKeyAlias = System.getenv("RELEASE_KEY_ALIAS")
    val releaseKeyPassword = System.getenv("RELEASE_KEY_PASSWORD")

    signingConfigs {
        if (releaseKeystorePath != null) {
            create("release") {
                storeFile = file(releaseKeystorePath)
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (releaseKeystorePath != null) {
                signingConfig = signingConfigs.getByName("release")
            }
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
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // 네트워킹 (GitHub Releases API, Google Cloud Translation API 호출용)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // 코루틴
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")

    // ML Kit 온디바이스 번역
    implementation("com.google.mlkit:translate:17.0.3")

    // Room (로컬 캐싱)
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")

    // Lifecycle / ViewModel
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.2")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
