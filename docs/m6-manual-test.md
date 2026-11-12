# M6 manual test (backup & restore)

Setup: the dev mesh runs the M6 server build (`otter-server/scripts/dev-mesh.sh stop && scripts/dev-mesh.sh`, data kept).
Phones: phone A (`adb install -r`), phone B (APK via `python3 -m http.server 8000`). A restore needs a link to the
network: any member's «Пригласить в семью» QR/link (it is not used up) or a bootstrap link.
Emulator hints: `adb input text` can't type Cyrillic (use Latin passwords; `%s` did not give spaces in our run, so the
24 words were typed field by field); long links in ≤ 60-char chunks; keep the screen on (`adb shell svc power stayon true`).
The 24-word screen blocks screenshots (FLAG_SECURE): read it with `uiautomator dump`.

Result column: the 2026-11-12 emulator run (`Medium_Phone_API_35`, Android 15, **debug** build, English locale) against the
3-node dev mesh with one scripted member (JVM client «Бот» that writes on request and restores accounts with words).
"—" = not run yet (needs the two phones).

| # | Step | Expected | Result |
|---|------|----------|--------|
| 1 | Settings | Section «Безопасность» with «Резервная копия» (before «Связь») | pass |
| 2 | Резервная копия (off) | Explanation + «Включить» | pass |
| 3 | Включить → `password12` twice | Strength bar red, «Надёжность пароля: слабый»; «Включить» disabled | pass |
| 4 | A long multi-word password twice → Включить | «Готовим ключ…» spinner, then «Последняя копия: <дата время>»; «Создать сейчас», «Сменить пароль» | pass (Argon2 64 MiB on the emulator: a few seconds) |
| 5 | Создать сейчас | «Копия создана»; a new version on every node | pass (version 2 on a, b, c) |
| 6 | Clear the app's data → «Восстановить аккаунт» → link → «Имя пользователя и пароль копии» → wrong password | «Неверное имя пользователя или пароль», stays on the form | pass |
| 7 | The right password | «Ищем копию…», then the chat list with the history | pass |
| 8 | Contact writes after the restore; restored phone answers | Contact's chat shows «… восстановил(а) аккаунт»; both messages arrive (✓✓) | pass |
| 9 | Restore the same account elsewhere (24 words), then reopen the first phone | «Аккаунт на другом телефоне» screen instead of the app; it never syncs again | pass (shown after the app reconnects, see notes) |
| 10 | Clear data → Восстановить → 24 words → Пропустить | Empty chat list; contacts known; a contact's new message arrives | pass |
| 11 | 24 words → «Вернуть переписку» with the backup password | History added to the restored account | — (covered by RestoreFlowTest) |
| 12 | A restored photo message | Blurred thumbnail with «Нет в резервной копии»; no download button | — (covered by RestoredMessagesTest) |
| 13 | TalkBack on the backup screen | Eye button «Показать пароль»; bar read as «Надёжность пароля: …» | pass (content descriptions in the UI dump) |
| 14 | Phone A: backup on; clear data; restore on phone A; phone B writes 1:1 and in a group | History back; B's messages arrive without B doing anything; A's group messages reach B | — |
| 15 | Phone A restored on phone B's hardware (B cleared) while A is switched off; switch A on | A shows «Аккаунт на другом телефоне» | — |
| 16 | 200 % font: backup screen, restore screens | No clipped text; the word grid scrolls | — |
| 17 | Russian locale: all new texts | RU strings, «восстановил(а) аккаунт» chip | — |

Notes from the run:
- An install that is **online** when its account is restored elsewhere is disconnected by the node as soon as mail
  arrives for the account (or when it tries to ack); its next login shows «Аккаунт на другом телефоне». It never takes or
  acks the new install's mail (server fix found in this run).
- Restoring through a node the backup has not reached yet answers «Неверное имя пользователя или пароль» and counts as a
  failed attempt (the node can't tell «no backup yet» from «wrong password», D51).
