# M1 manual test (two devices)

Setup: `otter-server/scripts/dev-node.sh` (default host `10.0.2.2` for emulators; `--host <PC LAN IP>` for a real phone on the same Wi-Fi).
Install the debug APK on both devices: `./gradlew installDebug` (or `adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk`).
Device A = first member, device B = second member.

| # | Step | Expected | Result |
|---|------|----------|--------|
| 1 | A: open app → "Присоединиться по приглашению" → paste the bootstrap link from `.dev/bootstrap-invite.txt` | Link accepted, profile screen | |
| 2 | A: name + username → phrase (24 words) → confirm 3 words | Screenshot blocked on phrase screens; account created; chat list (empty state) | |
| 3 | A: avatar → Настройки → "Пригласить в семью" | QR + "Действует 7 дней, один раз"; "Поделиться ссылкой" opens share sheet | |
| 4 | B: join by scanning A's QR (or pasting the shared link) | Profile screen shows "Вас приглашает <A>"; account created | |
| 5 | Both: "Написать" | The other person is listed | |
| 6 | B → A: send a text | B: clock → ✓ → ✓✓ (grey); A: message appears, unread badge in list | |
| 7 | A: open the conversation | B's message turns ✓✓ blue (Прочитано) | |
| 8 | A → B: reply; long text with a long URL | Wraps inside the bubble, no horizontal scroll | |
| 9 | Stop the node (Ctrl-C) | Banner "Нет подключения. Сообщения отправятся автоматически." on the chat list | |
| 10 | B: send while the node is down | Message stays PENDING (clock), not FAILED | |
| 11 | Restart the node with `--keep` | Banner disappears; message goes ✓ → ✓✓ | |
| 12 | B: send, then force-stop the app immediately; reopen | Message delivered after restart, no duplicate | |
| 13 | Long-press a message → "Удалить у меня" | Confirmation text "Сообщение исчезнет только на этом устройстве."; removed only on this device | |
| 14 | TalkBack on a message | Read as one unit: author, text, time, status | |

Not covered in M1: reinstall/restore (M6), background delivery (M5).
Record pass/fail per line, APK sizes and any crash from `adb logcat -b crash`.
