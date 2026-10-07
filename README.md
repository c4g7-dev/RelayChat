<p align="center">
  <img src="docs/logo.svg" width="128" alt="Relay Chat logo">
</p>

<h1 align="center">Relay Chat</h1>

<p align="center">
  <em>windowed chat for Minecraft: movable, lockable chat windows with tabs, regex filters and chat that survives server switches</em>
</p>

<p align="center">
  <a href="https://github.com/c4g7-dev/RelayChat/releases"><img src="https://img.shields.io/github/v/release/c4g7-dev/RelayChat?style=flat-square&color=5A8CFF&label=release" alt="latest release"></a>
  <img src="https://img.shields.io/badge/Minecraft-26.2%20%7C%2026.3-5A8CFF?style=flat-square" alt="Minecraft 26.2 and 26.3">
  <img src="https://img.shields.io/badge/loader-Fabric-DBD0B4?style=flat-square" alt="Fabric">
  <img src="https://img.shields.io/badge/side-client-6E6E6E?style=flat-square" alt="client-side">
  <a href="https://github.com/c4g7-dev/RelayChat/actions/workflows/build.yml"><img src="https://img.shields.io/github/actions/workflow/status/c4g7-dev/RelayChat/build.yml?style=flat-square&label=build" alt="build status"></a>
  <a href="LICENSE"><img src="https://img.shields.io/github/license/c4g7-dev/RelayChat?style=flat-square&color=6E6E6E" alt="MIT license"></a>
</p>

<p align="center">
  <img src="docs/chat.jpg" alt="Relay Chat: a Main window with lobby and game chat, and a Lifecycles window catching Conduit's service messages" width="860">
  <br>
  <sub>a <b>Main</b> window, and a <b>Lifecycles</b> window whose regex filter catches Conduit's server lifecycle messages · works on any server</sub>
</p>

<p align="center">
  <sub>made by <a href="https://github.com/c4g7-dev">c4g7</a></sub>
</p>

---

## what it does

- **chat windows**: as many as you like. Drag them by the tab bar, resize them from any edge, and they stay anchored to the nearest screen corner when the window or GUI scale changes
- **lock windows in place** from the ≡ menu or the tab settings. A locked window ignores drags and edge grabs and shows a small padlock
- **tabs** with unread markers. The **Main** tab gets everything; every other tab only gets what its filters catch
- **filters** by words or by **regex**, with *keep out of Main*, *hide*, *play sound*, *change background* and *search tooltips*
- **regex editor**: a full screen for writing patterns, with syntax colours, live validation that points at the error, snippet buttons, a test line and a live preview of which recent messages the filter would catch
- **real text fields** everywhere: click to place the caret, drag or Shift+arrows to select, double-click a word, triple-click for everything, Ctrl+A / C / X / V, Ctrl+←/→ word jumps, Ctrl+Backspace, Ctrl+Z / Ctrl+Y undo and redo
- **chat that survives server switches**: proxy moves (Velocity/Bungee) no longer wipe the windows, leaving a server keeps your tabs and your ↑ input history, and a thin `→ server · 14:32` divider marks where you landed. Optionally, the tabs are remembered across game restarts
- **clickable chat**: links, run/suggest-command, copy-to-clipboard and hover tooltips all work inside the windows, and Shift+click inserts a name
- timestamps, message markers, text shadow, per-tab background colours, combining of identical messages (`(3)`), anti-chat-clear, right-click to copy a message, vanilla's secure-chat indicator

<p align="center">
  <img src="docs/playing.jpg" alt="Relay Chat while playing: the chat is closed and new messages show in both windows over the world" width="860">
  <br>
  <sub>while you play: the chat stays closed, and new messages show up in their windows, then fade out after ten seconds</sub>
</p>

It looks and feels exactly like VelvetChat, the mod it grew out of: same panels, colours, toggles and animations.

