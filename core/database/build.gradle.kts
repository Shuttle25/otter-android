plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}
android {
    namespace = "app.ottermsg.core.database"
    compileSdk = 36
    defaultConfig { minSdk = 26; testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_21; targetCompatibility = JavaVersion.VERSION_21 }
    testOptions { unitTests.isIncludeAndroidResources = true }
    sourceSets["test"].assets.srcDir("$projectDir/schemas") // MigrationTestHelper reads exported schemas
}
kotlin { jvmToolchain(21) }
ksp { arg("room.schemaLocation", "$projectDir/schemas") }
dependencies {
    implementation(libs.room.runtime); implementation(libs.room.ktx); ksp(libs.room.compiler)
    implementation(libs.sqlite.ktx); implementation(libs.sqlcipher)
    implementation(libs.hilt.android); ksp(libs.hilt.compiler)
    testImplementation(libs.junit); testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core); testImplementation(libs.coroutines.test)
    testImplementation(libs.room.testing); testImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.core); androidTestImplementation(libs.junit)
    androidTestImplementation("androidx.test:runner:1.7.0"); androidTestImplementation("androidx.test.ext:junit:1.3.0")
}
