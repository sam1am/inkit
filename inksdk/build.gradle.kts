plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.inksdk.ink"
    compileSdk = 34

    defaultConfig {
        minSdk = 26
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
    // HiddenApiBypass is required on Android 14+ to reach vendor classes
    // (xrz HandwrittenClient, Onyx EpdController). Host apps still need to
    // call HiddenApiBypass.addHiddenApiExemptions("L") in Application.onCreate.
    api("org.lsposed.hiddenapibypass:hiddenapibypass:4.3")
}
