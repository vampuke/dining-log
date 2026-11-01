plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}
kotlin { jvmToolchain(17) }
dependencies {
    implementation(libs.coroutines.core)
    implementation(libs.serialization.core)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.coroutines.test)
}
