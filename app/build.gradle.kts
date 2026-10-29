plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.hamdi.tiktokcleaner"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.hamdi.tiktokcleaner"
        minSdk = 26
        targetSdk = 35
        versionCode = 20
        versionName = "2.0.7-gemini"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
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
    // Zero-bloat architecture: pure Android SDK + Kotlin stdlib, zero network permissions.
    testImplementation("junit:junit:4.13.2")
}
