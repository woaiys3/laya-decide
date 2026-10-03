import java.io.File
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// ---------------------------------------------------------------------------
// Release 签名
//
// 口令优先从环境变量取（CI 用），否则读 app/keystore.properties（本地用）。
// 两处都没有就只有 debug 包 —— 不会因为缺签名把构建搞挂。
//
// ⚠️ 上架前务必换成你自己的 keystore。keystore 丢了就再也无法更新同一个 App。
// ---------------------------------------------------------------------------
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

fun secret(envKey: String, propKey: String): String? =
    System.getenv(envKey) ?: keystoreProps.getProperty(propKey)

// 注意：这里必须用 rootProject.file() 解析，不能用 file()。
// 后者在 android {} 块里是相对「模块目录」（app/app/）解析的，
// 而 keystore 放在根项目目录（app/laya-decide.jks），会差一层。
val releaseStoreFile = secret("LAYA_KEYSTORE", "storeFile")
    ?.let { rootProject.file(it) }
    ?.let { File(it.absolutePath) }
val hasReleaseSigning = releaseStoreFile?.exists() == true

logger.lifecycle(
    "[签名] keystore 配置: " +
        (if (keystorePropsFile.exists()) "已读到 ${keystorePropsFile.name}" else "未找到 ${keystorePropsFile.name}") +
        " | storeFile=${releaseStoreFile ?: "null"}" +
        " | 存在=${releaseStoreFile?.exists() ?: false}" +
        " | release 签名=${if (hasReleaseSigning) "启用" else "禁用（只会产出未签名包）"}",
)

android {
    namespace = "com.laya.decide"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.laya.decide"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = releaseStoreFile
                storePassword = secret("LAYA_KEYSTORE_PASSWORD", "storePassword")
                keyAlias = secret("LAYA_KEY_ALIAS", "keyAlias")
                keyPassword = secret("LAYA_KEY_PASSWORD", "keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // 演示项目：release 不混淆，方便你直接看崩溃栈。
            // 真要上架再打开，并配好 keep 规则。
            isMinifyEnabled = false
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    /*
     * 按 ABI 拆包。
     *
     * ONNX Runtime 的原生库很大（x86_64 约 37MB、arm64 约 31MB），
     * 四种 ABI 全打进去会让 APK 变成 133MB。拆开之后每种架构只有 35-42MB。
     *
     * 装哪个：
     *   arm64-v8a    现代手机（2017 年之后基本都是）—— 发这个
     *   armeabi-v7a  老手机
     *   x86_64       模拟器
     *
     * universal 包仍然会生成一个，方便"我不知道该装哪个"的情况。
     */
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
        // AGP 8 起 BuildConfig 默认不生成，但调试入口要用 BuildConfig.DEBUG
        // 把 adb 驱动限定在 debug 构建里。
        buildConfig = true
    }
}

dependencies {
    // 依赖刻意保持精简：只用 androidx，不引 Material / Compose / Room。
    // 少一个依赖就少一处构建失败的可能，这个项目的界面复杂度用不着它们。
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // 端侧推理。版本与 server 端 onnxruntime-node 对齐（1.30.0），
    // 保证同一份 ONNX 图在两端行为一致。
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.30.0")

    testImplementation("junit:junit:4.13.2")
}
