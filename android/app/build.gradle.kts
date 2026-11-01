plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

val releaseSigningEnvironment = listOf(
    "DINING_SIGNING_STORE_FILE",
    "DINING_SIGNING_STORE_PASSWORD",
    "DINING_SIGNING_KEY_ALIAS",
    "DINING_SIGNING_KEY_PASSWORD",
).associateWith { System.getenv(it)?.takeIf(String::isNotBlank) }

fun requireReleaseSigning() {
    val missing = releaseSigningEnvironment.filterValues { it == null }.keys
    if (missing.isNotEmpty()) {
        error("Release signing requires environment variables: ${missing.joinToString()}")
    }
    if (!file(releaseSigningEnvironment.getValue("DINING_SIGNING_STORE_FILE")!!).isFile) {
        error("Release signing keystore file does not exist")
    }
}

// Fail before tasks execute, including when an aggregate build selects release.
if (gradle.startParameter.taskNames.any { it.contains("release", ignoreCase = true) }) {
    requireReleaseSigning()
}
gradle.taskGraph.whenReady {
    if (allTasks.any { it.project.path == ":app" && it.name.contains("release", ignoreCase = true) }) {
        requireReleaseSigning()
    }
}

android {
    namespace = "top.alanvampuke.dininglog"
    compileSdk = 35
    defaultConfig {
        minSdk = 26
        applicationId = "top.alanvampuke.dininglog"
        targetSdk = 35
        versionCode = 5
        versionName = "0.5.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    signingConfigs {
        create("release") {
            storeFile = releaseSigningEnvironment["DINING_SIGNING_STORE_FILE"]?.let { file(it) }
            storePassword = releaseSigningEnvironment["DINING_SIGNING_STORE_PASSWORD"]
            keyAlias = releaseSigningEnvironment["DINING_SIGNING_KEY_ALIAS"]
            keyPassword = releaseSigningEnvironment["DINING_SIGNING_KEY_PASSWORD"]
            enableV2Signing = true
        }
    }
    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
    androidTestImplementation(libs.retrofit)
    androidTestImplementation(libs.retrofit.serialization)
    debugImplementation(libs.compose.ui.test.manifest)
}
