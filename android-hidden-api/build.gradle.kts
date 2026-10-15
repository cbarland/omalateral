import java.util.Properties

plugins { `java-library` }
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
val properties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.isFile }?.inputStream()?.use(::load)
}
val sdkDirectory = properties.getProperty("sdk.dir") ?: System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
// Use the same installed SDK generation as :app; no extra API-35 SDK download.
val platformJar = listOf("android-37.0", "android-37")
    .map { file("$sdkDirectory/platforms/$it/android.jar") }.firstOrNull { it.isFile }
    ?: throw GradleException("Install Android SDK platform 37 and configure sdk.dir or ANDROID_HOME")
dependencies {
    compileOnly(files(platformJar))
}
