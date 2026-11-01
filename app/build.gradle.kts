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
        versionCode = 26
        versionName = "1.6.1"
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
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
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

    testImplementation("junit:junit:4.13.2")
    // Real org.json for JVM tests; android.jar only ships stubs.
    testImplementation("org.json:json:20231013")
    // Runs the Room migration SQL against real SQLite on the JVM.
    testImplementation("org.xerial:sqlite-jdbc:3.45.1.0")
    // Drives real views (SearchOverlay, settings insets) on the JVM.
    testImplementation("org.robolectric:robolectric:4.11.1")
    testImplementation("androidx.test:core:1.5.0")
    // Android's HttpURLConnection is a fork of OkHttp 2.7.5; the latency bench runs through it.
    testImplementation("com.squareup.okhttp:okhttp-urlconnection:2.7.5")
}
