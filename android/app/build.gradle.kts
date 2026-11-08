import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val signingFile = file(providers.environmentVariable("PDFCRAFT_SIGNING_PROPERTIES")
    .getOrElse("${System.getProperty("user.home")}/.local/share/pdfcraft/signing.properties"))
val releaseSecrets = Properties().apply { if (signingFile.isFile) signingFile.inputStream().use { load(it) } }

val nativeRoot = providers.environmentVariable("PDFCRAFT_NATIVE_DIR")
    .getOrElse(rootProject.layout.buildDirectory.dir("native").get().asFile.absolutePath)
val bundledFonts = layout.buildDirectory.dir("generated/fonts")
val launcherResources = layout.buildDirectory.dir("generated/launcher")
val prepareLauncher by tasks.registering(Sync::class) {
    from(rootProject.file("../app-icon.png")) {
        into("drawable-nodpi")
        rename { "app_icon.png" }
    }
    into(launcherResources)
    doFirst { check(rootProject.file("../app-icon.png").isFile) { "Required launcher icon is missing" } }
}
val prepareFonts by tasks.registering(Sync::class) {
    from(rootProject.file("../assets/fonts")) {
        include("Inter-Regular.ttf", "OFL-Inter.txt")
        into("fonts")
    }
    into(bundledFonts)
    doFirst {
        check(rootProject.file("../assets/fonts/Inter-Regular.ttf").isFile) { "Required Inter font is missing" }
    }
}
val checkNative by tasks.registering {
    doLast {
        check(file("$nativeRoot/jniLibs/arm64-v8a/libpdfcraft_android_bridge.so").isFile) {
            "Rust library missing. Follow android/README.md to build the bridge first."
        }
        check(file("$nativeRoot/kotlin/org/pdfcraft/android/engine/pdfcraft_android_bridge.kt").isFile) {
            "Generated Kotlin bindings missing. Run pdfcraft-bindgen for the exact .so."
        }
    }
}

android {
    namespace = "org.pdfcraft.android"
    compileSdk = 36
    defaultConfig {
        applicationId = "org.pdfcraft.android"
        minSdk = 26
        targetSdk = 36
        versionCode = 8
        versionName = "0.8.0"
        ndk { abiFilters += "arm64-v8a" }
    }
    signingConfigs {
        create("pdfcraftRelease") {
            if (signingFile.isFile) {
                storeFile = file(releaseSecrets.getProperty("storeFile"))
                storePassword = releaseSecrets.getProperty("storePassword")
                keyAlias = releaseSecrets.getProperty("keyAlias")
                keyPassword = releaseSecrets.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        getByName("release") {
            isDebuggable = false
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("pdfcraftRelease")
        }
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    sourceSets.getByName("main") {
        java.srcDir("$nativeRoot/kotlin")
        jniLibs.srcDir("$nativeRoot/jniLibs")
        assets.srcDir(bundledFonts)
        res.srcDir(launcherResources)
    }
    packaging { jniLibs.useLegacyPackaging = false }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
tasks.named("preBuild") { dependsOn(prepareFonts, prepareLauncher, checkNative) }
tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    doFirst { check(signingFile.isFile) { "Release signing properties are missing: $signingFile" } }
}

dependencies {
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.ui:ui:1.11.4")
    implementation("androidx.compose.foundation:foundation:1.11.4")
    implementation("androidx.compose.material3:material3:1.4.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.9.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("net.java.dev.jna:jna:5.17.0@aar")
    testImplementation("junit:junit:4.13.2")
}
