# otter-android

The OtterMSG Android client (Gradle multi-module: `app`, `core:proto`, `core:crypto`, `core:database`, `core:ui`). Protobuf schemas and cross-language test vectors come from `../otter-server`; refresh them with `scripts/sync-proto.sh`. Build and test with `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew assembleDebug test` (modules compile with a JDK 17 toolchain). Specs are in [`../docs`](../docs).
