# PikaStats 2.3 — ViaProxy plugin

PikaNetwork BedWars/SkyWars stats, profiles and punishments right in chat, with colored replies in the
server's `&#rrggbb` format that always fit the 128-character chat limit. Also feeds the
[desktop overlay](https://github.com/elpolake/pikastats-overlay).

## Download

Easiest: download `PikaStats-Setup-<version>.zip` from [Releases](https://github.com/elpolake/pikastats-plugin/releases),
unzip it and double-click `Install PikaStats.bat`. It sets up ViaProxy, this plugin and the desktop overlay
(no admin rights needed). Or put `PikaStats-<version>.jar` into ViaProxy's `plugins` folder yourself.

Build from source: `gradlew build` makes `build/libs/PikaStats-<version>.jar`. It needs
`ViaProxy-3.4.14.jar` next to this folder, in `libs/`, or set with `-PviaproxyJar=<path>`.
The installer's source is in [`installer/`](installer).

## Install

1. Download `PikaStats-<version>.jar` from Releases (or build it) and put it in ViaProxy's `plugins` folder.
2. Start ViaProxy once. It creates `plugins/PikaStats/config.properties`
   (settings from the old `overlay.properties`, including your API key, are migrated automatically).
3. Get a key in game with `/apikey` and set `api-key=` (or the `PIKA_STATS_API_KEY` environment variable).
4. Type `-ps reload` in game (or restart ViaProxy).

## API keys & rate limits

PikaNetwork allows about **120 API requests per minute per key**. PikaStats:

- spreads requests over **any number of keys**, always using the one with the most quota left (read from the
  API's rate-limit headers), and never sends a request a key has no quota for, so you don't get 429s;
- queues requests by priority: commands you type first, overlay clicks next, background lobby loading last —
  and background loading leaves part of every key's quota free so your own `-stats` is answered instantly;
- caches results (lifetime stats 10 min, weekly 3 min, profiles 30 min) and, if the API is rate limiting or down,
  answers with the last known result instead of an error;
- sets aside keys that the API rejects, and cools down a key that gets rate limited anyway.

Manage keys in **ViaProxy's window → PikaStats tab** (add, remove, test, live quota per key), or put them
comma-separated in `api-keys=` in `config.properties`. `-ps keys` shows the same status in game.

## Commands

**Your own lookups only show to you**: the command is not sent and the answer appears only in your chat.

- End a lookup with `global` (or `g`) to send the command and the answer to **the chat you are in right now**,
  party or global, exactly as if you had typed them. PikaStats reads the server's echo of your command to know
  where it landed, and uses colors only if that was party chat.
- End it with `party` to send both to party chat. Only you can use these keywords.

**Other players' lookups** (`-stats`, `-sw`, `-profile`, `-compare`, `-punishments`):

- `-partycommands on|off` (default on): party members' commands are answered in party chat, with colors.
- `-global on|off` (default off): commands typed in global chat are answered in global chat, without colors
  (PikaNetwork shows color codes as text there). `!` is added only while your plain messages go to party chat.

**Long answers are split** into several messages instead of being cut, at least 500 ms apart.
PikaNetwork limits a 1.8 client to 100 characters per message and blocks every non-ASCII character, so
PikaStats only sends ASCII there.

| Command | What it does |
|---|---|
| `-stats <player> [mode] [period] [view] [q]` | BedWars stats (aliases `-s`, `-bw`). Arguments in any order. |
| `-sw <player> [solo\|doubles] [period]` | SkyWars stats |
| `-compare <p1> <p2> [mode] [period] [sw]` | Head to head, winner of each stat in green (`-vs`) |
| `-profile <player>` | Network level, rank, clan, last seen, friends (`-level`) |
| `-punishments <player> [count\|reason]` | Ban/mute/warn/kick history (`-bans`) |
| `-threats [global\|party]` | Strongest players in your current BedWars lobby |
| `-partycommands on\|off` | Let party members use lookups (answers in party chat) |
| `-global on\|off` | Let everyone use lookups (answers in global chat with `!`) |
| `-allow party\|global <cmds>` | Which lookups others may use there: `stats sw compare profile punishments`, `all` or `none`. Also editable in the overlay (⚙) |
| `-style [name]` / `-styles` | Pick a chat color style, with previews |
| `-grad [#from] [#to] [#more] <text>` | Send gradient text, auto-fitted to 128 chars. `-rainbow`, `-fade`, `-pgrad` (party) |
| `-party list\|add\|remove\|clear` | Saved party list (PARTY badges in the overlay) |
| `-ps status\|keys\|reload\|colors <hex\|legacy\|none>\|clear` | Settings |
| `-help` | Everything above, in game |

**Modes:** `solo`/`1`/`1s`, `doubles`/`duos`/`2`, `triples`/`trios`/`3`, `quads`/`4` (default: overall)
**Periods:** `w`/`weekly`, `m`/`monthly`, `y`/`yearly`, `a`/`lifetime` (default: lifetime). The old `-stats Steve w 4` syntax still works.
**Views:** `full` (3 lines), `lb` (leaderboard places), `combat`, `fkdr`, `wlr`, `kdr`, `bblr`, `wins`, `losses`,
`finals`, `fd`, `kills`, `deaths`, `games`, `beds`, `ws`, `wr` (win rate), `fpg` (finals per game), `acc`.

Examples: `-stats isabe5lgod4117` (only you see it), `-stats isabe5lgod4117 quads weekly full global`, `-vs Steve Alex 4 party`.


## Chat styles

`midnight` (default, dark modern violet), `obsidian`, `eclipse` (white→black fade), `void`, `neon`, `tokyo`,
`dracula`, `crimson`, `ocean`, `aurora`, `emerald`, `toxic`, `sunset`, `gold`, `frost`, `sakura`, `classic`, `plain`.
Ratios are colored by tier (low → insane). Example (midnight, 127/128 chars):

```
&#a78bfa* &#f5f3ffIsabe5lGod4117&#8a87a8 | BW Overall | FKDR &#fb923c4.57&#8a87a8 | WLR &#facc151.46&#8a87a8 | KDR &#e5e7eb1.44
```

When a line would be too long the plugin merges similar colors, then drops the least important stats, and only
truncates as a last resort. Fancy symbols (✦ ┃ ⚡ …) are only used once the server is known to allow them (list them in
`extra-allowed-chars`, then `-ps reload`); until then ASCII fallbacks (`*`, `|`) are used. Characters a vanilla server
kicks for (control characters, `§`) are never sent.

## Settings (`config.properties`)

| Key | Default | |
|---|---|---|
| `api-key` | | PikaNetwork API key |
| `api-keys` | | More keys, comma separated (the GUI tab writes this) |
| `chat-style` | `midnight` | Chat color style |
| `color-mode` | `hex` | Party chat colors: `hex`, `legacy` (`&a`, 2 chars per color) or `none`. With `hex`, a message switches to `legacy` codes when that saves a message |
| `public-color-mode` | `none` | Colors in public/global chat (PikaNetwork shows them as text) |
| `chat-max-length` | `128` | Server chat limit including `/p c ` and color codes |
| `chat-delay-ms` | `500` | Pause between messages the plugin sends |
| `party-commands` | `true` | Answer party members' lookups in party chat |
| `global-commands` | `false` | Answer everyone's lookups in global chat (`!`) |
| `others-cooldown-seconds` | `4` | Cooldown for the same command from others |
| `extra-allowed-chars` | | Extra characters to treat as allowed |
| `websocket-port` | `8765` | Local port for the desktop overlay |
| `fallback-mode` | `QUADS` | Overlay mode when it cannot be detected |
| `overlay-profiles` | `true` | Load level/rank for overlay players |

## Build & test

Needs Java 17+. The build uses `../ViaProxy-3.4.14.jar` (or `libs/…`, `-PviaproxyJar=…`, `VIAPROXY_JAR`).

```powershell
.\gradlew.bat assemble test
```

With `PIKA_STATS_API_KEY` set, `.\gradlew.bat test -PshowOutput` also runs a live test that renders real stats
(default player `isabe5lgod4117`, override with `PIKA_STATS_TEST_PLAYER`) in every style.
