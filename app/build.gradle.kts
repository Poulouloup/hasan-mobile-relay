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

    packaging {
        jniLibs {
            // sherpa-onnx et onnxruntime-android embarquent chacun libonnxruntime.so.
            // On garde celle d'onnxruntime-android (binding Java compatible openwakeword)
            // et on laisse pickFirsts résoudre le conflit. Sherpa-ONNX fonctionne car il
            // appelle ONNX Runtime via l'API C standard, compatible avec les deux builds.
            pickFirsts += listOf(
                "lib/arm64-v8a/libonnxruntime.so",
                "lib/armeabi-v7a/libonnxruntime.so",
                "lib/x86_64/libonnxruntime.so",
                "lib/x86/libonnxruntime.so"
            )
        }
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

    // ONNX Runtime — nécessaire pour openwakeword (binding Java + libonnxruntime4j_jni.so).
    // sherpa-onnx embarque sa propre libonnxruntime.so (API C) incompatible avec le binding
    // Java → on garde la version standard et on exclut celle de sherpa-onnx via pickFirsts.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.18.0")

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

    // Wake word (openwakeword-android-kt)
    implementation("xyz.rementia:openwakeword:0.1.5")

    // Sherpa-ONNX — TTS Piper offline
    // Exclut onnxruntime-android de sherpa-onnx : sa build custom de libonnxruntime.so
    // n'exporte pas OrtGetApiBase, ce qui casse le binding Java utilisé par openwakeword.
    implementation("com.github.k2-fsa:sherpa-onnx:v1.12.39") {
        exclude(group = "com.microsoft.onnxruntime")
    }
}
