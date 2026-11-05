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
        versionCode = 18
        versionName = "0.5.13"
    }

    lint {
        disable += "ExpiredTargetSdkVersion"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    signingConfigs {
        create("release") {
            val ks = System.getenv("KINOZAL_KEYSTORE_FILE")
            if (!ks.isNullOrEmpty()) {
                storeFile = file(ks)
                storePassword = System.getenv("KINOZAL_STORE_PASSWORD")
                keyAlias = System.getenv("KINOZAL_KEY_ALIAS")
                keyPassword = System.getenv("KINOZAL_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }
}

dependencies {
    implementation("org.jsoup:jsoup:1.18.3")
}
