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

    testOptions {
        // The vendored Ultralytics helpers touch android.graphics statics (Paint, Color) on class init;
        // JVM unit tests of their pure math need those stubs to return defaults instead of throwing.
        unitTests.isReturnDefaultValues = true
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

    // Vendored Ultralytics pose core (com.ultralytics.yolo): LiteRT CompiledModel + the GPU accelerator.
    // Same version for both (catalog); never org.tensorflow:* (AGENTS rule 6).
    implementation(libs.litert)
    implementation(libs.litert.gpu)

    testImplementation(libs.junit4)
    testImplementation(libs.truth)
}
