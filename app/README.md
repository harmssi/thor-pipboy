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
