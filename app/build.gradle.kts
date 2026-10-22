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
        versionCode = 7
        versionName = "1.6"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
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
    // No AndroidX, no network, no analytics: framework widgets + Kotlin stdlib only.
    testImplementation("junit:junit:4.13.2")
}
