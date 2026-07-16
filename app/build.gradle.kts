import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val localSdkDir: String = Properties().apply {
    val propsFile = rootProject.file("local.properties")
    if (propsFile.exists()) {
        propsFile.inputStream().use { load(it) }
    }
}.getProperty("sdk.dir") ?: System.getenv("ANDROID_HOME") ?: ""

android {
    namespace = "com.subwave.radio"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.subwave.radio"
        minSdk = 24
        targetSdk = 35
        versionCode = 2
        versionName = "0.2"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")

    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")

    // Media3 / ExoPlayer + Android Auto session support
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-session:1.4.1")
    implementation("androidx.media3:media3-common:1.4.1")

    // Networking for iTunes metadata lookup
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Async image loading (artist artwork)
    implementation("io.coil-kt:coil-compose:2.6.0")

    // android.car platform stub, for detecting driving-state UX restrictions
    // on Automotive OS. Never packaged; the real classes come from the OS.
    compileOnly(files("$localSdkDir/platforms/android-35/optional/android.car.jar"))

    // Detects Android Auto (projection) vs Automotive OS (native) connection,
    // for phones where android.car has no CarUxRestrictionsManager to query.
    implementation("androidx.car.app:app:1.4.0")
}
