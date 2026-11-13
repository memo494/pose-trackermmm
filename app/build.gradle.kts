import groovy.json.JsonSlurper
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// app (ARCHITECTURE §3): Compose screens, overlay, diagnostics, DI. Thin Android layer over core.
// versionCode grows forever from the CI run number so every APK installs over the previous one.
val ciRunNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "0").toInt()
val gitShaShort = (System.getenv("GITHUB_SHA") ?: "local").take(7)

// Packages the committed model files as assets (M0b T4b). Verifies every file listed in
// model/model_info.json against its sha256 first, so a damaged or hand-edited model fails the build with
// a clear message instead of failing on the phone. Only the listed .tflite files and model_info.json are
// staged: no goldens, no fixture. model/ itself is read-only.
abstract class StageModelAssetsTask : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val modelDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Inject
    abstract val fileSystem: FileSystemOperations

    @TaskAction
    fun stage() {
        val infoName = "model_info.json"
        val infoFile = modelDir.file(infoName).get().asFile
        if (!infoFile.isFile) throw GradleException("$infoName is missing in ${infoFile.parent}. ${fix()}")
        val root = JsonSlurper().parse(infoFile) as? Map<*, *> ?: throw GradleException("$infoName is not an object")
        val sizes = root["sizes"] as? Map<*, *> ?: throw GradleException("$infoName has no \"sizes\" object")
        if (sizes.isEmpty()) throw GradleException("$infoName lists no model in \"sizes\"")
        val modelFiles =
            sizes.map { (size, entry) ->
                val fields = entry as? Map<*, *> ?: throw GradleException("$infoName: size $size is not an object")
                val name = fields["file"] as? String ?: throw GradleException("$infoName: size $size has no \"file\"")
                val expected =
                    fields["sha256"] as? String ?: throw GradleException("$infoName: size $size has no \"sha256\"")
                verify(name, expected)
                name
            }
        val staged = modelFiles + infoName
        fileSystem.sync {
            from(modelDir)
            include(staged)
            includeEmptyDirs = false
            into(outputDir)
        }
        logger.lifecycle("Staged model assets: ${staged.joinToString(", ")}")
    }

    private fun verify(
        name: String,
        expected: String,
    ) {
        if (name.isEmpty() || name.startsWith(".") || name.contains("/") || name.contains("\\")) {
            throw GradleException("model file name \"$name\" is not a bare file name. ${fix()}")
        }
        val file = File(modelDir.get().asFile, name)
        val actual = if (file.isFile) sha256Of(file) else "missing"
        if (actual != expected) {
            throw GradleException("model file $name is damaged: expected sha256 $expected but found $actual. ${fix()}")
        }
    }

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(65_536)
        file.inputStream().use { input ->
            var count = input.read(buffer)
            while (count >= 0) {
                digest.update(buffer, 0, count)
                count = input.read(buffer)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun fix(): String = "Restore model/ from git or re-run the export-model workflow; never edit model/ by hand."
}

android {
    namespace = "com.memo494.posetracker"
    compileSdk =
        libs.versions.compileSdk
            .get()
            .toInt()

    defaultConfig {
        applicationId = "com.memo494.posetracker"
        minSdk =
            libs.versions.minSdk
                .get()
                .toInt()
        targetSdk =
            libs.versions.targetSdk
                .get()
                .toInt()
        versionCode = ciRunNumber + 1
        versionName = "0.1.$ciRunNumber"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "GIT_SHA", "\"$gitShaShort\"")
    }

    signingConfigs {
        // Debug-only key, committed on purpose (ARCHITECTURE §10): every APK installs over the
        // previous one with no secrets setup. A real release key goes in CI Secrets later.
        getByName("debug") {
            storeFile = rootProject.file("signing/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debug")
        }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug") // debug-signed until a release key exists
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// One staging task per variant (stage<Variant>ModelAssets); AGP assigns outputDir and packages it as assets.
androidComponents {
    onVariants { variant ->
        val variantName = variant.name.replaceFirstChar { it.uppercase() }
        val stageModelAssets =
            tasks.register<StageModelAssetsTask>("stage${variantName}ModelAssets") {
                modelDir.set(rootProject.layout.projectDirectory.dir("model"))
            }
        variant.sources.assets?.addGeneratedSourceDirectory(stageModelAssets, StageModelAssetsTask::outputDir)
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":detector"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit4)
    testImplementation(libs.truth)
}
