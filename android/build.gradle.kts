plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
}
subprojects {
    dependencyLocking { lockAllConfigurations() }
    configurations.configureEach {
        if (name.endsWith("RuntimeClasspath", ignoreCase = true)) {
            // Metadata-only stdlib-common has no JVM runtime classes. AGP's
            // ASM artifact graph omits it, making a generated runtime lock
            // fail on the next build. The JVM stdlib remains version-locked.
            exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-common")
        }
    }
}
