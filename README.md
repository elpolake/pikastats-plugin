# PikaStats ViaProxy Plugin

Standalone source project for the PikaNetwork BedWars stats and punishment commands in ViaProxy. The optional desktop overlay is maintained separately in `pikastats-overlay`.

## Build

Requires Java 17 and a ViaProxy JAR matching the ViaProxy version you run. Put the JAR at `libs/ViaProxy-3.4.14.jar`, or set `VIAPROXY_JAR` / pass `-PviaproxyJar=...` to point at it. The ViaProxy JAR is only used to compile and is not bundled.

```powershell
.\gradlew.bat jar
```

The plugin JAR is written to `build/libs/PikaStats-1.0.0.jar`. Copy it to ViaProxy's `plugins` folder and restart ViaProxy.

## API key

PikaNetwork requires an API key for profile and leaderboard requests. In game, run `/apikey`, then set `api-key` in ViaProxy's generated `plugins/PikaStats/overlay.properties` file. Alternatively, set the `PIKA_STATS_API_KEY` environment variable before starting ViaProxy. Keep the key private; local config and key files are excluded from this repository.

## Commands

- `-stats <player> [period mode] [stat]` shows BedWars stats. Periods are `a` (all time), `w` (weekly), `m` (monthly), and `y` (yearly). Modes are `a` (all modes), `1` (solo), `2` (doubles), `3` (triples), and `4` (quads).
- `-punishments <player>` shows recent punishment counts and dates. Add `count` to show counts only, or `reason` to send each punishment reason separately.

The plugin also sends the live BedWars roster and player details to the optional overlay over a WebSocket bound to `127.0.0.1`.
