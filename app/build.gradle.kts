plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.voicepilot"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.voicepilot"
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        // Fixed debug key so new builds install over old ones. Personal sideloading only.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    packaging {
        resources.excludes += listOf(
            "/META-INF/{AL2.0,LGPL2.1,DEPENDENCIES,INDEX.LIST}",
            "/META-INF/LICENSE*", "/META-INF/NOTICE*",
            "/META-INF/versions/**",
        )
    }
}

dependencies {
    implementation("com.anthropic:anthropic-java:3.9.0")
    implementation("com.alphacephei:vosk-android:0.3.47@aar")
    implementation("net.java.dev.jna:jna:5.13.0@aar")
    testImplementation("junit:junit:4.13.2")
}
