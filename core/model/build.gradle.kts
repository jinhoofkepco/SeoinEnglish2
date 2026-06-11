plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

// core:model is pure-JVM Kotlin: data classes + JSON contract only.
// No Android, no Compose, no DI — so it stays trivially testable and
// every other module can depend on it without cost.
dependencies {
    api(libs.kotlinx.serialization.json)
    api(libs.kotlinx.datetime)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
