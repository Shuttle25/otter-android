# M3 manual test (groups, design assets, message menu)

Setup (otter-server): `scripts/dev-mesh.sh` as in [M2](m2-manual-test.md) (real phones: the mesh advertises the PC's LAN IP;
emulator: a mesh advertised as `127.0.0.1` plus `adb reverse tcp:808N tcp:808N` for each node port, because the emulator
cannot reach the PC's LAN IP through the host firewall). New devices need a fresh bootstrap invite or an invite from a member.
Install the debug APK (`JAVA_HOME=~/.gradle/jdks/eclipse_adoptium-21-amd64-linux.2 ./gradlew installDebug`).

Result column: results of the 2026-11-11 run on the emulator (`Medium_Phone_API_35`, Android 15) against a 3-node mesh,
with two scripted members (JVM clients that greet every group they join and read everything). "—" = not run yet
(needs the two real phones).

| # | Step | Expected | Result |
|---|------|----------|--------|
| 1 | Fresh install, open the app | Welcome with the otter illustration above the title; light and dark; 200 % text: text and button fit | pass (light/dark, 100/200 %) |
| 2 | Join; chat list before any chat | "Пока нет чатов" with the no-chats art and one "Написать" button; never shown while loading | pass (FAB hidden in this state since this run) |
| 3 | Написать → Новая группа: name, "Семейная группа" on, pick 2 members, Создать | Conversation opens; top bar shows the family art, name, "3 участника"; "Вы создали группу «…»" chip | pass |
| 4 | Other members write | Their messages appear with the author name at the start of each series only | pass |
| 5 | Send a message; members read it | Status goes ✓ → ✓✓ → read only when **all** members delivered / read | pass |
| 6 | Long-press own message at the bottom | Menu directly above the bubble, right-aligned: Копировать, Сведения, Удалить у меня | pass |
| 7 | Long-press an incoming message | Menu above the bubble, left-aligned (no Сведения) | pass |
| 8 | Long-press the topmost visible message | Menu opens below the bubble | — |
| 9 | Сведения on an outgoing group message | "Доставлено X из N", "Прочитано Y из N", each member with state and time | pass ("2 of 2", both "Read, 04:08") |
| 10 | Tap the top bar → group info | Art 64 dp, name with edit (admins), "N участников", members with "админ", Добавить участников, Покинуть группу | pass (own avatar initials fixed during the run) |
| 11 | Tap a member (admin): Сделать админом; another: Удалить из группы (confirm) | Chips "Вы назначили администратором: …", "Вы удалили из группы: …"; member count drops | pass |
| 12 | Write after the removal | Remaining members read it; the removed member gets nothing (sender keys rotated) | pass (remaining member read it; removal covered by LiveGroupTest) |
| 13 | Create a normal group (switch off) | Coloured initials avatar (colour stays after renaming) | pass ("D", mint) |
| 14 | Leave as the last admin | Dialog asks for a new admin first; afterwards "Вы покинули группу" and the composer is replaced by "Вы больше не участник этой группы"; group info read-only | pass |
| 15 | Rename (admin) on phone A; check phone B | Title and chip update on both | pass (phones) |
| 16 | Add a member on phone A; the new member writes | New member sees only messages after joining; others read the new member | pass (phones) |
| 17 | Phone B offline; A changes the group and writes; B back online | B catches up: state, events and messages in order | pass (phones) after reopening the app; Wi-Fi back while the app stayed open did not reconnect → fixed (network change during a connect attempt was ignored), retest with the app open |
| 18 | Launcher icon: circle and squircle shapes; themed icons on (Wallpaper & style → Themed icons) | Otter head fully inside the mask; monochrome head readable | circle pass on the Pixel launcher; squircle/rounded checked by rendering the masks; themed — |
| 19 | Android 12+ splash | Adaptive icon on the app background (light and dark) | — |
| 20 | Empty conversation (direct chat with no messages) | Empty-chat art + "Пока нет сообщений" / "Напишите первое сообщение"; hidden when the keyboard is open | — |
| 21 | TalkBack on group info and a group conversation | Rows read "name, админ"; admin actions in the actions menu; event chips read as text | — |

Phone run (2026-11-11, phone A + phone B, dev mesh): rows 5, 9, 12, 15, 16, 17 checked on the phones. Row 5
by design: a group message stays ✓ until **every** member has it (✓✓) and turns read only when every member has read it.

Record pass/fail per line and any crash from `adb logcat -b crash`.
