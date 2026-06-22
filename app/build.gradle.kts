plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.seoin.emojienglish.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.seoin.emojienglish"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1-skeleton"
    }

    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
        }
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
    // Shell wiring only — no business logic (§2).
    implementation(project(":feature:main"))
    implementation(project(":feature:home"))
    implementation(project(":feature:player"))
    implementation(project(":feature:master"))
    implementation(project(":feature:step-api"))
    implementation(project(":core:model"))
    implementation(project(":core:designsystem"))

    // Steps are assembled ONLY here (§2). Comment any line out and the app still
    // builds — that type then renders as the "unsupported step" card (§0.6).
    implementation(project(":steps:wordcomic"))
    implementation(project(":steps:storycomic"))
    implementation(project(":steps:voiceexplain"))
    implementation(project(":steps:similarcard"))
    implementation(project(":steps:shadowing"))
    implementation(project(":steps:question"))
    implementation(project(":steps:chunk"))
    implementation(project(":steps:passageread"))
    implementation(project(":steps:passageread2"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.hilt.navigation.compose)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
}
