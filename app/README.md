# Thor Pip-Boy – Android app

Bottom-screen companion for the AYN Thor: own green phosphor Pip-Boy look (everything drawn on a
Canvas, no game artwork), live data from the **ThorPipBoy** F4SE plugin (`ws://127.0.0.1:8767`, see `../plugin`).

- Tabs STAT, INV (tap = equip / unequip / use), DATA, MAP (world + local map, tap = zoom), RADIO (on/off, stations), FAV (quick keys 1–12 as tiles, Stimpak/RadAway buttons)
- Auto-reconnect, “waiting for Fallout 4…”, English/German
- Launcher tile “Thor Pip-Boy”: opens the Pip-Boy on display 4, then Fallout 4 (Steam AppID 377160) via
  DroidDeck's `LAUNCH_GAME` intent on display 0 (this order keeps DroidDeck in front)
- Guard (`Guard.java`): an exact-alarm chain without a resident process reopens the Pip-Boy after Android
  killed it, but only once the plugin reports the game stable for ≥ 20 s. Needs
  `SYSTEM_ALERT_WINDOW` and `SCHEDULE_EXACT_ALARM` granted via appops (see top-level README).
  Test hook: `adb shell am start -n de.harmssi.thorpipboy/.PipBoyActivity --ez debug_kill true`.

## Build

`./build.sh [--install]` builds in the Docker image `thor-build` (Android SDK 35 + Gradle) on
`THOR_BUILD_HOST`. The release signing key is generated once on the build host (`keystore/`, not in this repo)
and must be kept so updates install over older versions.

UI test without the game: `python3 -I tools/mock_server.py` + `adb reverse tcp:8767 tcp:8767`.

## License

MIT
