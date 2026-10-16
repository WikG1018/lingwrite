// SPDX-FileCopyrightText: 2026 WikG and LingWrite contributors
// SPDX-License-Identifier: AGPL-3.0-only
import java.net.URI
import java.security.MessageDigest
import java.util.zip.ZipFile
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.serialization)
}

// ---- MiSans 字体：协议不允许随仓库再分发字体文件，构建时从小米官方地址下载 ----
// 依据：《MiSans 字体知识产权许可协议》https://hyperos.mi.com/font/zh/download 第 3 条（见 THIRD_PARTY_NOTICES.md）。
// 下载一次后缓存在 ~/.gradle/caches/lingwrite-misans/；也可设 LINGWRITE_MISANS_DIR 指向已有的 ttf 目录。
// 离线或 -Plingwrite.misans=off 时退回系统字体，构建照常通过。
val misansRes = layout.buildDirectory.dir("generated/misans/res").get().asFile
val misansFiles = mapOf(
    "MiSans-Regular.ttf" to ("misans_regular.ttf" to "9c120f0a849bc0aa5048daae2a3c0f6eecd828b5b33fce682a9622833f5feea6"),
    "MiSans-Medium.ttf" to ("misans_medium.ttf" to "b03e98374e971594b0b7a9706d0704241f76e1b88556cdda79c5039ef8a638d1"),
    "MiSans-Semibold.ttf" to ("misans_semibold.ttf" to "77c23f31ae124867778344970155a0c8d34a89897dedaab81aeee82ff00a4ce6"),
)
fun sha256Of(f: File): String = MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }
val hasMiSans: Boolean = run {
    val fontDir = misansRes.resolve("font").apply { mkdirs() }
    fun ok() = misansFiles.values.all { (n, h) -> fontDir.resolve(n).let { it.isFile && sha256Of(it) == h } }
    if (ok()) return@run true
    fontDir.listFiles()?.forEach { it.delete() }
    if ((project.findProperty("lingwrite.misans") as String?) == "off") return@run false
    val local = System.getenv("LINGWRITE_MISANS_DIR")?.let(::File)
    val cache = File(System.getProperty("user.home"), ".gradle/caches/lingwrite-misans").apply { mkdirs() }
    runCatching {
        val src: (String) -> File = if (local != null) { n -> local.resolve(n) } else {
            val zip = cache.resolve("MiSans.zip")
            if (!zip.isFile) {
                logger.lifecycle("LingWrite: 从小米官方下载 MiSans 字体包…")
                val tmp = cache.resolve("MiSans.zip.part")
                URI("https://hyperos.mi.com/font-download/MiSans.zip").toURL().openStream().use { i -> tmp.outputStream().use { i.copyTo(it) } }
                tmp.renameTo(zip)
            }
            val out = cache.resolve("ttf").apply { mkdirs() }
            ZipFile(zip).use { z ->
                misansFiles.keys.forEach { n -> z.getEntry("MiSans/ttf/$n")?.let { e -> z.getInputStream(e).use { i -> out.resolve(n).outputStream().use { i.copyTo(it) } } } }
            }
            ({ n: String -> out.resolve(n) })
        }
        misansFiles.forEach { (n, v) -> src(n).copyTo(fontDir.resolve(v.first), overwrite = true) }
    }.onFailure { logger.warn("LingWrite: MiSans 下载失败（${it.message}），使用系统字体。") }
    ok().also { if (!it) { fontDir.listFiles()?.forEach { f -> f.delete() }; logger.warn("LingWrite: MiSans 校验不通过，使用系统字体。") } }
}
if (!hasMiSans) misansRes.resolve("font").apply { mkdirs() }.let { d ->
    // 占位：保证 R.font.* 存在；HAS_MISANS=false 时代码不会加载它们
    listOf("misans_regular", "misans_medium", "misans_semibold").forEach { d.resolve("$it.xml").writeText("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<font-family xmlns:android=\"http://schemas.android.com/apk/res/android\" />\n") }
}

android {
    namespace = "com.lingwrite.app"
    compileSdk { version = release(37) }

    defaultConfig {
        applicationId = "com.lingwrite.app"
        minSdk = 31
        targetSdk = 37
        versionCode = 15
        versionName = "0.3.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("boolean", "HAS_MISANS", hasMiSans.toString())
    }
    // release 签名只从仓库外读取（仓库里永远不放密钥）：
    //   1) 环境变量 LINGWRITE_STORE_FILE / _STORE_PASSWORD / _KEY_ALIAS / _KEY_PASSWORD（CI 用）
    //   2) LINGWRITE_SIGNING_PROPS 或 -Plingwrite.signing=<signing.properties 路径>
    //   3) 默认 ~/.lingwrite/signing.properties
    // 都没有时 release 包退回 debug 签名，任何人 clone 下来都能构建（但不能覆盖升级官方版）。
    val signingFile = file(System.getenv("LINGWRITE_SIGNING_PROPS")
        ?: (project.findProperty("lingwrite.signing") as String?) ?: "${System.getProperty("user.home")}/.lingwrite/signing.properties")
    val signingProps = Properties().apply { signingFile.takeIf { it.isFile }?.inputStream()?.use { load(it) } }
    fun sign(key: String, env: String): String? = System.getenv(env)?.takeIf { it.isNotBlank() } ?: signingProps.getProperty(key)
    val releaseSigning = sign("storeFile", "LINGWRITE_STORE_FILE")?.let { store ->
        val f = signingFile.parentFile?.resolve(store)?.takeIf { it.isFile } ?: file(store)
        if (!f.isFile) null else signingConfigs.create("release") {
            storeFile = f; storePassword = sign("storePassword", "LINGWRITE_STORE_PASSWORD")
            keyAlias = sign("keyAlias", "LINGWRITE_KEY_ALIAS"); keyPassword = sign("keyPassword", "LINGWRITE_KEY_PASSWORD")
        }
    }
    if (releaseSigning == null) logger.lifecycle("LingWrite: 未找到 release 签名配置，release 包使用 debug 签名。")
    buildTypes {
        release {
            signingConfig = releaseSigning ?: signingConfigs.getByName("debug")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug { applicationIdSuffix = ".debug"; versionNameSuffix = "-debug" }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true; buildConfig = true }
    sourceSets {
        getByName("test").assets.srcDir("$projectDir/schemas")
        getByName("main").res.srcDir(misansRes)
    }
    androidResources {
        noCompress += listOf("ttf", "otf")
        // 只打包界面支持的语言，依赖库里其他语言的资源不进 APK
        localeFilters += listOf("zh-rCN", "en", "zh-rTW", "ja")
    }
    packaging { resources.excludes += listOf("/META-INF/{AL2.0,LGPL2.1}", "DebugProbesKt.bin") }
    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.reports.junitXml.required.set(true)
            it.systemProperty("lingwrite.shotDir", (project.findProperty("shotDir") as String?) ?: "/workspace/lingwrite-shots")
            it.systemProperty("robolectric.pixelCopyRenderMode", "hardware")
            it.systemProperty("roborazzi.test.record", "true")
            it.outputs.upToDateWhen { false }
            it.maxHeapSize = "3g"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.haze)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.androidx.datastore.preferences)
    debugImplementation(libs.androidx.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.room.testing)
    testImplementation(libs.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.test.manifest)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation("androidx.test:runner:1.6.2")
}

ksp { arg("room.schemaLocation", "$projectDir/schemas"); arg("room.generateKotlin", "true") }
