# otter-android

The OtterMSG Android client (Gradle multi-module: `app`, `core:proto`, `core:crypto`, `core:database`, `core:ui`). Protobuf schemas and cross-language test vectors come from `../otter-server`; refresh them with `scripts/sync-proto.sh`. Build and test with `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew assembleDebug test` (modules compile with a JDK 17 toolchain). Specs are in [otter-docs](https://github.com/Shuttle25/otter-docs) (checked out next to this repo as `../docs`).


Backup and restore (M6): Settings → Безопасность → Резервная копия turns on an encrypted password backup (Argon2id, Tink streaming AEAD, uploaded to the nodes as a persistent blob). On a wiped phone, «Восстановить аккаунт» restores from the network link plus username and password (account and history) or from the 24 words (account only). Code: `core/data/.../backup` (`BackupManager`, `RestoreManager`, `InstallGuard`), `messaging/SessionResets`. Manual checks: [`docs/m6-manual-test.md`](docs/m6-manual-test.md).

## Push / Firebase

Push (M5) is optional and there is one build, no flavors. `firebase-messaging` is always a dependency; the
`com.google.gms.google-services` plugin is applied only when `app/google-services.json` exists. That file is the
owner's Firebase config: it is ignored by git (`git check-ignore -v app/google-services.json` names the rule) and never
committed. Without it the build works as usual, `BuildConfig.FIREBASE` is false and the app runs in «Экономия
батареи» (a WorkManager check every 15 minutes); Settings → Уведомления shows the mode.

To enable push:

1. [Firebase console](https://console.firebase.google.com): add a project (Google Analytics off), add an Android app
   with package `app.ottermsg` (no SHA-1 needed; add `app.ottermsg.staging` too for staging builds), download
   `google-services.json` to `app/google-services.json`. Skip the "add SDK" steps.
2. Project settings → Cloud Messaging: "Firebase Cloud Messaging API (V1)" must be enabled.
3. Project settings → Service accounts → «Generate new private key»: this is the nodes' key. Keep it outside every
   repo (e.g. `~/otter-secrets/fcm.json`) and install it with `otter-server/deploy/push-key.sh`, then
   `otter-admin node set-push` (see `otter-server/deploy/FIRST-DEPLOY.md`, "Push notifications").
4. Rebuild (`./gradlew :app:assembleDebug`) and install over the old APK (data kept). Settings → Уведомления shows
   «Быстрые уведомления» once Google Play services are present and a token exists.

How it works (D58–D62 in `../docs/00-decisions.md`): the app registers its FCM token as a signed `PUSH_TOKEN`
(`push.register`); a node sends a constant, content-free push; `OtterMessagingService` starts the expedited `wake`
work, which connects (last-good node first for 2 s), drains the mailbox, flushes the outbox and goes offline after 10 s
of quiet (at most 60 s). Notifications are built on the phone from the decrypted messages (MessagingStyle,
«Ответить», «Прочитано», private on the lock screen). After 3 missed wake-ups in 24 h the app falls back to the
15-minute `saver` work. WorkManager must stay ≥ 2.11.1: older versions run the saver in a cached process whose
network Android 15 blocks. Code: `core/data/.../push` (`PushRegistrar`, `PushMonitor`, `BackgroundSync`,
`MessageAlerts`), `app/.../push` (`OtterMessagingService`, `WakeScheduler`, `WakeWorker`, `SaverWorker`),
`app/.../notify` (`MessageNotifier`, `Channels`, `NotificationActionReceiver`), `feature/settings`
(`NotificationsScreen`). Manual checks: [`docs/m5-manual-test.md`](docs/m5-manual-test.md).

The bottom of Settings shows «Otter <versionName> (<short git hash>)» (`"dev"` outside a git checkout).

## News and in-app updates (M6.5)

The app fetches `news.list` after login and on the `news` event, verifies every announcement against the news keys of
its stored manifest and shows it in the read-only chat «Новости Выдры» (hidden while empty; links ask «Открыть
ссылку?»; notifications on the «Новости» channel only for news from the last 3 days). An announcement with an update
shows «Обновить до X» when its versionCode is higher than the installed one; the APK comes from the nodes (`apk.get`,
1 MiB chunks, resumable), its size and SHA-256 are checked and the system installer opens. Code: `core/crypto` (`AnnouncementVerifier`), `core/data/.../news`
(`NewsRepository`, `ApkUpdater`), `app/.../update` (`ApkInstaller`, `ApkFileProvider`).
Manual checks: [`docs/m6.5-manual-test.md`](docs/m6.5-manual-test.md).

Release signing: create `keystore.properties` next to `settings.gradle.kts` with `storeFile`, `storePassword`,
`keyAlias`, `keyPassword` (both it and the `.jks` are gitignored; back the keystore up — a lost key means every phone
must uninstall to update). Without it `assembleRelease` is debug-signed, and `deploy/release-apk.sh` refuses such an
APK. Switching a phone from the debug to the release signature needs one uninstall: make a backup first.

## Connection log and demotion (M7)

Every connection attempt of `NodeSelector` gets a failure class (`timeout`, `refused`, `reset`, `reset_after_kb`,
`tls`, `handshake`, `ok`); an endpoint whose connection drops after ≥ 1 KB three times within 24 h (the throttling
signature) is tried last for 24 h. Attempts go into a merged 200-line log (Room `net_log`, DB v7) with the network
type (Wi-Fi / mobile) and operator: Settings → «Журнал подключений», «Поделиться» sends it as text. Nothing is
uploaded automatically, and the app still speaks only WSS (REALITY runs between nodes, D70–D75). Code:
`core/network` (`FailureClass`, `NetLog`, `NodeSelector`, `ConnectionManager`), `core/data/.../network`
(`RoomNetLog`, `CurrentNetwork`), `feature/settings` (`NetLogScreen`). Manual checks:
[`docs/m7-manual-test.md`](docs/m7-manual-test.md).

## Private files (never commit)

| File | Where it lives | Why it is private |
|------|----------------|-------------------|
| `~/.otter-admin/` | admin PC only (outside the repo) | network admin key, node keys, TLS keys |
| `/etc/otter/fcm.json` | push-capable servers only (`deploy/push-key.sh`) | Firebase service-account key |
| `otter-android/app/google-services.json` | owner's PC only | Firebase app config (the build works without it, no push) |
| `local.properties`, `*.jks`, `keystore.properties` | owner's PC only | SDK path, release signing key |

`scripts/secrets-check.sh` (gitleaks v8.24.0 + personal-data grep) must pass before publishing.
