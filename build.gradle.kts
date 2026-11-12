plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.protobuf) apply false
    alias(libs.plugins.google.services) apply false
}

// Some JavaCompile tasks (e.g. Hilt's) default to the Gradle daemon JVM instead of the Kotlin
// toolchain; pin them all to JDK 21 so builds work when JAVA_HOME is a JRE.
subprojects {
    tasks.withType<JavaCompile>().configureEach {
        javaCompiler.set(project.extensions.getByType<JavaToolchainService>().compilerFor {
            languageVersion.set(JavaLanguageVersion.of(21))
        })
    }
}
