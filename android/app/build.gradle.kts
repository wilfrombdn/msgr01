import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release key lives in ../keystore (git-ignored). Back it up: losing it means
// updates can't install over the old app.
val keyProps = Properties().apply {
    val f = rootProject.file("keystore/keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.wilfrombdn.telegramnt"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.wilfrombdn.telegramnt"
        minSdk = 26
        targetSdk = 34
        versionCode = 43
        versionName = "5.5"
    }
    signingConfigs {
        create("release") {
            if (keyProps.isNotEmpty()) {
                storeFile = rootProject.file("keystore/release.jks")
                storePassword = keyProps.getProperty("password")
                keyAlias = "telegramnt"
                keyPassword = keyProps.getProperty("password")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName(if (keyProps.isNotEmpty()) "release" else "debug")
        }
    }
    ndkVersion = "27.3.13750724" // its strip tool shrinks libsignal's native library
    defaultConfig { ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") } } // phones only
    packaging {
        // libsignal ships desktop copies of its native code and a testing build; phones need neither
        resources { excludes += listOf("libsignal_jni*.so", "libsignal_jni*.dylib", "signal_jni*.dll") }
        // kmp-tor's resource-exec-tor ships the tor binary in jniLibs; it must be unpacked to the app's
        // nativeLibDir on install so it can be executed.
        jniLibs { useLegacyPackaging = true; excludes += listOf("**/libsignal_jni_testing.so") }
    }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true // libsignal uses newer Java APIs than Android 8 has
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            // libsignal unpacks a ~200 MB native library into the temp folder; keep that on disk, not in /tmp
            all { it.systemProperty("java.io.tmpdir", layout.buildDirectory.dir("tmp/jni").get().asFile.apply { mkdirs() }.path) }
        }
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    implementation("com.google.crypto.tink:tink-android:1.24.0")
    implementation("androidx.core:core:1.15.0")
    implementation("androidx.drawerlayout:drawerlayout:1.2.0") // Telegram-style slide-out side menu
    implementation("androidx.activity:activity:1.9.3") // ComponentActivity (LifecycleOwner) for the camera
    // CameraX: in-app camera (photo, video, pinch/slide zoom)
    implementation("androidx.camera:camera-camera2:1.4.1")
    implementation("androidx.camera:camera-lifecycle:1.4.1")
    implementation("androidx.camera:camera-view:1.4.1")
    implementation("androidx.camera:camera-video:1.4.1")
    implementation("com.google.zxing:core:3.5.3") // QR codes: show yours, scan a friend's
    implementation("com.google.firebase:firebase-messaging:24.1.0") // empty wake-up pings so calls ring a closed app
    implementation("androidx.fragment:fragment:1.8.5") // Firebase pulls an old one in; the camera screens' permission prompts need >= 1.3
    implementation("org.signal:libsignal-android:0.105.0") // Signal's encryption (Double Ratchet, sender keys) // FileProvider, to open attachments in other apps
    // Embedded Tor: the app reaches the relay's .onion through Tor, so there's no middleman that sees IPs.
    implementation("io.matthewnelson.kmp-tor:runtime:2.6.0")
    implementation("io.matthewnelson.kmp-tor:resource-exec-tor:408.22.0") // bundles the tor binary per ABI
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.signal:libsignal-client:0.105.0") // same library with desktop native code, for tests
    testImplementation("org.json:json:20240303")
    testImplementation("org.robolectric:robolectric:4.14.1")
}
