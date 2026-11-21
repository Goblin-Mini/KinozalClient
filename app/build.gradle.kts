import java.io.FileInputStream
import java.security.KeyStore
import java.security.MessageDigest

plugins {
    id("com.android.application")
}

val releaseKeystorePath = System.getenv("KINOZAL_KEYSTORE_FILE")
val releaseKeystorePassword = System.getenv("KINOZAL_STORE_PASSWORD")
val releaseKeyAlias = System.getenv("KINOZAL_KEY_ALIAS")
val releaseCertSha256: String = if (!releaseKeystorePath.isNullOrBlank()
    && !releaseKeystorePassword.isNullOrBlank()
    && !releaseKeyAlias.isNullOrBlank()) {
    val keyStore = KeyStore.getInstance("JKS")
    FileInputStream(releaseKeystorePath).use {
        keyStore.load(it, releaseKeystorePassword.toCharArray())
    }
    val certificate = keyStore.getCertificate(releaseKeyAlias)
        ?: throw GradleException("Release certificate not found for configured alias")
    MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
        .joinToString("") { "%02X".format(it) }
} else {
    // Debug builds compile but intentionally cannot pass the release-certificate gate.
    "0000000000000000000000000000000000000000000000000000000000000000"
}

android {
    namespace = "com.goblinmini.kinozalclient"
    compileSdk = 35
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "ru.gm.ktv"
        minSdk = 23
        targetSdk = 28
        versionCode = 6
        versionName = "1.0.5"

        externalNativeBuild {
            cmake {
                arguments += listOf("-DKTV_OFFICIAL_CERT_SHA256=$releaseCertSha256")
                cppFlags += listOf("-std=c++17", "-fvisibility=hidden", "-fvisibility-inlines-hidden",
                    "-fstack-protector-strong", "-D_FORTIFY_SOURCE=2")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
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
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }
}

dependencies {
    implementation("org.jsoup:jsoup:1.18.3")
    implementation("androidx.webkit:webkit:1.14.0")
    implementation("androidx.tvprovider:tvprovider:1.1.0")
    implementation("androidx.work:work-runtime:2.10.1")
}
