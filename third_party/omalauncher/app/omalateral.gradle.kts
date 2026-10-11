plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "dev.omalauncher"
    compileSdk = 37
    ndkVersion = "30.0.14904198"
    defaultConfig {
        minSdk = 29
        buildConfigField("String", "VERSION_NAME", "\"0.4.0-omalateral\"")
        ndk { abiFilters += "arm64-v8a" }
    }
    buildFeatures { compose = true; buildConfig = true }
    sourceSets { getByName("main") {
        manifest.srcFile("../../../integration/omalauncher/src/main/AndroidManifest.xml")
    } }
    externalNativeBuild { cmake {
        path = file("src/main/cpp/CMakeLists.txt")
        version = "4.1.2"
    } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
dependencies {
    api(platform("androidx.compose:compose-bom:2025.11.00"))
    api("androidx.activity:activity-compose:1.11.0")
    api("androidx.compose.runtime:runtime")
    api("androidx.compose.foundation:foundation")
    api("androidx.compose.ui:ui")
    implementation("org.apache.commons:commons-compress:1.28.0")
    implementation("com.caverock:androidsvg-aar:1.4")
    implementation("com.google.zxing:core:3.5.4")
    api(project(":owl"))
}
