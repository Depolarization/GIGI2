plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// release 签名口令只从环境变量取，不回退默认值：**仅当**本次请求了 release 产物时才校验并拦下。
// ⚠️ Windows 下用 setx 写的是用户级环境变量，只对「之后新启动」的进程生效——改完必须重启终端/Android Studio。
// 详见 docs/RELEASE-SIGNING.md。密码本身严禁写入任何入库文件。
//
// 为什么改成条件守卫（勿改回无条件抛）：V39-A1 棒实测——旧版在配置阶段无条件抛，挡死了
// debug/test/lint 等根本不用 release signingConfig 的任务（debug 走 Android 默认 debug keystore），
// 逼得排障者去 `reg query` 读注册表、把 keystore 口令明文打进会话日志。条件守卫后，
// 只有真正要打 release 包的任务才需要口令，其余棒可正常构建。
val requestedTasks = gradle.startParameter.taskNames
val needsReleaseSigning = requestedTasks.any { name ->
    name.contains("Release", ignoreCase = true) ||
        name.contains("Bundle", ignoreCase = true) ||
        name == "build" || name.endsWith(":build")
}
val gigiStorePassword = System.getenv("GIGI_STORE_PASSWORD")
val gigiKeyPassword = System.getenv("GIGI_KEY_PASSWORD")
if (needsReleaseSigning && (gigiStorePassword.isNullOrEmpty() || gigiKeyPassword.isNullOrEmpty())) {
    throw GradleException(
        "缺少 release 签名所需环境变量 GIGI_STORE_PASSWORD / GIGI_KEY_PASSWORD（或为空）。\n" +
            "修复（Windows 用户级，无需管理员，cmd 执行）：\n" +
            "    setx GIGI_STORE_PASSWORD \"<keystore 口令>\"\n" +
            "    setx GIGI_KEY_PASSWORD   \"<keystore 口令>\"\n" +
            "    setx GIGI_KEY_ALIAS      gigi_key\n" +
            "⚠️ setx 只对之后新启动的进程生效——设置完必须重启终端 / Android Studio 再构建。\n" +
            "详见 docs/RELEASE-SIGNING.md。禁止以空口令或默认口令产出未签名/错签名的 release 包。"
    )
}

android {
    namespace = "com.gigi.tcg"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.gigi.tcg"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
        // T11 取证用：instrumentation 注入凭据（仅 debug/androidTest 路径，不触业务逻辑）
        testInstrumentationRunner = "com.gigi.tcg.debug.DebugCredentialInjector"

        // V9-A: 域名/URL 抽离到 BuildConfig，业务代码不得再硬编码域名
        buildConfigField("String", "PASSPORT_BASE", "\"https://passport-api.miyoushe.com\"")
        buildConfigField("String", "RECORD_ORIGIN", "\"https://api-takumi-record.mihoyo.com\"")
        buildConfigField("String", "BADGE_LOGIN_URL", "\"https://api-takumi.mihoyo.com/common/badge/v1/login/account\"")
        buildConfigField("String", "EVENT_ORIGIN", "\"https://hk4e-api.mihoyo.com\"")
        buildConfigField("String", "RECORD_ORIGIN_2", "\"https://api-takumi-record.mihoyo.com\"")
        buildConfigField("String", "CONTENT_LIST_URL", "\"https://act-api-takumi-static.mihoyo.com/common/blackboard/ys_obc/v1/home/content/list?app_sn=ys_obc&channel_id=231\"")
        buildConfigField("String", "WIKI_ENTRY_URL_TEMPLATE", "\"https://act-api-takumi-static.mihoyo.com/hoyowiki/genshin/wapi/entry_page?app_sn=ys_obc&entry_page_id=ENTRY_PAGE_ID&lang=zh-cn\"")
        buildConfigField("String", "LOGIN_URL", "\"https://webstatic.mihoyo.com/ys/event/tcgmatch/index.html#/homePage\"")
        buildConfigField("String", "GITHUB_REPO", "\"Depolarization/GIGI2\"")
    }

    signingConfigs {
        create("release") {
            storeFile = file("../gigi_release.keystore")
            storePassword = gigiStorePassword
            keyAlias = System.getenv("GIGI_KEY_ALIAS") ?: "gigi_key"
            keyPassword = gigiKeyPassword
        }
    }

    buildTypes {
        debug {
            // 图标/资源取证用旁路：仅当显式传入 -PgigiIconDev 时，把 debug 包装成
            // 独立条目（com.gigi.tcg.icondev），以便与设备上已装的 release 包并存，
            // 不必卸载/清数据。平时构建完全不受影响。
            if (project.hasProperty("gigiIconDev")) {
                applicationIdSuffix = ".icondev"
                versionNameSuffix = "-icondev"
            }
            // 账号系统端到端测试旁路：仅当显式传入 -PgigiCookieDev 时，把 debug 包装成
            // 独立条目（com.gigi.tcg.cookiedev），与设备上已装的 release 包并存。
            // 配合 DebugCredentialInjector 的 raw_cookie 通道，做「只读 cookie、不走二维码」
            // 的完整登录链路测试（AuthManager.finalize → CredentialStore 落盘 → AppGate 收养）。
            if (project.hasProperty("gigiCookieDev")) {
                applicationIdSuffix = ".cookiedev"
                versionNameSuffix = "-cookiedev"
            }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.getByName("release")
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
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.navigation:navigation-compose:2.9.8")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose")
    implementation("androidx.lifecycle:lifecycle-runtime-compose")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("io.coil-kt:coil-compose:2.7.0")
    // V10-D: 卡面图鉴的卡面资源含 GIF（官方卡面动图），coil-compose 不含 GIF 解码器，
    // 缺失时 AppImage 的 AsyncImagePainter 走 State.Error，卡面显示为加载失败占位图。
    implementation("io.coil-kt:coil-gif:2.7.0")
    implementation("com.google.zxing:core:3.5.3")
    // V9-A: Theme.Material3.* 主题 parent 由 Material Components 库提供
    implementation("com.google.android.material:material:1.12.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-tooling-preview")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test")

    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("junit:junit:4.13.2")
}
