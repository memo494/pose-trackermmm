plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.spotless)
}

// Spotless (ktlint) over every module's Kotlin and Gradle Kotlin DSL (ARCHITECTURE §2).
// `./gradlew spotlessCheck` runs in verify.sh and CI; `./gradlew spotlessApply` fixes formatting.
spotless {
    val ktlintVersion = libs.versions.ktlint.get()
    kotlin {
        target("**/*.kt")
        // Never reformat vendored content (skills, and the Ultralytics import landing in M0b).
        targetExclude("**/build/**", ".claude/skills/**", "third_party/**")
        ktlint(ktlintVersion)
    }
    kotlinGradle {
        target("**/*.gradle.kts")
        targetExclude("**/build/**", ".claude/skills/**", "third_party/**")
        ktlint(ktlintVersion)
    }
}
