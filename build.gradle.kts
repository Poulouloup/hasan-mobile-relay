plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    id("org.owasp.dependencycheck") version "10.0.4" apply false
}
