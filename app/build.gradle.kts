plugins {
    id("com.android.application")
}

android {
    namespace = "com.niaman.go3bridge"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.niaman.go3bridge.v090"
        minSdk = 31
        targetSdk = 35
        versionCode = 14
        versionName = "1.7.0-practical-bridge"
    }

    signingConfigs {
        getByName("debug") {
            storeFile = file("go3bridge-debug.jks")
            storePassword = "go3bridge"
            keyAlias = "go3bridge"
            keyPassword = "go3bridge"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
        }
    }
}

dependencies {
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
}
