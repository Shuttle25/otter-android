import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}
// Firebase is optional (D61): without the owner's google-services.json (never committed) the app builds without push.
val firebaseConfigured = file("google-services.json").exists()
if (firebaseConfigured) apply(plugin = "com.google.gms.google-services")
// Packages that google-services.json has a client for (e.g. no `app.ottermsg.staging`). A variant whose applicationId
// is missing builds without Firebase: its google-services task is disabled and BuildConfig.FIREBASE is false.
@Suppress("UNCHECKED_CAST")
val firebasePackages: Set<String> = if (!firebaseConfigured) emptySet() else
    ((groovy.json.JsonSlurper().parse(file("google-services.json")) as Map<String, Any?>)["client"] as List<Map<String, Any?>>)
        .mapNotNull { ((it["client_info"] as? Map<*, *>)?.get("android_client_info") as? Map<*, *>)?.get("package_name") as? String }
        .toSet()
// Short git hash for the Settings version line (D63); "dev" outside a git checkout. Configuration-cache safe.
val gitHash: Provider<String> = providers.exec {
    commandLine("git", "rev-parse", "--short=7", "HEAD")
    isIgnoreExitValue = true
}.standardOutput.asText.map { it.trim().ifEmpty { "dev" } }.orElse("dev")
// Release signing (D68): the owner's keystore, when keystore.properties (gitignored, with storeFile, storePassword,
// keyAlias, keyPassword) exists next to settings.gradle.kts; otherwise release builds stay debug-signed as before.
val keystoreProps = rootProject.file("keystore.properties")
android {
    namespace = "app.ottermsg"
    compileSdk = 36
    ndkVersion = "27.0.12077973"
    defaultConfig {
        applicationId = "app.ottermsg"
        minSdk = 26; targetSdk = 36
        versionCode = 7; versionName = "0.7.0"
        buildConfigField("String", "GIT_HASH", "\"${gitHash.get()}\"")
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    signingConfigs {
        if (keystoreProps.exists()) create("release") {
            val p = Properties().apply { keystoreProps.inputStream().use { load(it) } }
            storeFile = rootProject.file(p.getProperty("storeFile"))
            storePassword = p.getProperty("storePassword"); keyAlias = p.getProperty("keyAlias"); keyPassword = p.getProperty("keyPassword")
        }
    }
    buildTypes {
        debug { buildConfigField("boolean", "ALLOW_PLAINTEXT", "true") }
        release {
            buildConfigField("boolean", "ALLOW_PLAINTEXT", "false")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
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
androidComponents {
    onVariants { variant ->
        val appId = variant.applicationId.get()
        val firebase = appId in firebasePackages
        variant.buildConfigFields?.put("FIREBASE", com.android.build.api.variant.BuildConfigField("boolean", firebase.toString(), null))
        if (firebaseConfigured && !firebase) {
            val name = variant.name.replaceFirstChar { it.uppercase() }
            tasks.matching { it.name == "process${name}GoogleServices" }.configureEach { enabled = false }
        }
    }
}
// WakeFlowTest reuses core:data's FakeNode / TestGraph. They use core:data internals, so they cannot be compiled here;
// their compiled classes go on the unit-test classpath as a jar (AGP ignores plain class directories there). Internal
// names are mangled per variant, so debug tests get core:data's debug test classes and release/staging the release ones.
fun coreDataTestJar(variant: String) = tasks.register<Jar>("coreData${variant}TestJar") {
    dependsOn(":core:data:compile${variant}UnitTestKotlin")
    from(rootProject.project(":core:data").layout.buildDirectory.dir("tmp/kotlin-classes/${variant.lowercase()}UnitTest"))
    archiveFileName.set("core-data-${variant.lowercase()}-test.jar")
    destinationDirectory.set(layout.buildDirectory.dir("core-data-test"))
}
val coreDataDebugTestJar = coreDataTestJar("Debug")
val coreDataReleaseTestJar = coreDataTestJar("Release")
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
    implementation(platform(libs.firebase.bom)); implementation(libs.firebase.messaging); implementation(libs.play.services.base)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.hilt.work); ksp(libs.androidx.hilt.compiler)
    testImplementation(libs.junit); testImplementation(libs.robolectric); testImplementation(libs.androidx.test.core)
    testImplementation(libs.coroutines.test); testImplementation(libs.androidx.work.testing)
    "testDebugImplementation"(files(coreDataDebugTestJar))
    "testReleaseImplementation"(files(coreDataReleaseTestJar)); "testStagingImplementation"(files(coreDataReleaseTestJar))
    androidTestImplementation(libs.junit); androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit); androidTestImplementation(libs.androidx.test.espresso)
    androidTestImplementation(platform(libs.compose.bom)); androidTestImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}
