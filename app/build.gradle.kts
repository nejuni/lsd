// AGP 9 has Kotlin built in: no separate kotlin-android plugin and no kotlin { } block needed
// (the Kotlin jvmTarget follows compileOptions below).
plugins {
    id("com.android.application")
}

// Signing: CI passes these env vars. Locally (no env) the debug key is used.
val ksPath: String? = System.getenv("KEYSTORE_PATH")

android {
    namespace = "com.lsd.io"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.lsd.io"
        minSdk = 30
        targetSdk = 36
        versionCode = providers.gradleProperty("appVersionCode").orNull?.toInt() ?: 2
        versionName = providers.gradleProperty("appVersionName").orNull ?: "1.1"
    }

    // Also emit ABI-labelled packages for users who prefer separate downloads.
    // The app currently has no native libraries, so all three APKs contain the same code.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = true
        }
    }

    signingConfigs {
        if (ksPath != null) {
            create("ci") {
                storeFile = file(ksPath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (ksPath != null) signingConfigs.getByName("ci") else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("org.apache.commons:commons-compress:1.28.0")
}
