plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}
android {
    namespace = "app.ottermsg"
    compileSdk = 36
    ndkVersion = "27.0.12077973"
    defaultConfig {
        applicationId = "app.ottermsg"
        minSdk = 26; targetSdk = 36
        versionCode = 1; versionName = "0.1.0"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
    }
    buildTypes {
        debug { buildConfigField("boolean", "ALLOW_PLAINTEXT", "true") }
        release {
            buildConfigField("boolean", "ALLOW_PLAINTEXT", "false")
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21; targetCompatibility = JavaVersion.VERSION_21
        isCoreLibraryDesugaringEnabled = true // required by libsignal-android
    }
    packaging {
        jniLibs { excludes += "**/libsignal_jni_testing.so" }
        resources { excludes += listOf("libsignal_jni*.so", "libsignal_jni*.dylib", "signal_jni*.dll") }
    }
}
kotlin { jvmToolchain(21) }
dependencies {
    implementation(project(":core:ui")); implementation(project(":core:database")); implementation(project(":core:crypto"))
    implementation(project(":core:data"))
    implementation(project(":feature:onboarding")); implementation(project(":feature:chats")); implementation(project(":feature:conversation")); implementation(project(":feature:groups"))
    implementation(libs.libsignal.android)
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    implementation(libs.androidx.core.ktx); implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose); implementation(libs.hilt.navigation.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.hilt.android); ksp(libs.hilt.compiler)
    testImplementation(libs.junit)
}
