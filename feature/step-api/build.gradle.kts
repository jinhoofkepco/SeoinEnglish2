plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose.compiler)
}

// feature:step-api is the contract that is frozen first (M1). It depends only on
// core modules and must never depend on any concrete step / player / master.
android {
    namespace = "com.seoin.emojienglish.step"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
    }
    buildFeatures { compose = true }
}

dependencies {
    api(project(":core:model"))
    api(project(":core:voice"))

    implementation(platform(libs.androidx.compose.bom))
    api(libs.androidx.compose.ui)
    implementation(libs.kotlinx.coroutines.core)

    // Hilt annotations only (StepRegistry + @IntoMap StringKey). Component
    // generation happens in :app, so no ksp is needed here.
    implementation(libs.hilt.android)
}
