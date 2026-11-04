plugins {
    id("com.android.application")
}

android {
    namespace = "com.goblinmini.kinozalclient"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.goblinmini.kinozalclient"
        minSdk = 23
        targetSdk = 28
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}