<p align="center">
  <img src="docs/menu.jpg" alt="the window menu: new tab, new window, settings, lock position" width="49%">
  <img src="docs/settings.jpg" alt="tab settings panel" width="49%">
  <br>
  <sub>the ≡ menu of a window · a tab's settings, right inside the window</sub>
</p>

## the regex editor

<p align="center">
  <img src="docs/filter.jpg" alt="a filter with Advanced (regex) turned on" width="600">
</p>

Open it from a filter with *Advanced (regex)* on, by clicking *Include regex* or *Exclude regex*.

<p align="center">
  <img src="docs/regex-editor.jpg" alt="Relay Chat's regex editor, testing a pattern against recent chat" width="760">
</p>

- **Include / Exclude**: a message is caught when it matches *include* and not *exclude*
- **Match**: *Contains* finds the pattern anywhere; *Whole message* needs it to match the entire line (what VelvetChat always did)
- **snippets** insert common pieces or wrap the selection (`( )`, `(?: )`, `[ ]`), and **Escape** turns selected text into a literal
- **recent chat**: click a message to test it, Shift+click to insert it escaped into the pattern
- `Enter` saves, `Esc` cancels, `Tab` switches between pattern and test line

A pattern that backtracks catastrophically, such as `(?:.*,){12}X`, gives up after a few milliseconds per message instead of freezing the game.

## install

1. Install [Fabric Loader](https://fabricmc.net/use/) ≥ 0.19.5 and [Fabric API](https://modrinth.com/mod/fabric-api) for Minecraft 26.2 or 26.3
2. Drop the jar for your game version from the [releases](https://github.com/c4g7-dev/RelayChat/releases) into `mods/`: `relaychat-<version>+26.2.jar` or `relaychat-<version>+26.3.jar`. Each jar only runs on the version in its name

### coming from VelvetChat

Remove VelvetChat. Both replace the chat, so Fabric refuses to start with both installed. On first start Relay Chat imports `config/velvetchat/chat.json`, so your windows, tabs and filters come straight over. Imported regex filters keep VelvetChat's *Whole message* matching.

One behaviour change: VelvetChat's regex *Case sensitive* toggle was inverted (on meant case-*in*sensitive). Relay Chat does what the toggle says.

## keys & controls

| | |
|---|---|
| `M` | open the chat with the tab settings (rebindable: *Chat settings*) |
| drag the tab bar / body | move a window |
| drag an edge | resize |
| ≡ | new tab, new window, settings, lock / unlock, delete |
| right-click a message | copy it |
| mouse wheel | scroll (Shift: one line at a time) |
| `Esc` in settings | one page back |

## config

`config/relay/relay.json` holds everything and is safe to edit by hand. Colours are `#AARRGGBB`. If the file ever fails to parse, it is kept as `relay.json.broken` and Relay Chat starts from defaults.

*Remember chat after restart* writes `config/relay/history.json` (the last 100 messages per tab). Turning the option off deletes that file.

## build

```sh
./gradlew build          # jar for 26.2 in build/libs/
./gradlew build -Pminecraft_version=26.3 -Pfabric_api_version=0.162.0+26.3   # jar for 26.3
./gradlew test           # unit tests: text editing, regex, filters, config import
./gradlew runSelftest    # dev client that clicks through the UI and takes screenshots
```

Needs JDK 25. `runSelftest` expects a world called `RelayTest` in `run-selftest/saves/` and runs fine headless: `tools/selftest.sh` starts it under `xvfb-run` with Mesa at 1920×1080 and prints the PASS/FAIL lines. The README shots come from that run, with Sodium, Iris and Complementary Reimagined dropped into `run-selftest/`.

## credits & license

Relay Chat is made by [c4g7](https://github.com/c4g7-dev). Bugs and ideas go to the [issues](https://github.com/c4g7-dev/RelayChat/issues).

MIT, see [LICENSE](LICENSE). Relay Chat is a clean rewrite: no VelvetChat code or assets are included, it only mirrors its look and behaviour.
