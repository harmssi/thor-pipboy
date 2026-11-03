# Thor Pip-Boy (Android)

Begleit-App für den unteren Bildschirm des AYN Thor: eigener Pip-Boy in grüner
Phosphor-Optik (alles per Canvas gezeichnet, keine Spielgrafiken), Daten live aus
Fallout 4 über das F4SE-Plugin **ThorPipBoy** (`ws://127.0.0.1:8767`, siehe `../plugin`).

- Reiter STAT (Status-Figur mit Gliedmaßen, S.P.E.C.I.A.L.), INV (Waffen, Kleidung, Hilfe,
  Verschiedenes, Schrott, Munition – Tippen = ausrüsten/ablegen/benutzen), DATEN (Quests, Welt),
  KARTE (Marker aus dem Pip-Boy-Datenmodell + Spielerpfeil, Tippen = Zoom), RADIO (an/aus, Sender).
- Auto-Reconnect, „Warte auf Fallout 4…“, Englisch/Deutsch.
- Starter-Kachel „Thor Pip-Boy“: startet Fallout 4 (AppID 377160) per DroiDeck-Intent auf
  Display 0 und den Pip-Boy auf Display 4.

Bauen: `./build.sh [--install]` (Docker-Image `thor-build` auf `THOR_BUILD_HOST`).
UI-Test ohne Spiel: `python3 -I tools/mock_server.py` + `adb reverse tcp:8767 tcp:8767`.

## Wächter (Guard.java)

Fallout 4 braucht auf dem Thor fast den ganzen RAM; beim Laden eines Spielstands beendet der
Low-Memory-Killer oft den Pip-Boy. Der Wächter ist eine Alarm-Kette ohne eigenen Dauerprozess:
Der laufende Pip-Boy schiebt alle 2 s einen exakten Alarm 6 s in die Zukunft. Stirbt er, prüft ein
kurzlebiger Receiver per HTTP den Plugin-Status (`ready`, `readyForMs`) und öffnet den Pip-Boy erst,
wenn der Spielstand seit mindestens 20 s geladen ist. Grund (am Gerät gemessen): Holt sich der
Pip-Boy den Vordergrund, fällt DroiDeck von oom_adj 0 auf 50 und wird bei einem Lade-Peak beendet –
samt Spiel. Selbst geschlossen = bleibt zu, bis das Spiel endet.

Einmalig nötig: `adb shell appops set de.harmssi.thorpipboy SYSTEM_ALERT_WINDOW allow` und
`adb shell appops set de.harmssi.thorpipboy SCHEDULE_EXACT_ALARM allow`.
Test-Haken: `adb shell am start -n de.harmssi.thorpipboy/.PipBoyActivity --ez debug_kill true`.
