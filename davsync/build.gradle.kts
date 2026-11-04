// The sync glue for Datebook and Connections: accounts, typed sign-in, discovery and the CalDAV /
// CardDAV sync adapters, adapted from DAVx5's core/ (bitfireAT, GPL-3.0-or-later). Ours.
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.wanderwildwood.davsync"
    compileSdk = 36
    defaultConfig {
        minSdk = 25  // below 26 so D8 desugars java.time (see README: custom time zones)
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
kotlin { jvmToolchain(17) }
dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")
    api(project(":dav4jvm"))
    api(project(":synctools"))
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.annotation:annotation:1.9.1")
    implementation("com.google.guava:guava:33.7.2-android")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("io.ktor:ktor-client-okhttp:3.6.1")
    // ktor 3.6.1 asks for OkHttp 5.5.1, which needs compileSdk 37; held at 5.1.0 for the apps' 36.
    implementation("com.squareup.okhttp3:okhttp") { version { strictly("5.1.0") } }
    implementation("com.squareup.okhttp3:okhttp-android") { version { strictly("5.1.0") } }
    implementation("io.ktor:ktor-client-auth:3.6.1")
    implementation("io.ktor:ktor-client-encoding:3.6.1")
    compileOnly("com.github.spotbugs:spotbugs-annotations:4.10.5")
}
