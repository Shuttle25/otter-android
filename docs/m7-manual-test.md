# M7 manual test (connection log, demotion, Room 6→7)

Setup: the local two-node network from `otter-server/docs/m7-manual-test.md` (`fi` with REALITY on 127.0.0.1:24443,
`ru` dial-out-only, `fi`'s ws endpoint in the manifest on the closed port 9191 so `ru` can't reach it over WS).
Emulator `Medium_Phone_API_35` (Android 15), app language English (strings checked as «Connection log» / «Share»).
Emulator on `ru`: `adb reverse tcp:9182 tcp:9182`; on `fi`: `adb reverse --remove tcp:9182; adb reverse tcp:9191
tcp:9181`, then force-stop and start the app. The other party: a temporary JVM client on `fi` (LiveClient copy, not
committed). Invite links: typed in 30-character chunks and compared with `uiautomator dump` (60-character chunks
dropped characters this time); the app has no `otter://` intent filter.

Update path: 0.6.0 → 0.7.0 by `adb install -r` of the **debug** builds (0.6.0 built from `main` in a temporary
worktree). The announcement-based update («Обновить») was proven in M6.5 (row 8 there) and is not repeated here; this
network has no news key (creating one writes to the desktop keyring), so news survival is checked on the phones.

Results: 2026-11-13 emulator run. "—" = not run yet (owner: `eeee`, ru-1 node, the two phones, MTS/Beeline).

| # | Step | Expected | Result |
|---|------|----------|--------|
| 1 | Install 0.6.0 (6) debug; join with an invite from the `fi` client while only `ru` is reachable | account created, connected to `ru` | PASS 2026-11-13 (`m7emu5205`, invited by «Бот Фи») |
| 2 | 0.6.0: message `fi` → phone, reply phone → `fi` | both delivered through ru ↔ fi (REALITY), read receipts | PASS 2026-11-13 (`Privet from fi v060` in, `Reply from ru v060` → Read) |
| 3 | `adb install -r` 0.7.0 (7) debug over 0.6.0 (Room 6→7) | account, chats, messages kept; no crash | PASS 2026-11-13: same chat list and history, Settings «EmuRu @m7emu5205», «Otter 0.7.0 (7b14110)», no FATAL in logcat |
| 4 | 0.7.0 on `ru`: messages both ways | delivered + read | PASS 2026-11-13 (`Hello from ru v070` → Read; `Hi from fi v070` in within 1 s) |
| 5 | Settings → Подключение → «Журнал подключений» on `ru` | the attempt with node, address, network type, class | PASS 2026-11-13: `13.11 12:26  ru  ws://127.0.0.1:9182  Wi-Fi  ok  ×1` |
| 6 | «Поделиться» | share sheet with the log as text | PASS 2026-11-13: «Sharing text» `Otter 0.7.0 (7b14110) · 2026-11-13` + the lines |
| 7 | Switch the emulator to `fi` (ru unreachable), restart app | connects to `fi`; log shows the failed `ru` attempt | PASS 2026-11-13: «Connected fi · ws://127.0.0.1:9191/…»; log `ru … Wi-Fi refused ×1 (Failed to connect …)`, `fi … Wi-Fi ok ×1` |
| 8 | Messages on `fi` both ways | delivered + read | PASS 2026-11-13 (`Now on fi` → Read; `Bot sees you on fi` in) |
| 9 | News kept across 0.6.0 → 0.7.0 | «Новости Выдры» history still there | — (no news key on the local network; check on the phones, row 11) |
| 10 | (owner) both phones: release 0.7.0 via `deploy/release-apk.sh 0.7.0 …` («Обновить») | updated, Settings «Otter 0.7.0 (hash)» | — |
| 11 | (owner) after the update: account, chats and «Новости Выдры» kept | nothing lost | — |
| 12 | (owner) phone A on the ru-1 node, phone B on `eeee` (direct WSS ru-1 → `eeee` blocked or throttled): messages both ways | delivered + read; node netlogs show REALITY | — |
| 13 | (owner) MTS / Beeline mobile in the city centre: use for a day, then «Журнал подключений» → «Поделиться» | lines with `mobile`, failure classes; a throttled endpoint is tried last | — |
| 14 | (owner) Wi-Fi ↔ mobile switch | new lines with the new network type; reconnect | — |
