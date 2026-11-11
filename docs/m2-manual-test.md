# M2 manual test (two devices, three nodes)

Setup (otter-server): `scripts/dev-mesh.sh --reset` (emulators: default host `10.0.2.2`; real phones on the same Wi-Fi:
`--host <PC LAN IP>`, and allow ports 8081–8083 through the firewall). The bootstrap invite is in `.dev-mesh/bootstrap-invite.txt`.
Install the debug APK on both devices (Gradle on Temurin 21:
`JAVA_HOME=~/.gradle/jdks/eclipse_adoptium-21-amd64-linux.2 ./gradlew installDebug`). Phones that joined the M1 `.dev` network
must clear Otter's data first (Settings → Apps → Otter → Storage → Clear data).

| # | Step | Expected | Result |
|---|------|----------|--------|
| 1 | A joins with the bootstrap link; A invites B (Настройки → Пригласить в семью); B joins | Both connected; Настройки → Подключение shows "Подключено" and the node (a, b or c) | |
| 2 | Exchange a few messages both ways | ✓ → ✓✓ → blue ✓✓ as in M1 | |
| 3 | On the PC: `scripts/dev-mesh.sh kill <node A uses>` | At most a brief "Подключаемся…"/"Нет подключения", then A shows another node in Подключение | |
| 4 | Send B → A and A → B right after step 3 | Messages arrive; statuses progress | |
| 5 | `kill` the node B uses as well (one node left) | Both end up on the remaining node; chat works | |
| 6 | `scripts/dev-mesh.sh start <n>` for the stopped nodes | Nothing visible in the app; later messages still flow | |
| 7 | Remove node c: `bin/otter-admin --dir .dev-mesh/admin node remove --label c`, `manifest sign`, `manifest publish --allow-plain` | Apps pick up the new manifest; a phone that was on c moves to a or b; Подключение never shows c again | |
| 8 | Toggle Wi-Fi off/on (or switch to mobile data and back, real phones) | Reconnects without user action; no duplicated messages | |
| 9 | Stop everything (`scripts/dev-mesh.sh stop`), send a message, start all (`scripts/dev-mesh.sh`) | Message stays PENDING (clock), then goes out once the mesh is back; no duplicates | |
| 10 | Force-stop both apps, reopen | Conversations unchanged, no duplicates | |

Record pass/fail per line and any crash from `adb logcat -b crash`.
