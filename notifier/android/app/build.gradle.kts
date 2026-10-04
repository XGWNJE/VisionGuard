import java.io.File
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
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

val notificationVersion = repositoryRoot.resolve("VERSION").readText().trim()
val versionParts = notificationVersion.split('.').map(String::toInt)
val notificationVersionCode = versionParts[0] * 1000 + versionParts[1] * 100 + versionParts[2]

android {
    sourceSets.getByName("main").res.srcDir(repositoryRoot.resolve("android-shared/src/main/res"))
    sourceSets.getByName("main").assets.srcDir(repositoryRoot.resolve("android-shared/src/main/assets"))
    sourceSets.getByName("main").java.srcDir(repositoryRoot.resolve("android-shared/src/main/java"))
    sourceSets.getByName("test").java.srcDir(repositoryRoot.resolve("android-shared/src/test/java"))
    namespace = "com.xgwnje.visionguard.notifier"
    compileSdk = 35 // Android 编译 SDK 版本

    defaultConfig {
        applicationId = "com.xgwnje.visionguard.notifier"
        minSdk = 26 // 最低支持的 SDK 版本
        targetSdk = 35 // 目标 SDK 版本
        versionCode = notificationVersionCode
        versionName = notificationVersion // 应用版本名

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" // 测试运行器
        vectorDrawables {
            useSupportLibrary = true // 启用对矢量图的支持库
        }
    }

    signingConfigs {
        create("release") {
            if (hasReleaseKeystore) {
                storeFile = releaseStoreFile
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true // 启用代码压缩（移除未使用的代码）
            isShrinkResources = true // 启用资源压缩（移除未使用的资源）
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro" // Proguard 规则文件
            )
            if (hasReleaseKeystore) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11 // Java 源代码兼容性版本
        targetCompatibility = JavaVersion.VERSION_11 // Java 目标代码兼容性版本
    }
    kotlinOptions {
        jvmTarget = "11" // Kotlin 编译到 JVM 的目标版本
    }
    buildFeatures {
        buildConfig = true
        compose = true // *** 启用 Jetpack Compose ***
    }

    packaging {
        resources {
            // 排除特定路径下的资源文件，以避免打包冲突
            excludes += "/META-INF/{AL2.0,LGPL2.1,LICENSE.md,LICENSE-notice.md}"
        }
    }
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Android 核心 KTX 库
    implementation("androidx.core:core-ktx:1.12.0")
    // AppCompat 库，提供向后兼容的 Material Design 组件
    implementation("androidx.appcompat:appcompat:1.6.1")
    // Material Design 组件库 (这是用于传统 View 系统的 Material 组件库)
    implementation("com.google.android.material:material:1.11.0")
    // ConstraintLayout 库 (如果传统 View 系统布局中使用)
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // AndroidX Lifecycle 库 (LiveData 和 ViewModel)
    implementation(libs.androidx.lifecycle.livedata.ktx)  // LiveData KTX 扩展
    implementation(libs.androidx.lifecycle.viewmodel.ktx) // ViewModel KTX 扩展

    // AndroidX Navigation 库 (如果使用基于 Fragment 的导航)
    implementation(libs.androidx.navigation.fragment.ktx) // Navigation Fragment KTX 扩展
    implementation(libs.androidx.navigation.ui.ktx)       // Navigation UI KTX 扩展

    // LocalBroadcastManager (用于应用内广播，但请注意它已被废弃)
    implementation("androidx.localbroadcastmanager:localbroadcastmanager:1.1.0")


    // *** Jetpack Compose 依赖 ***

    // Compose BOM (Bill of Materials) - 推荐使用，它能统一管理所有 Compose 相关库的版本，确保兼容性
    implementation(platform("androidx.compose:compose-bom:2024.02.00")) // 请查阅官方文档获取与您环境最匹配的最新稳定版

    // Compose UI 核心库
    implementation("androidx.compose.ui:ui")
    // Compose 图形处理库
    implementation("androidx.compose.ui:ui-graphics")
    // Compose 预览工具支持 (用于 Android Studio 中的预览)
    implementation("androidx.compose.ui:ui-tooling-preview")
    // Compose Foundation (提供 Compose 的基础构建块)
    implementation("androidx.compose.foundation:foundation")

    // Compose Material Design 2 (M2) 组件库
    implementation("androidx.compose.material:material")

    // Compose Material Design 3 (M3) 组件库
    implementation("androidx.compose.material3:material3")

    // Activity 与 Compose 的集成库
    implementation("androidx.activity:activity-compose:1.8.2") // 保持版本与BOM推荐或最新稳定版一致
    // Navigation 与 Compose 的集成库
    implementation("androidx.navigation:navigation-compose:2.7.7") // 保持版本与BOM推荐或最新稳定版一致

    // ViewModel 与 Compose 的集成库
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose") // 版本由 BOM 控制

    // LiveData 与 Compose 的集成库 (如果您需要在 Compose 中观察 LiveData)
    implementation("androidx.compose.runtime:runtime-livedata")

    // Google Accompanist 库 - Flow Layout (用于FlowRow)
    implementation("com.google.accompanist:accompanist-flowlayout:0.32.0")


    // *** 测试依赖 ***
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")

    // Compose UI 测试依赖
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.02.00")) // 为测试也使用BOM
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    // Compose UI 调试工具 (例如 Layout Inspector)
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // 其他您项目原有的依赖...
}
