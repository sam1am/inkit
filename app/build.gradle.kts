plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.merrythieves.inkit"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.merrythieves.inkit"
        minSdk = 26
        targetSdk = 34
        versionCode = 10009
        versionName = "1.0.9"
    }

    // Release signing reads keystore path + credentials from env vars (CI) or
    // gradle properties (local dev — put them in ~/.gradle/gradle.properties,
    // which lives outside the repo and is never committed). Env vars win if
    // both are set. Local debug builds work without any of this — assembleRelease
    // is the only target that needs it.
    fun creds(name: String): String? =
        System.getenv(name) ?: (findProperty(name) as String?)

    signingConfigs {
        create("release") {
            val storePath = creds("KEYSTORE_PATH")
            if (!storePath.isNullOrBlank()) {
                storeFile = file(storePath)
                storePassword = creds("KEYSTORE_PASSWORD")
                keyAlias = creds("KEY_ALIAS")
                keyPassword = creds("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        getByName("release") {
            // Only attach the release signing config if a keystore is actually
            // configured; otherwise the AGP build fails before any task runs.
            if (!creds("KEYSTORE_PATH").isNullOrBlank()) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.activity:activity-ktx:1.8.2")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation(project(":inksdk"))
}