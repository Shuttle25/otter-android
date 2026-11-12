# otter-android

The OtterMSG Android client (Gradle multi-module: `app`, `core:proto`, `core:crypto`, `core:database`, `core:ui`). Protobuf schemas and cross-language test vectors come from `../otter-server`; refresh them with `scripts/sync-proto.sh`. Build and test with `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew assembleDebug test` (modules compile with a JDK 17 toolchain). Specs are in [`../docs`](../docs).


Backup and restore (M6): Settings → Безопасность → Резервная копия turns on an encrypted password backup (Argon2id, Tink streaming AEAD, uploaded to the nodes as a persistent blob). On a wiped phone, «Восстановить аккаунт» restores from the network link plus username and password (account and history) or from the 24 words (account only). Code: `core/data/.../backup` (`BackupManager`, `RestoreManager`, `InstallGuard`), `messaging/SessionResets`. Manual checks: [`docs/m6-manual-test.md`](docs/m6-manual-test.md).
