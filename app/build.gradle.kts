import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.jetbrains.kotlin.konan.properties.Properties
import java.io.FileInputStream
import java.net.URI
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.detekt)
    id("org.jetbrains.kotlin.plugin.compose") version libs.versions.kotlin.get()
}

// --- scrcpy-server (call recording) ---
// Downloaded and SHA-256 verified at build time, then bundled as an asset, as done by
// ShizuCallRecorder (kitsumed, GPL-3.0). scrcpy is Apache-2.0 (Genymobile).
// The version is pinned in libs.versions.toml and MUST stay 4.0 (see ScrcpyClient).
val scrcpyVersion = libs.versions.scrcpy.get()
val scrcpyServerUrl = "https://github.com/Genymobile/scrcpy/releases/download/v$scrcpyVersion/scrcpy-server-v$scrcpyVersion"
val scrcpyServerSha256 = "84924bd564a1eb6089c872c7521f968058977f91f5ff02514a8c74aff3210f3a"
val scrcpyServerAssetName = "scrcpy-server"
val scrcpyDownloadDir = layout.buildDirectory.dir("generated/scrcpy/assets")

abstract class DownloadAssetTask : DefaultTask() {
    @get:Input
    abstract val url: Property<String>

    @get:Input
    abstract val sha256: Property<String>

    @get:Input
    abstract val assetName: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun download() {
        val targetFile = outputDir.get().file(assetName.get()).asFile
        if (targetFile.exists() && calculateSha256(targetFile).equals(sha256.get(), ignoreCase = true)) {
            logger.info("${assetName.get()} is up to date.")
            return
        }

        targetFile.parentFile.mkdirs()
        logger.lifecycle("Downloading ${assetName.get()}...")
        URI(url.get()).toURL().openStream().use { input ->
            targetFile.outputStream().use { output -> input.copyTo(output) }
        }

        val actualHash = calculateSha256(targetFile)
        if (!actualHash.equals(sha256.get(), ignoreCase = true)) {
            targetFile.delete()
            throw GradleException("SHA-256 mismatch for ${assetName.get()}: expected ${sha256.get()} but got $actualHash")
        }
    }

    private fun calculateSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var bytesRead = input.read(buffer)
            while (bytesRead != -1) {
                digest.update(buffer, 0, bytesRead)
                bytesRead = input.read(buffer)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

val downloadScrcpyServer = tasks.register<DownloadAssetTask>("downloadScrcpyServer") {
    url.set(scrcpyServerUrl)
    sha256.set(scrcpyServerSha256)
    assetName.set(scrcpyServerAssetName)
    outputDir.set(scrcpyDownloadDir)
}
// --- end of scrcpy-server block ---

val keystorePropertiesFile: File = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
}

fun hasSigningVars(): Boolean {
    return providers.environmentVariable("SIGNING_KEY_ALIAS").orNull != null
            && providers.environmentVariable("SIGNING_KEY_PASSWORD").orNull != null
            && providers.environmentVariable("SIGNING_STORE_FILE").orNull != null
            && providers.environmentVariable("SIGNING_STORE_PASSWORD").orNull != null
}

base {
    val versionCode = project.property("VERSION_CODE").toString().toInt()
    archivesName = "phone-$versionCode"
}

android {
    compileSdk = project.libs.versions.app.build.compileSDKVersion.get().toInt()

    defaultConfig {
        applicationId = project.property("APP_ID").toString()
        minSdk = project.libs.versions.app.build.minimumSDK.get().toInt()
        targetSdk = project.libs.versions.app.build.targetSDK.get().toInt()
        versionName = project.property("VERSION_NAME").toString()
        versionCode = project.property("VERSION_CODE").toString().toInt()

        buildConfigField("String", "SCRCPY_VERSION", "\"$scrcpyVersion\"")
        buildConfigField("String", "SCRCPY_SERVER_SHA256", "\"$scrcpyServerSha256\"")
        buildConfigField("String", "SCRCPY_SERVER_ASSET_NAME", "\"$scrcpyServerAssetName\"")
    }

    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            register("release") {
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
            }
        } else if (hasSigningVars()) {
            register("release") {
                keyAlias = providers.environmentVariable("SIGNING_KEY_ALIAS").get()
                keyPassword = providers.environmentVariable("SIGNING_KEY_PASSWORD").get()
                storeFile = file(providers.environmentVariable("SIGNING_STORE_FILE").get())
                storePassword = providers.environmentVariable("SIGNING_STORE_PASSWORD").get()
            }
        } else {
            logger.warn("Warning: No signing config found. Build will be unsigned.")
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
        compose = true
        aidl = true
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (keystorePropertiesFile.exists() || hasSigningVars()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    flavorDimensions.add("variants")
    productFlavors {
        register("core")
        register("foss")
        register("gplay")
    }

    sourceSets {
        getByName("main").java.directories.add("src/main/kotlin")
    }

    compileOptions {
        val currentJavaVersionFromLibs =
            JavaVersion.valueOf(libs.versions.app.build.javaVersion.get())
        sourceCompatibility = currentJavaVersionFromLibs
        targetCompatibility = currentJavaVersionFromLibs
    }

    dependenciesInfo {
        includeInApk = false
    }

    androidResources {
        @Suppress("UnstableApiUsage")
        generateLocaleConfig = true
    }

    tasks.withType<KotlinCompile> {
        compilerOptions.jvmTarget.set(
            JvmTarget.fromTarget(project.libs.versions.app.build.kotlinJVMTarget.get())
        )
    }

    namespace = project.property("APP_ID").toString()

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = true
        warningsAsErrors = false
        baseline = file("lint-baseline.xml")
        lintConfig = rootProject.file("lint.xml")
    }

    bundle {
        language {
            enableSplit = false
        }
    }
}

// Bundle the downloaded scrcpy-server as a generated asset for every variant.
androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(
            downloadScrcpyServer,
            DownloadAssetTask::outputDir
        )
    }
}

detekt {
    baseline = file("detekt-baseline.xml")
    config.setFrom("$rootDir/detekt.yml")
    buildUponDefaultConfig = true
    allRules = false
}

dependencies {
    implementation(libs.fossify.commons)
    implementation(libs.ezvcard)
    implementation(libs.indicator.fast.scroll)
    implementation(libs.autofit.text.view)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.eventbus)
    implementation(libs.libphonenumber)
    implementation(libs.geocoder)
    implementation("com.google.zxing:core:3.5.3")
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    detektPlugins(libs.compose.detekt)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)

    // Jetpack Compose dependencies for custom About page compilation
    implementation(platform("androidx.compose:compose-bom:2024.04.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.runtime:runtime")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.ui:ui-viewbinding")
    implementation("androidx.activity:activity-compose:1.9.0")
}

