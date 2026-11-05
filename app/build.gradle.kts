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

    signingConfigs {\n        create("release") {\n            val ks = System.getenv("KINOZAL_KEYSTORE_FILE")\n            if (!ks.isNullOrEmpty()) {\n                storeFile = file(ks)\n                storePassword = System.getenv("KINOZAL_STORE_PASSWORD")\n                keyAlias = System.getenv("KINOZAL_KEY_ALIAS")\n                keyPassword = System.getenv("KINOZAL_KEY_PASSWORD")\n            }\n        }\n    }\n\n    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}

dependencies {
    implementation("org.jsoup:jsoup:1.18.3")
}
