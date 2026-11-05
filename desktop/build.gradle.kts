import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.2.21"
    kotlin("plugin.compose") version "2.2.21"
    id("org.jetbrains.compose") version "1.9.0"
}

kotlin { jvmToolchain(21) }

// The chat engine is shared with the Android app: same files, same protocol, nothing to keep in sync.
val syncShared by tasks.registering(Sync::class) {
    from("../android/app/src/main/java/com/wilfrombdn/telegramnt") {
        include("Core.kt", "Crypto.kt", "Relay.kt", "Net.kt", "SignalStore.kt")
    }
    into(layout.buildDirectory.dir("shared/com/wilfrombdn/telegramnt"))
}
kotlin.sourceSets.main { kotlin.srcDir(layout.buildDirectory.dir("shared")) }
tasks.named("compileKotlin") { dependsOn(syncShared) }

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation("com.google.crypto.tink:tink:1.24.0")
    implementation("org.signal:libsignal-client:0.105.0")
    implementation("org.json:json:20240303")
    implementation("io.matthewnelson.kmp-tor:runtime:2.6.0")
    implementation("io.matthewnelson.kmp-tor:resource-exec-tor:409.5.0")
}

compose.desktop {
    application {
        mainClass = "tellem.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe, TargetFormat.Deb, TargetFormat.AppImage)
            packageName = "Tellem"
            packageVersion = "1.0.0"
            description = "Tellem for desktop"
            modules("java.naming", "java.sql", "jdk.unsupported", "java.net.http")
            windows { menuGroup = "Tellem"; shortcut = true; upgradeUuid = "7d2b1c5e-3f4a-4a64-9a55-2f6c8a0b1e11" }
            linux { shortcut = true; menuGroup = "Network"; debMaintainer = "tellem@localhost" }
        }
    }
}
