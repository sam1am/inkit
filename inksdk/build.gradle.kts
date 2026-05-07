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

    packaging {
        // Onyx pen SDK and mmkv (transitive of onyxsdk-base) both ship the
        // same arm64-v8a libc++_shared.so. Pick one to avoid duplicate-file
        // packaging errors.
        jniLibs { pickFirsts += "**/libc++_shared.so" }
    }
}

configurations.all {
    // Onyx SDK pulls in old pre-AndroidX support libraries that clash with
    // the AndroidX deps the host app uses. Exclude them.
    exclude(group = "com.android.support", module = "support-compat")
    exclude(group = "com.android.support", module = "support-annotations")
    exclude(group = "com.android.support", module = "support-v4")
}

dependencies {
    // HiddenApiBypass is required on Android 14+ to reach vendor classes
    // (xrz HandwrittenClient, Onyx EpdController). Host apps still need to
    // call HiddenApiBypass.addHiddenApiExemptions("L") in Application.onCreate.
    api("org.lsposed.hiddenapibypass:hiddenapibypass:4.3")

    // Onyx Boox pen SDK — required by OnyxInkController. Classes load on any
    // device, but the vendor runtime is only present on Boox firmware; on
    // non-Onyx devices attach() throws and we fall back cleanly.
    api("com.onyx.android.sdk:onyxsdk-pen:1.5.2")
    api("com.onyx.android.sdk:onyxsdk-device:1.3.3")
}
