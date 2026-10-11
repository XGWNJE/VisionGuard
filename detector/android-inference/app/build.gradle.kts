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
        ?: sharedSigningProperties.getProperty(name)
        ?: providers.gradleProperty(name).orNull
        ?: System.getenv(name)
        ?: ""
}

fun quotedBuildConfigString(value: String): String {
    return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}

val visionguardServerUrl = secretProperty("VISIONGUARD_SERVER_URL").ifBlank { "https://visionguard.xgwnje.cn" }

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

// 版本号只以根 VERSION 为准，避免第三套需要同步的硬编码副本。
val inferenceVersion = repositoryRoot.resolve("VERSION").readText().trim()
val inferenceVersionParts = inferenceVersion.split('.').map(String::toInt)
val inferenceVersionCode = inferenceVersionParts[0] * 1000 + inferenceVersionParts[1] * 100 + inferenceVersionParts[2]

android {
    sourceSets.getByName("main").res.srcDir(repositoryRoot.resolve("android-shared/src/main/res"))
    sourceSets.getByName("main").assets.srcDir(repositoryRoot.resolve("android-shared/src/main/assets"))
    sourceSets.getByName("main").kotlin.srcDir(repositoryRoot.resolve("android-shared/src/main/java"))
    sourceSets.getByName("test").kotlin.srcDir(repositoryRoot.resolve("android-shared/src/test/java"))
    namespace = "com.xgwnje.visionguard.inference"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.xgwnje.visionguard.inference"
        minSdk = 28
        targetSdk = 36
        versionCode = inferenceVersionCode
        versionName = inferenceVersion

        buildConfigField("String", "SERVER_URL", quotedBuildConfigString(visionguardServerUrl))
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
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1,LICENSE.md,LICENSE-notice.md,DEPENDENCIES,NOTICE,NOTICE.txt,index.list}"
        }
    }
    lint {
        // `InvalidFragmentVersionForActivityResult` 是给「用了 Fragment 又用 ActivityResult API」的
        // 工程准备的：那类工程若 Fragment 版本低于 1.3.0，回调会在重建后被丢。本端是纯 Compose +
        // ComponentActivity，依赖里没有任何 Fragment（见下方依赖块），不存在该风险。
        // 这条 warning 会被 lintVitalRelease 升格成 error，直接挡死 assembleRelease，因此显式关掉。
        disable += "InvalidFragmentVersionForActivityResult"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material3)
    testImplementation(libs.junit)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.okhttp)
    implementation(libs.gson)
    implementation(libs.onnxruntime.android)
    // 换运行时的候选：LiteRT 有独立的 GPU delegate 与 NPU 加速器，是 NNAPI 之外公开可得的
    // 移动加速路线。先与 ONNX Runtime 并存用于真机对比测量，选定后再删掉另一条。
    implementation(libs.litert)
    implementation(libs.litert.api)
    // litert-gpu 带 libLiteRtClGlAccelerator.so：没有它，运行时会报本机不支持 GPU 加速器。
    implementation(libs.litert.gpu)
}
