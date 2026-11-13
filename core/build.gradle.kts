import org.gradle.api.tasks.PathSensitivity
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

// core is PURE KOTLIN JVM (ARCHITECTURE §3): android.* cannot compile here. Model types,
// geometry, tracking, TrackerConfig and replay I/O live here and are unit-tested on the JVM.
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.truth)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // Tests read the committed model files through ../model (working directory = this module). Declaring
    // the directory as an input re-runs them when a model file changes, instead of reusing a cached result.
    inputs
        .dir(rootProject.file("model"))
        .withPathSensitivity(PathSensitivity.RELATIVE)
        .withPropertyName("committedModelFiles")
}
