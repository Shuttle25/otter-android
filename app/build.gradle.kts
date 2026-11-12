plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}
// Short git hash for the Settings version line (D63); "dev" outside a git checkout. Configuration-cache safe.
val gitHash: Provider<String> = providers.exec {
    commandLine("git", "rev-parse", "--short=7", "HEAD")
    isIgnoreExitValue = true
}.standardOutput.asText.map { it.trim().ifEmpty { "dev" } }.orElse("dev")
android {
    namespace = "app.ottermsg"
    compileSdk = 36
    ndkVersion = "27.0.12077973"
    defaultConfig {
        applicationId = "app.ottermsg"
        minSdk = 26; targetSdk = 36
        versionCode = 5; versionName = "0.5.0"
        buildConfigField("String", "GIT_HASH", "\"${gitHash.get()}\"")
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildTypes {
        debug { buildConfigField("boolean", "ALLOW_PLAINTEXT", "true") }
        release {
            buildConfigField("boolean", "ALLOW_PLAINTEXT", "false")
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        // Release code (R8) that may join a plaintext dev mesh; debug-signed. For testing only, never distributed.
        create("staging") {
            initWith(getByName("release"))
            buildConfigField("boolean", "ALLOW_PLAINTEXT", "true")
            signingConfig = signingConfigs.getByName("debug")
            applicationIdSuffix = ".staging"
            matchingFallbacks += listOf("release")
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    testOptions { unitTests.isIncludeAndroidResources = true }
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
    implementation(project(":feature:onboarding")); implementation(project(":feature:chats")); implementation(project(":feature:conversation")); implementation(project(":feature:groups")); implementation(project(":feature:settings"))
    implementation(libs.libsignal.android)
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    implementation(libs.androidx.core.ktx); implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose); implementation(libs.hilt.navigation.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.room.runtime) // MessageNotifier reads OtterDatabase (a RoomDatabase)
    implementation(libs.hilt.android); ksp(libs.hilt.compiler)
    testImplementation(libs.junit); testImplementation(libs.robolectric); testImplementation(libs.androidx.test.core)
    testImplementation(libs.coroutines.test)
    androidTestImplementation(libs.junit); androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit); androidTestImplementation(libs.androidx.test.espresso)
    androidTestImplementation(platform(libs.compose.bom)); androidTestImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}
