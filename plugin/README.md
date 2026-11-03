# ThorPipBoy – F4SE plugin

F4SE plugin for Fallout 4 **1.11.240** that serves Pip-Boy data over a WebSocket server
(`ws://127.0.0.1:8767`). Architecture and protocol follow [SkyrimWebSocket](https://github.com/andreyvelsk/SkyrimWebSocket).

## Build (no Windows needed)

`./build.sh` syncs the sources to a Linux host with Docker (`THOR_BUILD_HOST=user@host`) and builds in the
image from [`docker/Dockerfile`](docker/Dockerfile): Debian trixie, clang-cl/lld-link 21 from apt.llvm.org,
MSVC CRT + Windows SDK via [xwin](https://github.com/Jake-Shadle/xwin) (accepts the Microsoft license on image build).
Result: `out/ThorPipBoy.dll` (static CRT).

Dependencies are git submodules (`git submodule update --init --recursive`):
[libxse/commonlibf4](https://github.com/libxse/commonlibf4) (supports 1.11.240 via Address Library IDs) and
[spdlog](https://github.com/gabime/spdlog) v1.16.0. CommonLibF4's `F4SE/Registration*.cpp` is skipped
(does not compile with clang-cl and is not needed).

`tools/check_addrlib.py <version-1-11-240-0.bin>` checks that all Address Library IDs exist;
`tools/install_thor.sh <F4SE folder> <bin>` installs everything via adb (backs up existing files).

Log: `Data/F4SE/Plugins/ThorPipBoy.log` (next to the DLL; time in the Proton prefix is UTC).

## Protocol

JSON messages with a `type` field. After connecting the server sends `hello`
(`server`, `version`, `protocol`, `ready`, `keys`). A plain HTTP request returns
`{"server":"ThorPipBoy","ready":…,"readyForMs":…}` (used by the app's guard).

| Client → server | |
|---|---|
| `{"type":"subscribe","id":"s","settings":{"frequency":500,"sendOnChange":true},"fields":{"stats":"player.stats"}}` | subscription; `fields` as object (alias → key) or array |
| `{"type":"unsubscribe","id":"s"}` / `{"type":"unsubscribe_all"}` | stop |
| `{"type":"query","id":"q","fields":["player.inventory"]}` | one-shot → `data` |
| `{"type":"heartbeat"}` | → `{"type":"heartbeat","ts":…,"ready":…}` |
| `{"type":"command","id":"c","command":"toggle","params":{"id":"0x0004822B","stack":0}}` | → `commandResult` |

Server → client: `data` (`{"type":"data","id":…,"data":{alias: value}}`), `commandResult`
(`success`, `result`, `error`), `error`.

| Key | Content |
|---|---|
| `game` | `ready`, `player`, `pipboyOpen` |
| `player.stats` | name, level, xpProgress, hp/hpMax, ap/apMax, rads, caps, weight/weightMax, resistances |
| `player.special` | S.P.E.C.I.A.L. (`value`, `base`) |
| `player.limbs` | head, torso, leftArm, rightArm, leftLeg, rightLeg (`value`, `max`, `crippled`) |
| `player.inventory` | `[{id, stack, name, type, count, equipped, weight, value}]` |
| `player.quests` | displayed quest objectives |
| `player.location` | x, y, z, heading, interior, cell, location, worldspace |
| `world.time` | year (e.g. 287 = 2287), month (1-based), day, hour, daysPassed |
| `radio` | enabled, frequency, stations |
| `pipboy`, `pipboy.<path>` | raw data from the game's internal Pip-Boy data model (`PipboyDataManager`), e.g. `pipboy.Map.World`, `pipboy.Quests` |

| Command | Params | Effect |
|---|---|---|
| `toggle` | `id`, `stack` | equip/unequip, or use an aid item |
| `equip` / `unequip` / `use` | `id`, `stack` | explicit |
| `radio` | `enable` (optional, otherwise toggle) | Pip-Boy radio on/off |

All data is collected and all commands run on the game thread (F4SE task queue).

## Tested

AYN Thor, DroiDeck (Steam/Proton/FEX), Fallout 4 1.11.240, F4SE 0.7.9, Address Library (all-in-one):
live stats, inventory, quests, map, radio; equip, use and radio toggle work in game.
Known: `EquipObject` returns `false` for consumables although the item is used – reported as success.

## License

GPL-3.0-or-later with the Modding Exception and GPL-3.0 Linking Exception (see `LICENSE`, `EXCEPTIONS`).
