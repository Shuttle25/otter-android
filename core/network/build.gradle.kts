plugins { alias(libs.plugins.kotlin.jvm) }
kotlin { jvmToolchain(21) }
dependencies {
    api(project(":core:crypto"))
    api(libs.okhttp)
    api(libs.coroutines.core)
    testImplementation(libs.junit); testImplementation(libs.coroutines.test)
    testImplementation(libs.okhttp.tls); testImplementation(libs.mockwebserver3)
}
