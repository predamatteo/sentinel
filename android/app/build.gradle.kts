import java.util.Properties
import java.io.FileInputStream

plugins {
    id("com.android.application")
    // START: FlutterFire Configuration
    id("com.google.gms.google-services")
    // END: FlutterFire Configuration
    id("kotlin-android")
    // Sprint Quality: KSP wires Room's annotation processor.
    id("com.google.devtools.ksp")
    // The Flutter Gradle Plugin must be applied after the Android and Kotlin Gradle plugins.
    id("dev.flutter.flutter-gradle-plugin")
}

// Load Safe Browsing API key from local.properties if available.
// The key is optional: when missing the provider returns a "not configured" reason.
val localProperties = Properties().apply {
    val localPropsFile = rootProject.file("local.properties")
    if (localPropsFile.exists()) {
        FileInputStream(localPropsFile).use { load(it) }
    }
}
val safeBrowsingApiKey: String = localProperties.getProperty("SAFE_BROWSING_API_KEY", "")

// Sprint Quality: optional release signing config. When `android/key.properties`
// exists we wire a real keystore for `flutter build apk --release`; otherwise
// we fall back to the debug signing config so that contributors without the
// keystore can still build a sideloadable APK.
val keystoreProperties = Properties().apply {
    val keystoreFile = rootProject.file("key.properties")
    if (keystoreFile.exists()) {
        FileInputStream(keystoreFile).use { load(it) }
    }
}
val hasReleaseKeystore: Boolean = keystoreProperties.getProperty("storeFile")?.isNotBlank() == true

android {
    namespace = "com.sentinel.app"
    // Sprint 2: compileSdk bumped to 36 because shared_preferences_android
    // requires it. targetSdk stays at 34 — runtime behaviour and the
    // 4 + 6 manifest declarations are still consistent with API 34.
    compileSdk = 36
    ndkVersion = flutter.ndkVersion

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = JavaVersion.VERSION_17.toString()
    }

    defaultConfig {
        applicationId = "com.sentinel.app"
        minSdk = 24
        targetSdk = 34
        versionCode = flutter.versionCode
        versionName = flutter.versionName

        // Expose the Safe Browsing key to native code via BuildConfig.
        buildConfigField("String", "SAFE_BROWSING_API_KEY", "\"$safeBrowsingApiKey\"")

        // Sprint Quality: Room schema export. Schemas are tracked in
        // version control so that future migrations can audit the
        // serialized form of every entity.
        ksp {
            arg("room.schemaLocation", "$projectDir/schemas")
            arg("room.incremental", "true")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // Sprint Quality: real release signing when key.properties is
            // present, debug signing otherwise. Documented in README.
            signingConfig = if (hasReleaseKeystore) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }

    packaging {
        resources {
            excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1")
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.browser:browser:1.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Sprint Quality: Room for per-day persistent stats. 2.8.4 is the
    // first stable line with full KSP2 + Kotlin 2.2 codegen support;
    // earlier 2.6.x emits Continuation<? super T> erasure mismatches.
    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    ksp("androidx.room:room-compiler:2.8.4")

    // JVM unit tests for pure-Kotlin layers (parsers, blocklist parsing).
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("androidx.room:room-testing:2.8.4")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("org.robolectric:robolectric:4.13")
}

flutter {
    source = "../.."
}
