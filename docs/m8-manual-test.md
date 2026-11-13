# M8 manual test (1:1 audio calls)

Setup: the local two-node network `x`/`y` with TURN from `otter-server/docs/m8-manual-test.md`. Two emulators, debug
builds (`x86_64`), app language English:

* **A** = `m8_api34` (Google APIs, Android 14), account `m8a_emu`;
* **B** = `m8_api30` (Google APIs, Android 11), accounts `m8b_emu`, later `m8c_emu` (update test).

Invite links typed in 25-character chunks with a pause and compared with `uiautomator dump`. Microphone: emulator
virtual microphone fed from the host (`adb emu avd hostmicon`); "audio both ways" = relayed bytes on the node grow in
both directions (muting A for 30 s cut the relayed volume by ~40 %). A phone was pinned to a node / UDP was blocked
with `iptables` **inside the emulator** (`adb root`), never on the PC. The local nodes have **no FCM key**, so a
phone whose app has left the screen for more than a few seconds cannot be woken (see Findings); ringing with the app
closed is an owner row on the real phones.

Results: 2026-11-13 emulator run. "—" = not run yet (owner: `eeee`, ru-1 node, the two phones, Bluetooth, mobile).

| # | Step | Expected | Result |
|---|------|----------|--------|
| 1 | A calls B; B in the background with the screen off and locked (relay-only on) | B wakes with the full-screen incoming call; A «Звонит…» | PASS 2026-11-13 (B API 30: screen on, «M8A · Incoming call», Decline/Accept) |
| 2 | B accepts; call ≥ 2 min; A hangs up | timer on both; audio both ways; lines «Исходящий звонок · m:ss» / «Входящий звонок · m:ss» | PASS 2026-11-13: «Outgoing call · 2:55» / «Incoming call · 2:56», 353 036 B relayed |
| 3 | «Журнал подключений» after the call | `call:relay-udp … ok (OUTGOING m:ss, rtt)` | PASS 2026-11-13: A `y call:relay-udp Wi-Fi ok ×1 (OUTGOING 2:55, 2 ms)`, B `(INCOMING 2:56, 4 ms)` |
| 4 | Decline on B | A «Отклонён», line on both | PASS 2026-11-13 («Declined» on both; B in the app → heads-up with Decline/Answer) |
| 5 | No answer 45 s, B locked | A «Не отвечает»; B «Пропущенный звонок» line + notification | PASS 2026-11-13 (notification «Missed call — M8A») |
| 6 | B in an ordinary phone call (emulator GSM call accepted), A calls | A «Занято» | PASS 2026-11-13 («Busy») |
| 7 | A on `x`, B on `y` (cross-node), B hangs up | rings in ~3 s; connects; remote hang-up ends both; relays released | PASS 2026-11-13 (0:55 call); A's log line showed `call:none` → fixed in `fbee406` (path kept from when ICE connected) |
| 8 | UDP to the PC blocked in both emulators | call connects over TCP; `call:relay-tcp` | PASS 2026-11-13: `x call:relay-tcp … (OUTGOING 0:33)`, `y call:relay-tcp … (INCOMING 0:33)` |
| 9 | TURN off on the nodes, A calls | «Звонки недоступны: сервер не обновлён», no line | PASS 2026-11-13 after `dc98cce` + `c05ab41` (before: cached credentials → ringing, then a blank call screen). The screen shows the text without the peer's name |
| 10 | API 34 (A) locked, B calls | A wakes with the full-screen incoming call | PASS 2026-11-13 (woke within 2 s) |
| 11 | API 34 full-screen intents denied (`appops set --uid app.ottermsg USE_FULL_SCREEN_INTENT deny`) | locked phone: notification only (screen stays off); Settings shows «Разрешить звонки на заблокированном экране»; the row opens the system page and disappears once allowed | PASS 2026-11-13 (with only the per-package op denied the phone still rang full-screen) |
| 12 | «Звонки только через сервер» off on both, call | `call:direct` | PASS 2026-11-13: `x call:direct … (OUTGOING 0:22)`, `y call:direct … (INCOMING 0:22)`; switched back on |
| 13 | 0.7.0 (7) phone receives a call | nothing rings, no notification; may get an empty chat; caller «Не отвечает» | PASS 2026-11-13: empty chat «M8A» created (as the release news says); A «Соединение…» then «No answer» |
| 14 | `adb install -r` 0.8.0 (8) over 0.7.0 (Room 7→8) | account and chats kept; the next call rings and writes a call line | PASS 2026-11-13: same chat, rang ~3 s after dialing, «Incoming call · 0:14» |
| 15 | News kept across 0.7.0 → 0.8.0 | «Новости Выдры» history still there | — (no news key on the local network; owner, row 19) |
| 16 | (owner) phone on RU mobile (ru-1) ↔ phone on foreign Wi-Fi (`eeee`), relay-only, 5 min | audio both ways the whole time (best effort: the border is not handled in M8) | — |
| 17 | (owner) RU ↔ RU via ru-1; abroad ↔ abroad via `eeee` | clear audio; log `call:relay-udp` on the expected node | — |
| 18 | (owner) phone locked, app swiped away (FCM), call it | rings full-screen within seconds | — |
| 19 | (owner) both phones: release 0.8.0 («Обновить»); account, chats, «Новости Выдры» kept | nothing lost | — |
| 20 | (owner) Bluetooth headset; speaker toggle; proximity sensor (screen off at the ear) | audio follows the route; screen off at the ear | — |
| 21 | (owner) Wi-Fi → mobile switch mid-call | audio back within 20 s (one ICE restart) or the call ends «Не удалось соединиться» | — |
| 22 | (owner) a phone without push (battery-saver mode) is called | caller sees «Собеседник может не услышать звонок…» and the call still goes out | — |

## Findings fixed during the run

* `fbee406` — a call the other side ended was logged as `call:none`: the live stats had no selected pair any more. The
  path seen 2 s after ICE connected is now kept as a fallback.
* `dc98cce` — TURN credentials were cached for up to 11 h, so a node that switched TURN off (or got a new secret)
  made calls ring and then fail. Credentials are now asked for on every call; the cache is only a fallback when that
  request fails. (Revises the spec's "cached until 1 h before expiry".)
* `c05ab41` — the «Звонки недоступны» notice was emitted before the call screen subscribed, so the screen opened blank
  and closed. The last notice is now replayed and cleared when a new call starts.

## Not fixed (noted)

* Without push the app keeps its connection only a few seconds after leaving the screen, so such a phone cannot ring
  (D61, Instant mode is V1.x). The caller's warning relies on `push.status`, which only knows whether a push token
  exists.
* The notice screen in row 9 shows no peer name.
* A full `./gradlew test` failed once and passed on the rerun (intermittent; see the final verification).
