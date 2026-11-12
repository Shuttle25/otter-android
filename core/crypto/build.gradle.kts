plugins { alias(libs.plugins.kotlin.jvm) }
kotlin { jvmToolchain(21) }
dependencies {
    api(project(":core:proto"))
    implementation(libs.tink)
    implementation(libs.bouncycastle.prov)
    testImplementation(libs.junit)
}
