import java.io.File
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val keystoreProperties = Properties()
val keystoreFile = file("../keystore.properties")
if (keystoreFile.exists()) {
    keystoreProperties.load(keystoreFile.inputStream())
}

val repositoryRoot = rootProject.projectDir.parentFile.parentFile
val sharedSigningProperties = Properties()
val sharedSigningFile = repositoryRoot.resolve(".local/visionguard-release.env")
if (sharedSigningFile.exists()) {
    sharedSigningProperties.load(sharedSigningFile.inputStream())
}

val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localProperties.load(localPropertiesFile.inputStream())
}

fun secretProperty(name: String): String {
    return localProperties.getProperty(name)
        ?: providers.gradleProperty(name).orNull
        ?: System.getenv(name)
        ?: ""
}

fun quotedBuildConfigString(value: String): String {
    return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}

val visionguardApiKey = secretProperty("VISIONGUARD_API_KEY")

fun signingProperty(environmentName: String, legacyName: String): String {
    return System.getenv(environmentName)?.takeIf { it.isNotBlank() }
        ?: sharedSigningProperties.getProperty(environmentName)?.takeIf { it.isNotBlank() }
        ?: keystoreProperties.getProperty(legacyName).orEmpty()
}

fun resolveReleaseStoreFile(configuredPath: String): File? {
    if (configuredPath.isBlank()) return null
    val candidate = File(configuredPath)
    if (candidate.isAbsolute) return candidate
    return if (configuredPath.replace('\\', '/').startsWith(".local/")) {
        repositoryRoot.resolve(configuredPath)
    } else {
        rootProject.file(configuredPath)
    }
}

val releaseStoreFile = resolveReleaseStoreFile(
    signingProperty("VISIONGUARD_ANDROID_STORE_FILE", "storeFile")
)
val releaseStorePassword = signingProperty("VISIONGUARD_ANDROID_STORE_PASSWORD", "storePassword")
val releaseKeyAlias = signingProperty("VISIONGUARD_ANDROID_KEY_ALIAS", "keyAlias")
val releaseKeyPassword = signingProperty("VISIONGUARD_ANDROID_KEY_PASSWORD", "keyPassword")
val hasReleaseKeystore = releaseStoreFile?.isFile == true &&
    listOf(releaseStorePassword, releaseKeyAlias, releaseKeyPassword)
        .all { it.isNotBlank() && !it.startsWith("REPLACE_WITH") }
val allowUnsignedRelease = providers.gradleProperty("VISIONGUARD_ALLOW_UNSIGNED_RELEASE")
    .orNull
    ?.equals("true", ignoreCase = true) == true
val releasePackagingRequested = gradle.startParameter.taskNames
    .map { it.substringAfterLast(':') }
    .any { taskName ->
        taskName.equals("build", ignoreCase = true) ||
            taskName.equals("assemble", ignoreCase = true) ||
            taskName.equals("bundle", ignoreCase = true) ||
            (
                taskName.contains("Release", ignoreCase = true) &&
                    listOf("assemble", "bundle", "package", "install", "publish")
                        .any { taskName.startsWith(it, ignoreCase = true) }
                )
    }

if (releasePackagingRequested && !hasReleaseKeystore && !allowUnsignedRelease) {
    throw GradleException(
        "Signed Android Release is required. Run scripts/initialize-android-signing.ps1 " +
            "or explicitly use -PVISIONGUARD_ALLOW_UNSIGNED_RELEASE=true for compile-only validation."
    )
}

android {
    namespace = "com.xgwnje.visionguard"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.xgwnje.visionguard"
        minSdk = 28
        targetSdk = 36
        versionCode = 4404
        versionName = "4.4.4"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "API_KEY", quotedBuildConfigString(visionguardApiKey))

        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = releaseStoreFile
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = false
            }
        }
    }

    buildTypes {
        release {
            if (hasReleaseKeystore) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }

}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.onnxruntime)
    implementation(libs.okhttp)
    implementation(libs.gson)
    implementation(libs.datastore.preferences)
    implementation(libs.lifecycle.service)
}
