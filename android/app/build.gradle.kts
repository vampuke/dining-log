plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "top.alanvampuke.dininglog"
    compileSdk = 35
    defaultConfig {
        minSdk = 26
        applicationId = "top.alanvampuke.dininglog"
        targetSdk = 35
        versionCode = 4
        versionName = "0.4.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // P5 adds environment-based release signing; no fallback to debug keys.
        }
    }
}
kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":core"))
    implementation(libs.coroutines.core)
    implementation(project(":data"))
    implementation(libs.room.runtime)
    implementation(project(":feature"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.coroutines.test)
    androidTestImplementation(libs.room.testing)
    androidTestImplementation(libs.room.runtime)
    androidTestImplementation(libs.mockwebserver)
    androidTestImplementation(libs.serialization.json)
    androidTestImplementation(libs.okhttp)
    debugImplementation(libs.compose.ui.test.manifest)
}
