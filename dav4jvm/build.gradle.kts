// dav4jvm 5.0.0 (bitfireAT, MPL-2.0), sources unchanged; this build file is ours.
plugins {
    id("org.jetbrains.kotlin.jvm")
}
kotlin { jvmToolchain(17) }
dependencies {
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    api("io.ktor:ktor-client-core:3.6.1")
    api("com.github.spotbugs:spotbugs-annotations:4.10.5")
    // On Android the platform has its own XmlPullParser; xpp3 is for the JVM tests only.
    compileOnly("org.ogce:xpp3:1.1.6")
    testImplementation("org.ogce:xpp3:1.1.6")
    implementation("com.google.guava:guava:33.7.2-android")
    implementation("io.ktor:ktor-client-auth:3.6.1")
    implementation("io.ktor:ktor-client-encoding:3.6.1")
}
