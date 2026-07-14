pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // com.github.gkonovalov.android-vad:silero — barge-in VAD (étape 6)
        maven("https://jitpack.io")
    }
}

rootProject.name = "HasanV1"
include(":app")
