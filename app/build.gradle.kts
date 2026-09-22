plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.multimodelviewer"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.multimodelviewer"
        // Filament/gltfio require API 19+; 24 is a safe, common low-end floor.
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    // IMPORTANT: .glb files are binary glTF containers. If AAPT compresses
    // them inside the APK, gltfio's buffer/stream reads can fail or become
    // needlessly slow to decompress on low-end CPUs at load time.
    androidResources {
        noCompress += listOf("glb")
    }
}

dependencies {
    implementation("com.google.android.filament:filament-android:1.56.0")
    implementation("com.google.android.filament:gltfio-android:1.56.0")
    implementation("com.google.android.filament:filament-utils-android:1.56.0")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
}
