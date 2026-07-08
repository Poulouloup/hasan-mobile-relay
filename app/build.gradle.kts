import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    id("org.jetbrains.kotlin.kapt")
    id("org.owasp.dependencycheck")
}

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) load(f.inputStream())
}

dependencyCheck {
    nvd.apiKey = localProps.getProperty("nvdApiKey") ?: System.getenv("NVD_API_KEY") ?: ""
    analyzers.ossIndex.enabled = false
}

android {
    namespace = "com.hasan.v1"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.hasan.v1"
        minSdk = 29
        targetSdk = 35
        versionCode = 2
        versionName = "1.2"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.lifecycle.viewmodel.ktx)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)

    // ONNX Runtime — utilisé par openwakeword pour l'inférence wake word
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.17.0")

    // Fragment KTX
    implementation("androidx.fragment:fragment-ktx:1.8.5")

    // Room Database
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")

    // EncryptedSharedPreferences
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Markwon — rendu Markdown dans les bulles Hasan
    implementation("io.noties.markwon:core:4.6.2")
    implementation("io.noties.markwon:ext-strikethrough:4.6.2")
    implementation("io.noties.markwon:ext-tables:4.6.2")
    implementation("io.noties.markwon:linkify:4.6.2")

    // Wake word (openwakeword-android-kt)
    implementation("xyz.rementia:openwakeword:0.1.5")

    // TTS Edge — wrapper Java du endpoint non officiel "Lire à voix haute" de Microsoft Edge,
    // gratuit et sans clé API. Non garanti stable (endpoint non documenté, peut casser sans
    // préavis si Microsoft change son protocole) — HassanTtsManager bascule automatiquement
    // sur le TTS natif Android si une synthèse échoue.
    implementation("io.github.whitemagic2014:tts-edge-java:1.3.3")
}
