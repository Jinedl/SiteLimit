plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Подпись берётся из переменных окружения (их задаёт GitHub Actions).
// Если их нет, release-APK собирается неподписанным.
val signingFile = System.getenv("SIGNING_KEYSTORE")
val signingPassword = System.getenv("SIGNING_PASSWORD")
val hasSigning = !signingFile.isNullOrBlank() && !signingPassword.isNullOrBlank()

android {
    namespace = "io.github.jinedl.sitelimit"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.jinedl.sitelimit"
        minSdk = 26
        // 34, а не 35: на Android 15 при targetSdk 35 обязателен edge-to-edge,
        // и простой экран залезал бы под системные панели.
        targetSdk = 34
        // Увеличивать в каждой версии.
        versionCode = 8
        versionName = "0.8"
    }

    signingConfigs {
        if (hasSigning) {
            create("release") {
                storeFile = file(signingFile!!)
                storePassword = signingPassword
                keyAlias = System.getenv("SIGNING_ALIAS") ?: "sitelimit"
                keyPassword = signingPassword
                storeType = "PKCS12"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (hasSigning) signingConfig = signingConfigs.getByName("release")
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
