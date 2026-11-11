# M4 manual test (photos)

Setup: the dev mesh runs the M4 server build (`otter-server/scripts/dev-mesh.sh stop && scripts/dev-mesh.sh`, data kept).
Phones: phone A (`adb install -r`), phone B (APK via `python3 -m http.server 8000`). Emulator: a mesh advertised as
`127.0.0.1` plus `adb reverse tcp:<port> tcp:<port>`; `adb input text` can't type Cyrillic; long links in ≤ 60-char chunks;
keep the screen on (`adb shell svc power stayon true`). A test photo with EXIF orientation 6 and GPS:
`/sdcard/Pictures/dacha_gps.jpg` (pushed, then a media scan broadcast).

Result column: the 2026-11-11 emulator run (`Medium_Phone_API_35`, Android 15, **staging** build = R8) against a 3-node scratch
mesh with one scripted member (JVM client that sends photos on request and downloads everything). "—" = not run yet
(needs the two phones).

| # | Step | Expected | Result |
|---|------|----------|--------|
| 1 | Open a chat | Composer: gallery button at the start («Выбрать фото»), camera in the field («Сделать снимок»), send 48 dp | pass |
| 2 | Gallery → pick the EXIF-6 + GPS photo | Full-screen preview, upright, caption field (draft prefilled), Отмена / Отправить | pass after two fixes found in the run: preview rotation, preview as a dialog (the chat behind was still touchable/focusable) |
| 3 | Caption, Отправить | Bubble with the photo (radius 16, ≤ 320 dp, crop beyond), caption below, time right-aligned; ✓ → ✓✓; the member downloads it | pass (time alignment fixed in the run) |
| 4 | Tap the photo | Viewer on black; double tap 2.5×; pinch; Back returns | pass (double tap) |
| 5 | Viewer → Сохранить в галерею | «Сохранено в галерею»; file in Pictures/Otter, 1920×2560, **no EXIF, no GPS** | pass (checked with PIL) |
| 6 | Camera, deny the permission | Dialog «Для снимка нужен доступ к камере» with Настройки / Выбрать из галереи | pass |
| 7 | Camera, granted → shutter → Done → Отправить | Sent without a caption: time/status on a scrim over the photo | pass |
| 8 | Settings → Фото и память → Вручную; member sends a photo | Thumbnail + «Загрузить фото»; chat list «Фото: подпись»; tap → downloads | pass |
| 9 | Временные файлы → Очистить | Cache 0 B; «Фото на телефоне» unchanged; photos still open | pass (436 kB → 0 B; 62 kB kept) |
| 10 | All nodes down → send a photo | Spinner, «Ожидает отправки»; nodes back → sent and delivered | pass (~30 s after the nodes returned) |
| 11 | Photo both ways between the two phones | Thumbnail at once, full photo on Wi-Fi, ✓✓ / read | — |
| 12 | Group of 3 (two phones + one more member) | Every member gets the photo; Сведения shows per-member delivery | — |
| 13 | Phone on mobile data, policy «Только по Wi-Fi» | Thumbnail + «Загрузить фото» until Wi-Fi | — |
| 14 | Portrait photo from the phone camera | Upright for the receiver; saved copy without GPS (EXIF viewer) | — |
| 15 | TalkBack on a photo bubble | "author, Фото, caption, time, status"; actions: Сохранить в галерею, Удалить у меня | — (emulator dump: "You, Photo, Dacha, 18:37, Delivered") |
| 16 | 200 % font | Composer buttons and preview controls fit; caption wraps | — |
| 17 | Android 8–9 phone (if any): Сохранить в галерею | Storage permission asked once, then saved | — |

Mesh acceptance (real processes, 2026-11-11): `scripts/dev-mesh.sh kill b`, `LiveBlobTest` (20 MiB, clients on a and c)
passed in 8.4 s; `start b` → b holds no blob rows, chunk rows or files (it never learnt the meta: the other nodes had deleted
it; its BLOB_FETCHED row would delete a late meta at once). The upload node itself going down is meshsim scenario 1.

Record pass/fail per line and any crash from `adb logcat -b crash`.
