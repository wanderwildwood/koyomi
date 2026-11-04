// synctools from davx5-ose (bitfireAT, GPL-3.0-or-later), sources unchanged; this build file is ours.
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "at.bitfire.synctools"
    compileSdk = 36
    defaultConfig {
        minSdk = 25  // below 26 so D8 desugars java.time (see README: custom time zones)
        buildConfigField("String", "version_ical4j", "\"4.4.0\"")
        consumerProguardFiles("consumer-rules.pro")
        // Upstream's own instrumented tests, run on an API 31 emulator (the Kompakt's level).
        testInstrumentationRunner = "at.bitfire.synctools.LoggingTestRunner"
    }
    buildFeatures { buildConfig = true }
    sourceSets["main"].java.srcDirs("opentasks-contract/src/main/java")
    sourceSets["androidTest"].java.srcDirs("src/testFixtures/kotlin")
    packaging { resources { excludes += listOf("META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/*.md") } }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
kotlin { jvmToolchain(17) }
dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")
    implementation("androidx.annotation:annotation:1.9.1")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("com.google.guava:guava:33.7.2-android")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    api("org.mnode.ical4j:ical4j:4.4.0")
    implementation("org.slf4j:slf4j-jdk14:2.0.20")
    compileOnly("com.github.spotbugs:spotbugs-annotations:4.10.5")
    api("com.googlecode.ez-vcard:ez-vcard:0.12.2")
    implementation("commons-codec:commons-codec") { version { strictly("1.17.1") } }
    implementation("org.apache.commons:commons-lang3") { version { strictly("3.15.0") } }
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test:rules:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("junit:junit:4.13.2")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    androidTestImplementation("io.mockk:mockk-android:1.14.11")
    androidTestImplementation("org.jetbrains.kotlin:kotlin-test:2.2.21")
}
