plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}
android {
    namespace = "app.ottermsg.core.data"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_21; targetCompatibility = JavaVersion.VERSION_21 }
    testOptions { unitTests.isIncludeAndroidResources = true }
}
kotlin { jvmToolchain(21) }
dependencies {
    api(project(":core:signal")); api(project(":core:network")); api(project(":core:database"))
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    implementation(libs.zxcvbn)
    implementation(libs.hilt.android); ksp(libs.hilt.compiler)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.exifinterface)
    testImplementation(libs.junit); testImplementation(libs.robolectric); testImplementation(libs.androidx.test.core)
    testImplementation(libs.coroutines.test)
}
