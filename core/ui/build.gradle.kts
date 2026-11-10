plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}
android {
    namespace = "app.ottermsg.core.ui"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    buildFeatures { compose = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
kotlin { jvmToolchain(17) }
dependencies {
    api(platform(libs.compose.bom)); api(libs.compose.ui); api(libs.compose.material3)
    api(libs.compose.ui.tooling.preview); debugImplementation(libs.compose.ui.tooling)
    testImplementation(libs.junit)
}
