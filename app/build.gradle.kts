plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// V9-A: 签名凭据全部走环境变量；未配置时 release 产出未签名包，配置后重新构建即自动签名
val gigiStorePassword = System.getenv("GIGI_STORE_PASSWORD")
val gigiSigningConfigured = !gigiStorePassword.isNullOrEmpty()

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
            storePassword = gigiStorePassword ?: ""
            keyAlias = System.getenv("GIGI_KEY_ALIAS") ?: "gigi_key"
            keyPassword = System.getenv("GIGI_KEY_PASSWORD") ?: ""
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // 环境变量未配置时不挂签名，避免空密码导致构建失败
            if (gigiSigningConfigured) {
                signingConfig = signingConfigs.getByName("release")
            }
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
