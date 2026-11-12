# otter-android

The OtterMSG Android client (Gradle multi-module: `app`, `core:proto`, `core:crypto`, `core:database`, `core:ui`). Protobuf schemas and cross-language test vectors come from `../otter-server`; refresh them with `scripts/sync-proto.sh`. Build and test with `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew assembleDebug test` (modules compile with a JDK 17 toolchain). Specs are in [`../docs`](../docs).


Backup and restore (M6): Settings → Безопасность → Резервная копия turns on an encrypted password backup (Argon2id, Tink streaming AEAD, uploaded to the nodes as a persistent blob). On a wiped phone, «Восстановить аккаунт» restores from the network link plus username and password (account and history) or from the 24 words (account only). Code: `core/data/.../backup` (`BackupManager`, `RestoreManager`, `InstallGuard`), `messaging/SessionResets`. Manual checks: [`docs/m6-manual-test.md`](docs/m6-manual-test.md).

## Private files (never commit)

| File | Where it lives | Why it is private |
|------|----------------|-------------------|
| `~/.otter-admin/` | admin PC only (outside the repo) | network admin key, node keys, TLS keys |
| `/etc/otter/fcm.json` | push-capable servers only (`deploy/push-key.sh`) | Firebase service-account key |
| `otter-android/app/google-services.json` | owner's PC only | Firebase app config (the build works without it, no push) |
| `local.properties`, `*.jks`, `keystore.properties` | owner's PC only | SDK path, release signing key |

`scripts/secrets-check.sh` (gitleaks v8.24.0 + personal-data grep) must pass before publishing.
