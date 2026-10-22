# PikaStats ViaProxy Plugin

PikaStats adds PikaNetwork BedWars stats and punishment lookups to ViaProxy. It can also send the live BedWars roster and detailed player data to the optional [PikaStats Desktop Overlay](https://github.com/elpolake/pikastats-overlay).

## What it does

- Looks up BedWars stats by player, including wins, losses, kills, deaths, final kills, final deaths, FKDR, WLR, and KDR.
- Supports all-time, weekly, monthly, and yearly stats across all modes, solo, doubles, triples, or quads.
- Looks up ban, mute, warning, and kick counts plus recent punishment dates and reasons.
- Provides a local WebSocket feed for the desktop overlay. The feed listens on 127.0.0.1 by default.

## Install

1. Download **PikaStats-1.0.0.jar** from the [v1.0.0 release](https://github.com/elpolake/pikastats-plugin/releases/tag/v1.0.0).
2. Put the JAR in ViaProxy's plugins folder.
3. Start or restart ViaProxy once so it creates **plugins/PikaStats/overlay.properties**.
4. In PikaNetwork, get your API key with **/apikey**.
5. Set the **api-key** property in **plugins/PikaStats/overlay.properties**. The key stays on your computer; do not post it in chat, commit it, or share it.
6. Restart ViaProxy to load the key.

The plugin creates **overlay.properties** with a blank key and these defaults:

~~~properties
api-key=
websocket-port=8765
fallback-mode=QUADS
~~~

You can set the environment variable **PIKA_STATS_API_KEY** before starting ViaProxy instead. It takes precedence over the key in the properties file.

The other properties are:

- **websocket-port** selects the local port for the overlay feed. It defaults to **8765** and must be in the range 1024 to 65535.
- **fallback-mode** selects the BedWars mode used by the overlay if ViaProxy cannot identify the current mode. Set it to **SOLO**, **DOUBLES**, **TRIPLES**, or **QUADS**; the default is **QUADS**.

## Commands

Commands are typed in Minecraft chat. Results are sent to public chat, so do not include your API key in a command.

### BedWars stats

~~~text
-stats <player> [period mode] [stat]
~~~

The player name must contain 1 to 16 letters, numbers, or underscores. If you omit the period and mode, the plugin uses all-time and all modes. To choose a period, provide both a period and a mode.

Periods:

- **a** - all time
- **w** - weekly
- **m** - monthly
- **y** - yearly

Modes:

- **a** - all modes
- **1** - solo
- **2** - doubles
- **3** - triples
- **4** - quads

If you omit the stat selector, the plugin returns a summary. Selectors are **wins** or **win**, **losses** or **loss**, **final_kills** or **final_kill**, **final_deaths** or **final_death**, **kills** or **kill**, **deaths** or **death**, **fkdr**, **wlr**, and **kdr**. Final-kill aliases **final**, **finals**, **finalkill**, and **finalkills** are also accepted; final-death aliases **finaldeath** and **finaldeaths** are accepted. Hyphens can replace underscores.

Examples:

~~~text
-stats Steve
-stats Steve w 4
-stats Steve fkdr
-stats Steve m 2 wins
~~~

### Punishments

~~~text
-punishments <player> [count|reason]
~~~

- **-punishments <player>** shows counts and up to three recent punishment types and dates.
- Add **count** to show only counts in this format: **bans: N | mutes: N | warns: N | kicks: N**.
- Add **reason** to send the counts, then each available punishment in its own public chat message. Each entry includes the punishment type, date in **yy-mm-dd** format, and reason. Messages are spaced 250 ms apart.

Reason text is shortened to fit the chat limit, filtered text is redacted, and URLs are obfuscated because PikaNetwork chat may reject some words or links. The server can still reject a reason. Use this option knowing the output is public.

Examples:

~~~text
-punishments Steve
-punishments Steve count
-punishments Steve reason
~~~

## Desktop overlay

Install and run the [PikaStats Desktop Overlay](https://github.com/elpolake/pikastats-overlay) separately. The plugin provides the live BedWars roster and player details over a local WebSocket. Its default port is **8765**; set **websocket-port** in **overlay.properties** to match the overlay's **config.json** if you change it.

## Build from source

You need Java 17 and a ViaProxy JAR matching the version you run. The build uses the ViaProxy JAR to compile and does not bundle it. Put it at **libs/ViaProxy-3.4.14.jar**, set **VIAPROXY_JAR**, or pass a path with **-PviaproxyJar**.

PowerShell:

~~~powershell
.\gradlew.bat jar
~~~

Linux or macOS:

~~~sh
./gradlew jar
~~~

The output is **build/libs/PikaStats-1.0.0.jar**.

## Troubleshooting

- **The API says a key is required:** check the key in **plugins/PikaStats/overlay.properties** or **PIKA_STATS_API_KEY**, then restart ViaProxy.
- **The overlay is disconnected:** make sure the plugin started, then check the WebSocket port in both plugin and overlay settings.
- **A player has no stats:** the player may be nicked, have stats hidden, or not have an available profile.
- **Requests are rate limited:** wait before retrying. Repeated stats requests are cached briefly.
- **A punishment reason is rejected:** the plugin filters some text and hides URLs, but PikaNetwork may still reject server-provided text.
