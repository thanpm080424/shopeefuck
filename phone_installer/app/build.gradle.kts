plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val googleServicesFile = rootProject.file("../android/app/google-services.json")
if (!googleServicesFile.isFile) {
    throw GradleException("Missing driver Firebase config: ${googleServicesFile.absolutePath}")
}
val firebaseConfig = groovy.json.JsonSlurper().parse(googleServicesFile) as Map<*, *>
val firebaseProjectId = (firebaseConfig["project_info"] as Map<*, *>)["project_id"] as String
val firebaseClient = (firebaseConfig["client"] as List<*>).first() as Map<*, *>
val firebaseApiKeyEntry = (firebaseClient["api_key"] as List<*>).first() as Map<*, *>
val firebaseApiKey = firebaseApiKeyEntry["current_key"] as String

val releaseKeystore = System.getenv("HKPICK_SIGNING_KEYSTORE")
val releaseAlias = System.getenv("HKPICK_SIGNING_ALIAS")
val releasePassword = System.getenv("HKPICK_SIGNING_PASSWORD")
val releaseRequested = gradle.startParameter.taskNames.any { it.contains("release", ignoreCase = true) }
val hasReleaseSigning = listOf(releaseKeystore, releaseAlias, releasePassword).all { !it.isNullOrBlank() }

if (releaseRequested && !hasReleaseSigning) {
    throw GradleException("Release signing environment is missing. Use tools/build_phone_installer.ps1.")
}

android {
    namespace = "com.hkpick.phoneinstaller"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.hkpick.phoneinstaller"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
        buildConfigField("String", "FIREBASE_PROJECT_ID", "\"$firebaseProjectId\"")
        buildConfigField("String", "FIREBASE_API_KEY", "\"$firebaseApiKey\"")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    androidResources {
        noCompress += "apk"
    }

    buildFeatures { buildConfig = true }

    packaging {
        resources {
            excludes += setOf("META-INF/LICENSE.md", "META-INF/NOTICE.md")
        }
    }

    signingConfigs {
        create("release") {
            if (hasReleaseSigning) {
                storeFile = file(releaseKeystore!!)
                storePassword = releasePassword
                keyAlias = releaseAlias
                keyPassword = releasePassword
            }
        }
    }

    buildTypes {
        release {
            signingConfig = if (hasReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
            isMinifyEnabled = false
        }
    }
}

dependencies {
    implementation("com.github.rhythmcache:adb-kt:1.0.13")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
