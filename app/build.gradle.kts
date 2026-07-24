import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
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
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        viewBinding = true
        compose = true
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

    // Jetpack Compose — migration progressive (étape 9, refonte UI) via ComposeView
    // dans les fragments existants ; le reste de l'app reste en Views/ViewBinding.
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.activity)
    debugImplementation(libs.compose.ui.tooling)

    // ONNX Runtime — utilisé par openwakeword pour l'inférence wake word
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.17.0")

    // Fragment KTX
    implementation("androidx.fragment:fragment-ktx:1.8.5")

    // Room Database
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")

    // Chiffrement de la base Room (finding #6 audit sécurité — historique de
    // conversation en clair sur disque). SupportFactory branché dans
    // HassanDatabase.getInstance(), migration du fichier plaintext existant
    // au premier lancement post-update — voir HassanDatabase.kt.
    implementation("net.zetetic:android-database-sqlcipher:4.5.4")
    implementation("androidx.sqlite:sqlite:2.4.0")

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

    // Barge-in VAD (Silero, DNN) — coupe le TTS quand l'utilisateur parle par-dessus.
    // Dépend de onnxruntime-android (déjà présent ci-dessus pour openwakeword).
    implementation("com.github.gkonovalov.android-vad:silero:2.0.8")

    // Lecture audio gapless (VoicePlayer) — remplace MediaPlayer dans EdgeTtsEngine
    // pour éliminer le micro-silence entre deux chunks de phrase synthétisés.
    // 1.9.4 (pas 1.10.x) : les versions 1.10+ exigent compileSdk 36, ce projet est en 35.
    implementation("androidx.media3:media3-exoplayer:1.9.4")

    // Scanner QR pour le pairing relay (étape 8/9).
    // 1.5.3 (pas 1.6.x) : la 1.6.x exige compileSdk 36 + AGP 8.9.1, ce projet est en 35 / AGP 8.7.0.
    implementation("androidx.camera:camera-camera2:1.5.3")
    implementation("androidx.camera:camera-lifecycle:1.5.3")
    implementation("androidx.camera:camera-view:1.5.3")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")

    // Authentification biométrique/PIN à l'activation du relay (actions téléphone sensibles).
    implementation("androidx.biometric:biometric:1.1.0")

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}
