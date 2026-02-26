import java.util.Properties
import java.io.FileInputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.meowgi.launcher710"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.meowgi.launcher710"
        minSdk = 26
        targetSdk = 34
        versionCode = 4
        versionName = "1.4.0"
    }

    signingConfigs {
        val keystoreFile = rootProject.file("app/release.keystore")
        val propsFile = rootProject.file("keystore.properties")
        if (keystoreFile.exists() && propsFile.exists()) {
            val props = Properties().apply { load(FileInputStream(propsFile)) }
            create("release") {
                storeFile = keystoreFile
                storePassword = props["storePassword"] as String?
                keyAlias = props["keyAlias"] as String?
                keyPassword = props["keyPassword"] as String?
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.viewpager2:viewpager2:1.0.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
}
