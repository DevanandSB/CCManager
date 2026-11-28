import java.io.File
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

fun loadDotEnv(file: File): Map<String, String> {
    if (!file.exists()) return emptyMap()
    val map = LinkedHashMap<String, String>()
    file.readLines().forEach { rawLine ->
        val line = rawLine.trim()
        if (line.isEmpty() || line.startsWith("#")) return@forEach
        val cleaned = line.removePrefix("export ").trim()
        val eq = cleaned.indexOf('=')
        if (eq <= 0) return@forEach
        val k = cleaned.substring(0, eq).trim()
        var v = cleaned.substring(eq + 1).trim()
        if ((v.startsWith("\"") && v.endsWith("\"") && v.length >= 2) ||
            (v.startsWith("'") && v.endsWith("'") && v.length >= 2)
        ) {
            v = v.substring(1, v.length - 1)
        }
        if (k.isNotEmpty()) map[k] = v
    }
    return map
}

val dotEnv = loadDotEnv(rootProject.file(".env"))
val keyProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun secretProp(envKey: String, legacyKey: String): String? =
    dotEnv[envKey]?.takeIf { it.isNotBlank() }
        ?: System.getenv(envKey)?.takeIf { it.isNotBlank() }
        ?: keyProps.getProperty(legacyKey)?.takeIf { it.isNotBlank() }

// versionCode counts commits, so every build from a newer commit installs as an update.
// CI checks out full history for this; without git it falls back to 1.
val commitCount: Int = runCatching {
    providers.exec { commandLine("git", "rev-list", "--count", "HEAD") }.standardOutput.asText.get().trim().toInt()
}.getOrDefault(1)

android {
    namespace = "com.devanandsb.ccmanager"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.devanandsb.ccmanager"
        minSdk = 26
        targetSdk = 36
        versionCode = commitCount
        versionName = "1.0.0" // bump by hand for releases
        androidResources { localeFilters += "en" }
    }

    signingConfigs {
        val storeFilePath = secretProp("RELEASE_STORE_FILE", "storeFile")
        val storePass = secretProp("RELEASE_STORE_PASSWORD", "storePassword")
        val alias = secretProp("RELEASE_KEY_ALIAS", "keyAlias")
        val keyPass = secretProp("RELEASE_KEY_PASSWORD", "keyPassword")
        if (storeFilePath != null && storePass != null && alias != null && keyPass != null) {
            create("release") {
                storeFile = rootProject.file(storeFilePath)
                storePassword = storePass
                keyAlias = alias
                keyPassword = keyPass
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.findByName("release")
            // Every phone from the last several years is arm64; this drops ~3 copies of SQLCipher.
            ndk { abiFilters += "arm64-v8a" }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    buildFeatures { compose = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

    lint { checkReleaseBuilds = false }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}", "/META-INF/*.version", "/kotlin/**", "/*.txt", "/DebugProbesKt.bin",
                "/META-INF/LICENSE.md", "/META-INF/NOTICE.md", "/META-INF/LICENSE", "/META-INF/NOTICE",
                // BouncyCastle's post-quantum tables (~3 MB); only PDFBox uses BC, for classic PDF encryption.
                "/org/bouncycastle/pqc/**",
            )
        }
    }
}

ksp { arg("room.schemaLocation", "$projectDir/schemas") }

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.core:core-ktx:1.17.0")
    // Installs the baseline profile (src/main/baseline-prof.txt) so startup and the stack are precompiled.
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.navigation:navigation-compose:2.9.0")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")

    implementation("androidx.room:room-runtime:2.7.1")
    implementation("androidx.room:room-ktx:2.7.1")
    ksp("androidx.room:room-compiler:2.7.1")
    implementation("net.zetetic:sqlcipher-android:4.6.1@aar")
    implementation("androidx.sqlite:sqlite-ktx:2.5.1")

    // v1.1 statement ingestion
    implementation("com.sun.mail:android-mail:1.6.7")
    implementation("com.sun.mail:android-activation:1.6.7")
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")

    // On-device camera & unbundled offline ML Kit OCR (Play Services pre-fetches OCR model on install).
    implementation("androidx.camera:camera-camera2:1.4.2")
    implementation("androidx.camera:camera-lifecycle:1.4.2")
    implementation("androidx.camera:camera-view:1.4.2")
    implementation("com.google.android.gms:play-services-mlkit-text-recognition:19.0.2")

    testImplementation("junit:junit:4.13.2")
    // Desktop PDFBox (same 2.0.27 base as the Android port) so StatementProbe can read real PDFs on the JVM.
    testImplementation("org.apache.pdfbox:pdfbox:2.0.27")
}
