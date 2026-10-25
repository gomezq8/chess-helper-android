plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val buildNumber = (System.getenv("VERSION_CODE") ?: "1").toInt()

android {
    namespace = "com.gomez.chesshelper"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.gomez.chesshelper"
        minSdk = 26
        targetSdk = 35
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    // Same key for every build so updates install over the old version.
    signingConfigs {
        create("release") {
            storeFile = rootProject.file("signing/release-key.jks")
            storePassword = "chesshelper"
            keyAlias = "chess"
            keyPassword = "chesshelper"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    // Stockfish ships as libstockfish.so so Android installs it as an executable file.
    packaging {
        jniLibs { useLegacyPackaging = true }
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
    // Chess rules (legal moves) - pure Java
    implementation("com.github.bhlangonijr:chesslib:1.3.3")
}
