plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}
android {
    namespace = "app.ottermsg.feature.conversation"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    buildFeatures { compose = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_21; targetCompatibility = JavaVersion.VERSION_21 }
    testOptions { unitTests.isIncludeAndroidResources = true }
}
kotlin { jvmToolchain(21) }
dependencies {
    implementation(project(":core:ui")); implementation(project(":core:data"))
    implementation(libs.androidx.lifecycle.runtime.compose); implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.hilt.android); ksp(libs.hilt.compiler); implementation(libs.hilt.navigation.compose)
    implementation(libs.compose.material.icons); implementation(libs.androidx.activity.compose)
    testImplementation(libs.junit); testImplementation(libs.robolectric); testImplementation(libs.coroutines.test)
    testImplementation(libs.compose.ui.test.junit4); testImplementation(libs.compose.ui.test.manifest)
}
