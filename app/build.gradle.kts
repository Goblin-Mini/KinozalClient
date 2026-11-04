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
        versionCode = 2
        versionName = "0.2.0-native"
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

dependencies {
    implementation("org.jsoup:jsoup:1.18.3")
}
