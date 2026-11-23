plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.quickmt.gamesubtitles"
    compileSdk = 34
    ndkVersion = "26.3.11579264"

    defaultConfig {
        applicationId = "com.quickmt.gamesubtitles"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "1.1.0"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++17", "-O3", "-fexceptions", "-frtti")
                arguments += listOf("-DANDROID_STL=c++_shared")
            }
        }
    }

    signingConfigs {
        getByName("debug") {
            storeFile = file("quickmt-debug.p12")
            storeType = "PKCS12"
            storePassword = "quickmtdebug"
            keyAlias = "quickmt"
            keyPassword = "quickmtdebug"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
        debug {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Bundled offline Google ML Kit Latin Text Recognition (no internet needed!)
    implementation("com.google.mlkit:text-recognition:16.0.1")
}
