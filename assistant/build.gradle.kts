import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.net.URI

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// CI 가 빌드 번호를 넘겨주면 그 값으로 버전을 올려 휴대폰에서 덮어쓰기 설치가 되게 한다.
val buildNumber = System.getenv("VERSION_CODE")?.toIntOrNull() ?: 1
val signingStore: String? = System.getenv("SIGNING_STORE_FILE")?.takeIf { it.isNotBlank() }

// 휴대폰 안 한국어 음성 인식 엔진 sherpa-onnx (Apache-2.0). 50MB 라 저장소에 넣지 않고 처음 빌드할 때 공식 릴리스에서 받는다.
val sherpaVersion = "1.13.8"
val sherpaAar = file("libs/sherpa-onnx-$sherpaVersion.aar")
if (!sherpaAar.exists() || sherpaAar.length() < 1_000_000) {
    sherpaAar.parentFile.mkdirs()
    val tmp = File(sherpaAar.path + ".part")
    URI("https://github.com/k2-fsa/sherpa-onnx/releases/download/v$sherpaVersion/sherpa-onnx-$sherpaVersion.aar")
        .toURL().openStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
    check(tmp.length() > 1_000_000) { "sherpa-onnx AAR 을 받지 못했습니다" }
    tmp.renameTo(sherpaAar)
}

android {
    namespace = "io.github.jgg113.gyeot"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.jgg113.gyeot"
        minSdk = 26
        targetSdk = 35
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
        // 갤럭시는 모두 64비트 ARM. 다른 CPU 용 라이브러리를 빼서 APK 를 줄인다.
        ndk { abiFilters += "arm64-v8a" }
    }

    signingConfigs {
        if (signingStore != null) {
            create("release") {
                storeFile = file(signingStore)
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName(if (signingStore != null) "release" else "debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "/META-INF/DEPENDENCIES",
            "/META-INF/INDEX.LIST",
            "/META-INF/*.kotlin_module",
            "/META-INF/versions/**",
        )
        // 음성 인식·AI 엔진 라이브러리를 압축해 APK 를 줄인다 (설치할 때 풀린다).
        jniLibs.useLegacyPackaging = true
    }

    lint {
        checkReleaseBuilds = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.anthropic.java)
    implementation(libs.okhttp)
    implementation(files(sherpaAar))
    implementation(libs.mediapipe.tasks.genai)
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test.junit)
}
