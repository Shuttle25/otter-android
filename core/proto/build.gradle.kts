plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.protobuf)
}
kotlin { jvmToolchain(21) }
dependencies { api(libs.protobuf.javalite) }
protobuf {
    protoc { artifact = libs.protobuf.protoc.get().toString() }
    generateProtoTasks {
        all().forEach { task -> task.builtins { named("java") { option("lite") } } }
    }
}
