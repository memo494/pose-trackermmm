import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

// detector is an Android library (ARCHITECTURE §3): the PoseDetector interface and the adapter
// over the vendored Ultralytics code (LiteRtModel, PoseEstimator, utils) plus a Fake impl.
android {
    namespace = "com.memo494.posetracker.detector"
    compileSdk =
        libs.versions.compileSdk
            .get()
            .toInt()

    defaultConfig {
        minSdk =
            libs.versions.minSdk
                .get()
                .toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))

    testImplementation(libs.junit4)
    testImplementation(libs.truth)
}
