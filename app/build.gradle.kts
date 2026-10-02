plugins {
    id("com.android.application")
}

android {
    namespace = "com.niaman.go3bridge"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.niaman.go3bridge.safe"
        minSdk = 31
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0-safe"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}

dependencies {
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
}
