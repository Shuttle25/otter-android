# M5 manual test (push notifications)

Setup: the dev mesh runs the M5 server build (`otter-server/scripts/dev-mesh.sh stop && scripts/dev-mesh.sh`, data kept;
the nodes need `push.register`, otherwise the app logs a harmless error per login). The dev mesh has no FCM key, so it
never pushes: rows 12–16 need the real network (`eeee` with `deploy/push-key.sh`) and a Firebase build (the owner's
`app/google-services.json`, never committed; Task 14).
Phones: phone A (`adb install -r`), phone B (APK via `python3 -m http.server 8000`), dev mesh at `<PC LAN IP>`
(`192.168.x.y`).
Emulator hints: `adb input text` can't type Cyrillic; long links in ≤ 60-char chunks; keep the screen on
(`adb shell svc power stayon true`); read the screen with `uiautomator dump`; Russian UI on an English emulator:
`adb shell cmd locale set-app-locales app.ottermsg --locales ru-RU`. Notifications: `adb shell dumpsys notification --noredact`.
Battery saver: WorkManager runs the periodic «saver» job once when it is enqueued, then every 15 min. Forcing it with
`adb shell cmd jobscheduler run -f -n androidx.work.systemjobscheduler app.ottermsg <job id>` (id from
`adb shell dumpsys jobscheduler | grep 'JobStatus{.*app.ottermsg'`) does **not** help: WorkManager refuses a periodic
run before its schedule («Delaying execution … because it is being executed before schedule») and re-queues it. Wait for
the period instead (`TIME=+…` in the dumpsys line is the time left).

Result column: the 2026-11-12 emulator run (`Medium_Phone_API_35`, Android 15, **debug** build without Firebase →
SAVER, app language Russian via `set-app-locales`) against the 3-node dev mesh with one scripted contact (JVM client
«Бот M5» that writes on request and logs what it receives and the ✓/✓✓ state). "—" = not run yet (needs the phones / the
real network).

| # | Step | Expected | Result |
|---|------|----------|--------|
| 1 | Settings bottom | «Otter 0.5.0 (hash)» below «Подключение» | pass («Otter 0.5.0 (683988e)») |
| 2 | Fresh start of the app (Android 13+) | notification permission asked once | pass (asked once, when the empty chat list was first shown) |
| 3 | Settings → Уведомления (build **without** Firebase) | «Экономия батареи» with the hint | pass («Экономия батареи», «Проверяем каждые 15 минут: сервисы Google недоступны или не отвечают») |
| 4 | App in background, contact writes | notification within 15 min | pass after a fix (see notes): 11 min 47 s from send to notification (the job started 3.4 s before it) |
| 5 | Lock screen | only «Otter / Новое сообщение» | pass (PIN set, «hide sensitive content»: «Otter» / «Новое сообщение») |
| 6 | Notification → «Ответить» | reply delivered; notification cleared | pass (the contact had the reply ~1 s after «Send»; notification cleared, contact's message ✓✓ read) |
| 7 | Notification → «Прочитано» | cleared; contact sees read | pass (app in the background with its network blocked: contact saw ✓✓ read ~1 s later; notification cleared) |
| 8 | Chat open on screen, contact writes | no notification | pass (message read at once, nothing posted) |
| 9 | Group message | notification titled with the group name, sender per line | pass (title «M5 group», line «Бот M5: …», channel «Группы») |
| 10 | Photo message | «Фото» / «Фото: подпись» | — (the scripted contact can't send real photos; covered by NotificationContentTest) |
| 11 | Switch «Показывать уведомления» off | nothing posted | pass (message delivered, nothing posted; switched back on) |
| 12 | (owner, Task 14) Firebase build on phone A | Settings → «Быстрые уведомления» | — |
| 13 | (owner) phone A screen off ≥ 2 min, contact sends; stopwatch from "send" to the sound | < 5 s; repeat 5 times, write each time | — (1: _ s, 2: _ s, 3: _ s, 4: _ s, 5: _ s) |
| 14 | (owner) phone A in Doze (`adb shell dumpsys deviceidle force-idle`), contact sends | notification < 5 s | — |
| 15 | (owner) phone B, same as 13 | < 5 s (if Google Play services present) | — |
| 16 | (owner) `deploy/status.sh` | PUSH = on for eeee | — |
| 17 | (owner) TalkBack on the notification and the Уведомления screen | everything read out | — |

Notes from the run:
- **Bug found and fixed (WorkManager 2.10.1 → 2.11.2):** Android 15 blocks the network of a cached app process
  (`dumpsys netpolicy`: `blocked=APP_BACKGROUND`). With 2.10.1 the in-process scheduler started `SaverWorker` in that
  process; the drain could not connect and gave up after 60 s, so the first 15-min check showed nothing. 2.11.1+ treats a
  blocked network as an unmet constraint («Constraints not met» in logcat) and the run happens as a JobScheduler job,
  which is allowed to use the network. After the fix, with the same cached process alive, the in-process run was
  refused while the network was blocked (the later job run was overlapped by an app launch, so row 4's timing is from
  the run where the job started the process).
- A push or notification action (reply, «Прочитано») runs the expedited «wake» job, which gets network even when the
  app's background network is blocked.
- `adb input text` into a notification's reply field: tap the field first; dismiss Gboard's stylus tutorial if it opens.
- The dev mesh never pushes (no FCM key), so the < 5 s rows need the owner's Firebase build and `eeee` (Task 14).
