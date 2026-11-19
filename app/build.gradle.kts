plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "dev.scooph"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.scooph.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
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
    }

    testOptions {
        // Robolectric 需要 AGP 生成合并后的 manifest / 资源，才能在 JVM 单测里
        // 起 Android 运行时（Room 的 in-memory 数据库依赖它）。
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            // 本机沙箱的 HOME 只读：Robolectric 的 android-all 缓存、Maven 本地仓库
            // 与下载锁都放到仓库内已被 gitignore 的 .gradle-home/ 下。
            val sandboxHome = rootProject.layout.projectDirectory.dir(".gradle-home").asFile
            val sandboxTmp = sandboxHome.resolve("tmp").apply { mkdirs() }
            it.systemProperty("user.home", sandboxHome.absolutePath)
            it.systemProperty("maven.repo.local", sandboxHome.resolve(".m2/repository").absolutePath)
            // Robolectric 会把 sqlite4java 原生库解压到 java.io.tmpdir；系统临时目录
            // 在沙箱里不可写，一并改到仓库内。
            it.systemProperty("java.io.tmpdir", sandboxTmp.absolutePath)
        }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.kotlinx.coroutines.android)

    // Go 引擎：gomobile bind 产出的 AAR，随 APK 一起打包 lib/*/libgojni.so。
    implementation(files("../engine/build/scooph-engine.aar"))

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)

    debugImplementation(libs.androidx.compose.ui.tooling)
}
