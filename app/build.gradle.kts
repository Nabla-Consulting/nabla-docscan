import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.kapt)
    alias(libs.plugins.hilt)
}

// Load local.properties (never committed to git)
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) load(file.inputStream())
}

android {
    namespace = "com.nabla.docscan"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.nabla.docscan"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // MSAL client ID from local.properties (no hardcoded secrets)
        buildConfigField(
            "String",
            "MSAL_CLIENT_ID",
            "\"${localProperties.getProperty("msal.clientId", "YOUR_CLIENT_ID_HERE")}\""
        )
        buildConfigField(
            "String",
            "ONEDRIVE_FOLDER_ID",
            "\"${localProperties.getProperty("onedrive.folderId", "root")}\""
        )
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
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
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.constraintlayout)
    implementation(libs.lifecycle.viewmodel.ktx)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.livedata.ktx)
    implementation(libs.activity.ktx)
    implementation(libs.fragment.ktx)
    implementation(libs.navigation.fragment.ktx)
    implementation(libs.navigation.ui.ktx)

    // CameraX
    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)

    // ML Kit
    implementation(libs.mlkit.doc.scanner)
    implementation(libs.mlkit.text.recognition)

    // DI
    implementation(libs.hilt.android)
    kapt(libs.hilt.compiler)

    // Async
    implementation(libs.coroutines.android)

    // Microsoft Auth + Graph
    implementation(libs.msal)
    implementation(libs.retrofit)
    implementation(libs.retrofit.gson)
    implementation(libs.okhttp.logging)
    implementation(libs.gson)

    // Image loading
    implementation(libs.coil)
}

