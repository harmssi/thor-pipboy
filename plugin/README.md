# ThorPipBoy (F4SE-Plugin)

F4SE-Plugin für Fallout 4 **1.11.240** (Steam, „Anniversary“-Runtime), das Pip-Boy-Daten
über einen WebSocket-Server bereitstellt – Gegenstück zur Android-App `de.harmssi.thorpipboy`
auf dem unteren Bildschirm des AYN Thor. Ersatz für die in 1.11.x nicht mehr funktionierende
offizielle Pip-Boy-App-Schnittstelle (TCP 27000).

Architektur und Protokoll sind an [SkyrimWebSocket](https://github.com/andreyvelsk/SkyrimWebSocket) angelehnt.

## Bauen (ohne Windows)

`./build.sh` – synchronisiert die Quellen auf den Build-Host (`THOR_BUILD_HOST` aus
`~/.config/thorconquer/env`) und baut dort im Docker-Image `fo4-xbuild`
(`docker/Dockerfile`: Debian trixie, clang-cl/lld-link 21 von apt.llvm.org, MSVC-CRT + Windows-SDK via
[xwin](https://github.com/Jake-Shadle/xwin)). Ergebnis: `out/ThorPipBoy.dll` (statische CRT).

Abhängigkeiten als Git-Submodule: [libxse/commonlibf4](https://github.com/libxse/commonlibf4)
(unterstützt 1.11.240, Address-Library-IDs), [spdlog](https://github.com/gabime/spdlog) v1.16.0.
`F4SE/Registration*.cpp` aus CommonLibF4 wird ausgelassen (kompiliert nicht mit clang-cl, nicht benötigt).

## Installation (Spielverzeichnis)

- F4SE 0.7.x für 1.11.240 (`f4se_loader.exe`, `f4se_1_11_240.dll`, `Data/Scripts`)
- Address Library: `Data/F4SE/Plugins/version-1-11-240-0.bin`
- `Data/F4SE/Plugins/ThorPipBoy.dll` (+ optional `ThorPipBoy.ini`)
- Log: `Documents/My Games/Fallout4/F4SE/ThorPipBoy.log`

## Protokoll (ws://127.0.0.1:8767)

JSON-Nachrichten mit Feld `type`. Der Server schickt nach dem Verbinden `hello`
(`server`, `version`, `protocol`, `ready`, `keys`).

| Client → Server | Beschreibung |
|---|---|
| `{"type":"subscribe","id":"s","settings":{"frequency":500,"sendOnChange":true},"fields":{"stats":"player.stats"}}` | Abo; `fields` als Objekt (Alias → Schlüssel) oder Array |
| `{"type":"unsubscribe","id":"s"}` / `{"type":"unsubscribe_all"}` | Abo beenden |
| `{"type":"query","id":"q","fields":["player.inventory"]}` | einmalige Abfrage → `data` |
| `{"type":"heartbeat"}` | → `{"type":"heartbeat","ts":…,"ready":…}` |
| `{"type":"command","id":"c","command":"toggle","params":{"id":"0x0004822B","stack":0}}` | → `commandResult` |

Server → Client: `{"type":"data","id":…,"data":{alias: wert}}`, `commandResult`
(`success`, `result`, `error`), `error`.

### Schlüssel

| Schlüssel | Inhalt |
|---|---|
| `game` | `ready`, `player`, `pipboyOpen` |
| `player.stats` | name, level, xpProgress, hp/hpMax, ap/apMax, rads, caps, weight/weightMax, Resistenzen |
| `player.special` | S.P.E.C.I.A.L. (`value`, `base`) |
| `player.limbs` | head, torso, leftArm, rightArm, leftLeg, rightLeg (`value`, `max`, `crippled`) |
| `player.inventory` | `[{id, stack, name, type, count, equipped, weight, value}]`, type ∈ weapon/apparel/aid/ammo/junk/book/holotape/key/misc |
| `player.quests` | angezeigte Questziele `[{quest, questId, objective, index, state}]` |
| `player.location` | x, y, z, heading, interior, cell, location, worldspace |
| `world.time` | year, month, day, hour, daysPassed |
| `radio` | enabled, frequency, stations (Pip-Boy-Baum `Radio`) |
| `pipboy`, `pipboy.<Pfad>` | Rohdaten aus dem internen Pip-Boy-Datenmodell (`PipboyDataManager`), z. B. `pipboy.Map.World` |

Alle Daten werden auf dem Spielthread (F4SE-Task-Queue) gesammelt; Befehle ebenso.

### Befehle

| Befehl | Parameter | Wirkung |
|---|---|---|
| `toggle` | `id`, `stack` | ausrüsten/ablegen bzw. Hilfsmittel benutzen |
| `equip` / `unequip` / `use` | `id`, `stack` | explizit |
| `radio` | `enable` (optional, sonst umschalten) | Pip-Boy-Radio an/aus |

## Thor/DroiDeck (getestet 02.11.2026)

- F4SE 0.7.9 + Address Library (All in One, `version-1-11-240-0.bin`) + Plugin per
  `tools/install_thor.sh <F4SE-Ordner> <bin>`; vorher `tools/check_addrlib.py <bin>` (alle 26 genutzten IDs vorhanden).
- Steam-Startoption (Eigenschaften → Allgemein → Startoptionen; vorher leer):
  `bash -c 'a=("${@/Fallout4Launcher.exe/f4se_loader.exe}"); exec "${a[@]/Fallout4.exe/f4se_loader.exe}"' -- %command%`
- Ergebnis: Plugin lädt (F4SE 0.7.9, Spiel 1.11.240), Server aus Android auf 127.0.0.1:8767 erreichbar,
  Werte, Inventar, Quests, Karte (Welt + lokal) und Radio live; Ausrüsten, Benutzen und Radio an/aus funktionieren im Spiel.
- Log: `Data/F4SE/Plugins/ThorPipBoy.log` (Uhrzeit im Proton-Prefix = UTC).
- Bekannt: `EquipObject` meldet bei Verbrauchsgütern `false`, obwohl benutzt wird → wird als Erfolg gemeldet.
