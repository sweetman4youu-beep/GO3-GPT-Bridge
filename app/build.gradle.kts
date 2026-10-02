plugins {
    id("com.android.application")
}

android {
    namespace = "com.niaman.go3bridge"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.niaman.go3bridge.fresh"
        minSdk = 31
        targetSdk = 35
        versionCode = 2
        versionName = "0.7.2-fresh"
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
