plugins {
    id("com.android.application")
    id("kotlin-android")
    // The Flutter Gradle Plugin must be applied after the Android and Kotlin Gradle plugins.
    id("dev.flutter.flutter-gradle-plugin")
    id("com.google.gms.google-services")
}

val signingKeystorePath = System.getenv("HKPICK_SIGNING_KEYSTORE")
val signingAlias = System.getenv("HKPICK_SIGNING_ALIAS")
val signingPassword = System.getenv("HKPICK_SIGNING_PASSWORD")
val releaseTaskRequested = gradle.startParameter.taskNames.any { it.contains("release", ignoreCase = true) }
val hasReleaseSigning = listOf(signingKeystorePath, signingAlias, signingPassword).all { !it.isNullOrBlank() }
if (releaseTaskRequested && !hasReleaseSigning) {
    throw GradleException("Release signing environment is missing. Build through tools/build_sales_apks.ps1.")
}

android {
    namespace = "com.example.hkpickclone"
    compileSdk = flutter.compileSdkVersion
    ndkVersion = flutter.ndkVersion

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = JavaVersion.VERSION_17.toString()
    }

    defaultConfig {
        // TODO: Specify your own unique Application ID (https://developer.android.com/studio/build/application-id.html).
        applicationId = "com.samsung.health.addon"
        minSdk = 30
        targetSdk = flutter.targetSdkVersion
        versionCode = flutter.versionCode
        versionName = flutter.versionName
    }

    signingConfigs {
        create("release") {
            if (hasReleaseSigning) {
                storeFile = file(signingKeystorePath!!)
                storePassword = signingPassword
                keyAlias = signingAlias
                keyPassword = signingPassword
            } else {
                initWith(getByName("debug"))
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
}

dependencies {
    implementation("com.google.mlkit:text-recognition:16.0.1")
}

flutter {
    source = "../.."
}
