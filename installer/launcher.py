"""PIKA//CTRL - setup wizard and launcher for ViaProxy, the PikaStats plugin and overlay.

Modes:
  setup      - started by "Install PikaStats.bat" from a setup package: installs everything
  installed  - run from %LOCALAPPDATA%/PikaStats: the everyday launcher for players
"""
from __future__ import annotations

import json
import os
import random
import re
import shutil
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import webbrowser
from pathlib import Path

ROOT = Path(__file__).resolve().parent
SETTINGS_FILE = ROOT / "launcher.json"
STATE_FILE = ROOT / ".launcher-pids.json"
WINDOWS = os.name == "nt"
VERSION = "3.0"
INSTALL_DIR = Path(os.environ.get("PIKASTATS_HOME")
                   or Path(os.environ.get("LOCALAPPDATA", str(Path.home()))) / "PikaStats")

DEFAULTS = {
    "viaproxy_dir": str(ROOT / "viaproxy"),
    "java": "java",
    "python": "",
    "github_repo": "elpolake/pikastats-plugin",
    "viaproxy_gui": True,  # True = ViaProxy window + log window, False = headless (log window only)
    "selected": ["viaproxy", "overlay"],
    "boot_animation": True,
    "accent": "green",
}

# ----------------------------------------------------------------------------- terminal

if WINDOWS:
    os.system("")  # Enables ANSI escape codes in the classic console.
    try:
        sys.stdout.reconfigure(encoding="utf-8")
    except Exception:
        pass

ACCENTS = {
    "green": ((0, 255, 140), (0, 160, 60)),
    "cyan": ((0, 240, 255), (40, 90, 255)),
    "purple": ((200, 120, 255), (110, 40, 220)),
    "red": ((255, 70, 90), (140, 0, 30)),
    "amber": ((255, 200, 60), (230, 100, 0)),
    "mono": ((255, 255, 255), (110, 110, 110)),
}


def rgb(r: int, g: int, b: int) -> str:
    return f"\x1b[38;2;{r};{g};{b}m"


RESET, BOLD, DIM = "\x1b[0m", "\x1b[1m", "\x1b[2m"
GREEN, RED, YELLOW, GRAY, WHITE = rgb(80, 255, 140), rgb(255, 85, 95), rgb(255, 210, 80), rgb(120, 130, 140), rgb(235, 240, 245)


class Theme:
    def __init__(self, name: str):
        self.top, self.bottom = ACCENTS.get(name, ACCENTS["green"])

    def at(self, t: float) -> str:
        return rgb(*(round(a + (b - a) * t) for a, b in zip(self.top, self.bottom)))

    @property
    def main(self) -> str:
        return self.at(0.0)

    @property
    def soft(self) -> str:
        return self.at(0.6)


def clear() -> None:
    os.system("cls" if WINDOWS else "clear")


def term_width() -> int:
    return max(70, min(110, shutil.get_terminal_size((96, 30)).columns))


def getkey(prompt: str = "") -> str:
    print(prompt, end="", flush=True)
    if WINDOWS:
        import msvcrt
        key = msvcrt.getwch()
        if key in ("\x00", "\xe0"):
            msvcrt.getwch()
            return ""
        print()
        return key.lower()
    return (input().strip()[:1] or "\r").lower()


def ask(prompt: str) -> str:
    try:
        return input(prompt).strip()
    except EOFError:
        return ""


def typewrite(text: str, delay: float = 0.004) -> None:
    for char in text:
        sys.stdout.write(char)
        sys.stdout.flush()
        if char not in "\x1b[;m0123456789":
            time.sleep(delay)
    print()


BANNER = r"""
 ██████╗ ██╗██╗  ██╗ █████╗     ██╗██╗ ██████╗████████╗██████╗ ██╗
 ██╔══██╗██║██║ ██╔╝██╔══██╗   ██╔╝██╔╝██╔════╝╚══██╔══╝██╔══██╗██║
 ██████╔╝██║█████╔╝ ███████║  ██╔╝██╔╝ ██║        ██║   ██████╔╝██║
 ██╔═══╝ ██║██╔═██╗ ██╔══██║ ██╔╝██╔╝  ██║        ██║   ██╔══██╗██║
 ██║     ██║██║  ██╗██║  ██║██╔╝██╔╝   ╚██████╗   ██║   ██║  ██║███████╗
 ╚═╝     ╚═╝╚═╝  ╚═╝╚═╝  ╚═╝╚═╝ ╚═╝     ╚═════╝   ╚═╝   ╚═╝  ╚═╝╚══════╝"""


def banner(theme: Theme) -> None:
    lines = BANNER.strip("\n").split("\n")
    for index, line in enumerate(lines):
        print(theme.at(index / max(1, len(lines) - 1)) + BOLD + line + RESET)
    parts = "viaproxy · pikastats · overlay"
    tag = f"  pikastats control center v{VERSION}  //  {parts}"
    print(GRAY + tag + RESET)


def rule(theme: Theme, title: str = "") -> None:
    width = term_width() - 2
    if title:
        text = f"─[ {title} ]"
        print(theme.soft + text + "─" * max(0, width - len(text)) + RESET)
    else:
        print(theme.soft + "─" * width + RESET)


def boot(theme: Theme) -> None:
    clear()
    width = term_width()
    glyphs = "01アイウエオカキクケコサシスセソタチツテト#$%&*+=<>"
    for row in range(10):
        print(theme.at(row / 10) + "".join(random.choice(glyphs) if random.random() < 0.35 else " " for _ in range(width - 2)) + RESET)
        time.sleep(0.02)
    clear()
    banner(theme)
    print()
    for step in ("loading modules", "scanning local ports", "reading viaproxy config", "linking pikastats bridge",
                 "warming up stats engine", "establishing secure channel"):
        typewrite(f"{theme.soft}[{theme.main}+{theme.soft}]{RESET} {step}{GRAY}{'.' * random.randint(3, 8)}{RESET} {GREEN}OK{RESET}", 0.002)
        time.sleep(0.05)
    time.sleep(0.25)


# ----------------------------------------------------------------------------- settings & state

def load_settings() -> dict:
    settings = dict(DEFAULTS)
    if SETTINGS_FILE.exists():
        try:
            settings.update(json.loads(SETTINGS_FILE.read_text(encoding="utf-8")))
            settings.pop("viaproxy_mode", None)  # Replaced by viaproxy_gui in 2.0.1.
            if settings.get("github_repo") == "elpolake/pikastats":  # That repo never existed.
                settings["github_repo"] = SETUP_REPO_DEFAULT
        except (OSError, ValueError):
            pass
    return settings


def save_settings(settings: dict) -> None:
    SETTINGS_FILE.write_text(json.dumps(settings, indent=2) + "\n", encoding="utf-8")


def load_pids() -> dict:
    try:
        return json.loads(STATE_FILE.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return {}


def save_pids(pids: dict) -> None:
    STATE_FILE.write_text(json.dumps(pids), encoding="utf-8")


def pid_alive(pid: int) -> bool:
    if not pid:
        return False
    if WINDOWS:
        raw = subprocess.run(["tasklist", "/FI", f"PID eq {pid}", "/NH"], capture_output=True).stdout or b""
        return f" {pid} " in raw.decode("ascii", errors="ignore")
    try:
        os.kill(pid, 0)
        return True
    except OSError:
        return False


def port_open(port: int, host: str = "127.0.0.1") -> bool:
    try:
        with socket.create_connection((host, port), timeout=0.25):
            return True
    except OSError:
        return False


# ----------------------------------------------------------------------------- components

def viaproxy_dir(settings: dict) -> Path:
    return Path(settings["viaproxy_dir"]).expanduser()


def viaproxy_jar(settings: dict) -> Path | None:
    jars = sorted(viaproxy_dir(settings).glob("ViaProxy*.jar"))
    if jars:
        return jars[-1]
    fallback = sorted(ROOT.glob("ViaProxy*.jar"))
    return fallback[-1] if fallback else None


def viaproxy_bind_port(settings: dict) -> int:
    config = viaproxy_dir(settings) / "viaproxy.yml"
    try:
        for line in config.read_text(encoding="utf-8").splitlines():
            if line.strip().startswith("bind-address:"):
                return int(line.split(":")[-1].strip().strip('"'))
    except (OSError, ValueError):
        pass
    return 25568


def plugin_config(settings: dict) -> dict:
    values = {}
    folder = viaproxy_dir(settings) / "plugins" / "PikaStats"
    for name in ("overlay.properties", "config.properties"):  # Later files win.
        path = folder / name
        if path.exists():
            for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
                if "=" in line and not line.lstrip().startswith("#"):
                    key, value = line.split("=", 1)
                    values[key.strip()] = value.strip().replace("\\:", ":").replace("\\=", "=")
    return values


def ws_port(settings: dict) -> int:
    try:
        return int(plugin_config(settings).get("websocket-port", "8765"))
    except ValueError:
        return 8765




COMPONENTS = {
    "viaproxy": {"name": "VIAPROXY", "desc": "proxy + PikaStats plugin"},
    "overlay": {"name": "OVERLAY", "desc": "desktop stats overlay (F8 hide)"},
}


def overlay_dir() -> Path:
    return ROOT / "overlay"


def overlay_python(settings: dict) -> str:
    if not settings.get("python"):
        return "py -3" if WINDOWS else "python3"
    return f'"{settings["python"]}"'


def command_for(key: str, settings: dict) -> tuple[list[str] | str, Path] | None:
    if key == "viaproxy":
        jar = viaproxy_jar(settings)
        if jar is None:
            return None
        directory = viaproxy_dir(settings)
        java = settings.get("java") or "java"
        java = f'"{java}"' if " " in java and not java.startswith('"') else java
        if settings.get("viaproxy_gui", True):
            # java (not javaw) keeps the log console next to ViaProxy's own window.
            return f'{java} -jar "{jar}"', directory
        return f'{java} -jar "{jar}" config viaproxy.yml', directory
    if key == "overlay":
        return f'{overlay_python(settings)} "{overlay_dir() / "main.py"}"', overlay_dir()
    return None


def launch(key: str, settings: dict, theme: Theme) -> None:
    pids = load_pids()
    if pid_alive(pids.get(key, 0)):
        print(f"  {YELLOW}[!]{RESET} {COMPONENTS[key]['name']} already running (pid {pids[key]})")
        return
    spec = command_for(key, settings)
    if spec is None:
        print(f"  {RED}[x]{RESET} {COMPONENTS[key]['name']}: ViaProxy jar not found in {viaproxy_dir(settings)}")
        return
    command, cwd = spec
    title = f"PIKA :: {COMPONENTS[key]['name']}"
    if WINDOWS and isinstance(command, str):
        # Own console window that stays open so logs and crashes stay readable.
        process = subprocess.Popen(f'cmd /k "title {title} && color 0A && {command}"', cwd=cwd,
                                   creationflags=subprocess.CREATE_NEW_CONSOLE)
    elif WINDOWS:
        process = subprocess.Popen(command, cwd=cwd, creationflags=subprocess.CREATE_NEW_PROCESS_GROUP)
    else:
        process = subprocess.Popen(command if isinstance(command, list) else command.split(), cwd=cwd)
    pids[key] = process.pid
    save_pids(pids)
    typewrite(f"  {theme.main}[>]{RESET} {BOLD}{COMPONENTS[key]['name']:<9}{RESET} {GRAY}pid {process.pid}{RESET}  {GREEN}LAUNCHED{RESET}", 0.003)


def stop(key: str) -> bool:
    pids = load_pids()
    pid = pids.pop(key, 0)
    save_pids(pids)
    if not pid_alive(pid):
        return False
    if WINDOWS:
        subprocess.run(["taskkill", "/PID", str(pid), "/T", "/F"], capture_output=True)
    else:
        os.kill(pid, 15)
    return True


def wait_for(port: int, seconds: float) -> bool:
    deadline = time.time() + seconds
    while time.time() < deadline:
        if port_open(port):
            return True
        time.sleep(0.4)
    return False


def start_selected(settings: dict, theme: Theme) -> None:
    selected = [key for key in COMPONENTS if key in settings["selected"]]
    if not selected:
        print(f"  {YELLOW}[!]{RESET} nothing selected")
        return
    rule(theme, "DEPLOY")
    for key in selected:
        launch(key, settings, theme)
        if key == "viaproxy" and len(selected) > 1:
            spinner(f"waiting for PikaStats bridge on :{ws_port(settings)}", lambda: port_open(ws_port(settings)), 45, theme)
    print(f"\n  {GREEN}[✓]{RESET} deploy finished. connect Minecraft to {BOLD}localhost:{viaproxy_bind_port(settings)}{RESET}")


def spinner(label: str, check, seconds: float, theme: Theme) -> bool:
    frames = "⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏"
    deadline = time.time() + seconds
    index = 0
    while time.time() < deadline:
        if check():
            print(f"\r  {GREEN}[✓]{RESET} {label}{' ' * 12}")
            return True
        print(f"\r  {theme.main}{frames[index % len(frames)]}{RESET} {label} {GRAY}{int(deadline - time.time())}s{RESET}  ", end="", flush=True)
        index += 1
        time.sleep(0.12)
    print(f"\r  {YELLOW}[!]{RESET} {label} {GRAY}(timed out, continuing){RESET}")
    return False


# ----------------------------------------------------------------------------- tools

TIERS = [(12, rgb(217, 70, 239)), (7, rgb(244, 63, 94)), (4, rgb(251, 146, 60)), (2, rgb(250, 204, 21)), (1, WHITE), (0, GRAY)]


def tier_color(value: float, scale: float = 1.0) -> str:
    for threshold, color in TIERS:
        if value >= threshold * scale:
            return color
    return GRAY


def api_get(url: str, key: str) -> tuple[int, dict | None]:
    request = urllib.request.Request(url, headers={"X-Api-Key": key, "User-Agent": "PikaStats-Launcher/2.0",
                                                   "Accept": "application/json"})
    try:
        with urllib.request.urlopen(request, timeout=12) as response:
            return response.status, json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as error:
        return error.code, None
    except (urllib.error.URLError, TimeoutError, ValueError):
        return 0, None


def api_keys(settings: dict) -> list[str]:
    config = plugin_config(settings)
    raw = ",".join([os.environ.get("PIKA_STATS_API_KEY", ""), config.get("api-key", ""), config.get("api-keys", "")])
    keys: list[str] = []
    for key in raw.replace(";", ",").replace(" ", ",").split(","):
        if key.strip() and key.strip() not in keys:
            keys.append(key.strip())
    return keys


def lookup(settings: dict, theme: Theme) -> None:
    keys = api_keys(settings)
    key = random.choice(keys) if keys else ""
    if not key:
        print(f"  {RED}[x]{RESET} no API key. run /apikey in game and put it in plugins/PikaStats/config.properties")
        return
    name = ask(f"  {theme.main}target{RESET} > ")
    if not name.replace("_", "").isalnum() or len(name) > 16:
        print(f"  {RED}[x]{RESET} invalid username")
        return
    mode = ask(f"  {theme.main}mode{RESET} [all/solo/doubles/triples/quads] > ").lower() or "all"
    mode_api = {"all": "ALL_MODES", "solo": "SOLO", "1": "SOLO", "doubles": "DOUBLES", "2": "DOUBLES",
                "triples": "TRIPLES", "3": "TRIPLES", "quads": "QUAD", "4": "QUAD"}.get(mode, "ALL_MODES")
    quoted = urllib.parse.quote(name)
    typewrite(f"  {GRAY}[*] querying stats.pika-network.net for {name}...{RESET}", 0.004)
    status, stats = api_get(f"https://stats.pika-network.net/api/profile/{quoted}/leaderboard?type=bedwars&interval=total&mode={mode_api}", key)
    _, profile = api_get(f"https://stats.pika-network.net/api/profile/{quoted}", key)
    if status in (400, 404):
        print(f"  {RED}[x]{RESET} player not found")
        return
    if status == 401:
        print(f"  {RED}[x]{RESET} API key rejected")
        return
    if not stats:
        print(f"  {RED}[x]{RESET} lookup failed (HTTP {status})")
        return

    def value(stat: str) -> int:
        entries = (stats.get(stat) or {}).get("entries") or []
        try:
            return int(entries[0]["value"]) if entries else 0
        except (KeyError, ValueError, TypeError):
            return 0

    def place(stat: str) -> str:
        entries = (stats.get(stat) or {}).get("entries") or []
        return f"#{entries[0]['place']:,}" if entries and entries[0].get("place") else ""

    ratio = lambda a, b: a / max(1, b) if a else 0.0
    fk, fd, w, l, k, d = (value(s) for s in ("Final kills", "Final deaths", "Wins", "Losses", "Kills", "Deaths"))
    display = (profile or {}).get("username", name)
    level = ((profile or {}).get("rank") or {}).get("level", "?")
    ranks = ", ".join(r.get("displayName", "") for r in (profile or {}).get("ranks", []) if r.get("displayName")) or "-"
    clan = ((profile or {}).get("clan") or {}).get("name") or "-"
    print()
    rule(theme, f"TARGET :: {display}")
    print(f"  {GRAY}level{RESET} {BOLD}{level}{RESET}   {GRAY}rank{RESET} {ranks}   {GRAY}clan{RESET} {clan}   {GRAY}mode{RESET} {mode_api}")
    print()
    for label, number, scale in (("FKDR", ratio(fk, fd), 1.0), ("WLR", ratio(w, l), 0.6), ("KDR", ratio(k, d), 0.5)):
        bar = "█" * min(30, int(number * 30 / (12 * scale))) or "▏"
        print(f"  {GRAY}{label:<5}{RESET} {tier_color(number, scale)}{BOLD}{number:>6.2f}{RESET}  {tier_color(number, scale)}{bar}{RESET}")
    print()
    rows = [("Wins", "Wins"), ("Losses", "Losses"), ("Final kills", "Final kills"), ("Final deaths", "Final deaths"),
            ("Beds broken", "Beds destroyed"), ("Kills", "Kills"), ("Games", "Games played"), ("Best winstreak", "Highest winstreak reached")]
    for index in range(0, len(rows), 2):
        cells = []
        for label, stat in rows[index:index + 2]:
            cells.append(f"{GRAY}{label:<15}{RESET}{BOLD}{value(stat):>8,}{RESET} {DIM}{place(stat):<9}{RESET}")
        print("  " + "    ".join(cells))
    rule(theme)


def status_report(settings: dict, theme: Theme) -> None:
    rule(theme, "STATUS")
    pids = load_pids()
    checks = [
        ("viaproxy", "VIAPROXY", viaproxy_bind_port(settings)),
        ("viaproxy", "PIKASTATS WS", ws_port(settings)),
    ]
    for key, label, port in checks:
        up = port_open(port)
        print(f"  {GREEN + '● ONLINE ' if up else RED + '○ OFFLINE'}{RESET}  {label:<15} {GRAY}127.0.0.1:{port}{RESET}")
    overlay = pid_alive(pids.get("overlay", 0))
    print(f"  {GREEN + '● ONLINE ' if overlay else GRAY + '○ STOPPED'}{RESET}  {'OVERLAY':<15} {GRAY}pid {pids.get('overlay', '-')}{RESET}")
    jar = viaproxy_jar(settings)
    plugins = sorted(p.name for p in (viaproxy_dir(settings) / "plugins").glob("*.jar"))
    print(f"\n  {GRAY}viaproxy jar{RESET}  {jar or RED + 'missing' + RESET}")
    print(f"  {GRAY}plugins{RESET}       {', '.join(plugins) or '-'}")
    count = len(api_keys(settings))
    print(f"  {GRAY}api keys{RESET}      {GREEN + f'{count} key(s), ~{count * 120} requests/min' if count else RED + 'MISSING (key a in the menu)'}{RESET}")
    print(f"  {GRAY}java{RESET}          {settings.get('java')}")
    print(f"  {GRAY}install{RESET}       {ROOT}")
    rule(theme)


def kill_all(theme: Theme) -> None:
    rule(theme, "KILL SWITCH")
    for key in COMPONENTS:
        stopped = stop(key)
        print(f"  {RED + '[■]' if stopped else GRAY + '[-]'}{RESET} {COMPONENTS[key]['name']:<9} {'terminated' if stopped else 'not running'}")




def open_path(path: Path) -> None:
    if not path.exists():
        print(f"  {RED}[x]{RESET} not found: {path}")
        return
    if WINDOWS:
        os.startfile(str(path))
    else:
        subprocess.Popen(["xdg-open", str(path)])


def overlay_theme(theme: Theme) -> None:
    config_path = overlay_dir() / "config.json"
    sys.path.insert(0, str(overlay_dir()))
    try:
        from themes import THEMES  # type: ignore
    except Exception:
        print(f"  {RED}[x]{RESET} could not load overlay themes")
        return
    names = list(THEMES)
    for index, name in enumerate(names, 1):
        print(f"  {theme.main}{index:>2}{RESET} {THEMES[name]['label']}")
    choice = ask(f"  {theme.main}theme #{RESET} > ")
    if not choice.isdigit() or not 1 <= int(choice) <= len(names):
        return
    try:
        config = json.loads(config_path.read_text(encoding="utf-8")) if config_path.exists() else {}
    except ValueError:
        config = {}
    config.setdefault("overlay", {})["theme"] = names[int(choice) - 1]
    config_path.write_text(json.dumps(config, indent=2) + "\n", encoding="utf-8")
    print(f"  {GREEN}[✓]{RESET} overlay theme -> {THEMES[names[int(choice) - 1]]['label']} (restart the overlay)")


def settings_menu(settings: dict, theme: Theme) -> Theme:
    while True:
        clear()
        banner(theme)
        rule(theme, "SETTINGS")
        print(f"  {theme.main}1{RESET} viaproxy folder   {GRAY}{settings['viaproxy_dir']}{RESET}")
        print(f"  {theme.main}2{RESET} viaproxy window   {BOLD}{'gui + logs' if settings.get('viaproxy_gui', True) else 'headless (logs only)'}{RESET}")
        print(f"  {theme.main}4{RESET} boot animation    {BOLD}{'on' if settings['boot_animation'] else 'off'}{RESET}")
        print(f"  {theme.main}5{RESET} accent color      {BOLD}{settings['accent']}{RESET} {GRAY}({', '.join(ACCENTS)}){RESET}")
        print(f"  {theme.main}7{RESET} update source     {GRAY}github.com/{settings.get('github_repo')}{RESET}")
        print(f"  {theme.main}b{RESET} back")
        key = getkey(f"\n  {theme.main}root@pika{RESET}:{GRAY}~/settings{RESET}$ ")
        if key == "1":
            value = ask("  new folder > ")
            if value:
                settings["viaproxy_dir"] = value.strip('"')
        elif key == "2":
            settings["viaproxy_gui"] = not settings.get("viaproxy_gui", True)
        elif key == "4":
            settings["boot_animation"] = not settings["boot_animation"]
        elif key == "5":
            names = list(ACCENTS)
            settings["accent"] = names[(names.index(settings["accent"]) + 1) % len(names)] if settings["accent"] in names else names[0]
            theme = Theme(settings["accent"])
        elif key == "7":
            value = ask("  GitHub repo (owner/name) > ").strip()
            if value.count("/") == 1:
                settings["github_repo"] = value
        elif key in ("b", "q", "\x1b", "\r"):
            save_settings(settings)
            return theme
        save_settings(settings)


# ----------------------------------------------------------------------------- installer

SETUP_REPO_DEFAULT = "elpolake/pikastats-plugin"
VIAPROXY_RELEASES = "https://api.github.com/repos/ViaVersion/ViaProxy/releases/latest"
JAVA_URL = "https://api.adoptium.net/v3/binary/latest/21/ga/windows/x64/jre/hotspot/normal/eclipse?project=jdk"
USER_AGENT = {"User-Agent": "PikaStats-Setup/" + VERSION}


def http_json(url: str) -> dict | None:
    try:
        with urllib.request.urlopen(urllib.request.Request(url, headers={**USER_AGENT, "Accept": "application/json"}),
                                    timeout=20) as response:
            return json.loads(response.read().decode("utf-8"))
    except (urllib.error.URLError, TimeoutError, ValueError):
        return None


def download(url: str, target: Path, label: str, theme: Theme) -> bool:
    """Downloads with a progress bar; retries dropped connections a few times before giving up."""
    for attempt in range(1, 4):
        if download_once(url, target, label, theme):
            return True
        if attempt < 3:
            wait = 3 * attempt
            print(f"  {YELLOW}[~]{RESET} retrying in {wait}s (attempt {attempt + 1}/3)...")
            time.sleep(wait)
    return False


def download_once(url: str, target: Path, label: str, theme: Theme) -> bool:
    """One download attempt into target (via a .part file, so a cut download never counts)."""
    target.parent.mkdir(parents=True, exist_ok=True)
    partial = target.with_name(target.name + ".part")
    try:
        with urllib.request.urlopen(urllib.request.Request(url, headers=USER_AGENT), timeout=30) as response, \
                open(partial, "wb") as output:
            total = int(response.headers.get("Content-Length") or 0)
            done = 0
            while True:
                chunk = response.read(256 * 1024)
                if not chunk:
                    break
                output.write(chunk)
                done += len(chunk)
                if total:
                    filled = int(30 * done / total)
                    print(f"\r  {theme.main}[{'█' * filled}{'·' * (30 - filled)}]{RESET} {label} "
                          f"{done / 1e6:5.1f}/{total / 1e6:.1f} MB ", end="", flush=True)
                else:
                    print(f"\r  {theme.main}[...]{RESET} {label} {done / 1e6:5.1f} MB ", end="", flush=True)
        print()
        partial.replace(target)
        return True
    except (urllib.error.URLError, TimeoutError, OSError) as error:
        print(f"\n  {RED}[x]{RESET} download failed: {error}")
        partial.unlink(missing_ok=True)
        return False


def java_major(java: str) -> int:
    try:
        output = subprocess.run([java, "-version"], capture_output=True, text=True, timeout=20)
        text = output.stderr + output.stdout
        for token in text.replace('"', " ").split():
            if token[:1].isdigit():
                return int(token.split(".")[0])
    except (OSError, subprocess.SubprocessError, ValueError):
        pass
    return 0


def find_java(settings: dict) -> str | None:
    candidates = [settings.get("java", "")]
    candidates += [str(path) for path in sorted((ROOT / "runtime" / "java").glob("*/bin/java.exe"))]
    candidates.append("java")
    for candidate in candidates:
        if candidate and java_major(candidate) >= 17:
            return candidate
    return None


def install_java(settings: dict, theme: Theme) -> bool:
    found = find_java(settings)
    if found:
        print(f"  {GREEN}[✓]{RESET} Java found: {GRAY}{found}{RESET}")
        settings["java"] = found
        return True
    print(f"  {YELLOW}[~]{RESET} Java 17+ not found - downloading a portable Java 21 (Eclipse Temurin, ~45 MB)")
    archive = ROOT / "runtime" / "java.zip"
    if not download(JAVA_URL, archive, "java", theme):
        return False
    import zipfile
    target = ROOT / "runtime" / "java"
    shutil.rmtree(target, ignore_errors=True)
    with zipfile.ZipFile(archive) as zipped:
        zipped.extractall(target)
    archive.unlink(missing_ok=True)
    found = find_java(settings)
    if not found:
        print(f"  {RED}[x]{RESET} Java was downloaded but does not start")
        return False
    settings["java"] = found
    print(f"  {GREEN}[✓]{RESET} Java installed: {GRAY}{found}{RESET}")
    return True


def install_viaproxy(settings: dict, theme: Theme, force: bool = False) -> bool:
    folder = viaproxy_dir(settings)
    folder.mkdir(parents=True, exist_ok=True)
    current = sorted(folder.glob("ViaProxy-*.jar"))
    release = http_json(VIAPROXY_RELEASES)
    # The normal build only (releases also contain e.g. "ViaProxy-3.4.14+java8.jar").
    import re
    assets = [asset for asset in (release or {}).get("assets", [])
              if re.fullmatch(r"ViaProxy-[0-9][0-9.]*\.jar", asset.get("name", ""))]
    if not assets:
        if current:
            print(f"  {YELLOW}[!]{RESET} could not check for ViaProxy updates; keeping {current[-1].name}")
            return True
        print(f"  {RED}[x]{RESET} could not reach GitHub to download ViaProxy")
        return False
    asset = assets[0]
    if (folder / asset["name"]).exists() and not force:
        print(f"  {GREEN}[✓]{RESET} ViaProxy up to date: {GRAY}{asset['name']}{RESET}")
        return True
    if not download(asset["browser_download_url"], folder / asset["name"], asset["name"], theme):
        return False
    for old in current:
        if old.name != asset["name"]:
            old.unlink(missing_ok=True)
    config = folder / "viaproxy.yml"
    if not config.exists():
        # ViaProxy fills in every other setting itself on first start.
        config.write_text("target-address: play.pika-network.net\n", encoding="utf-8")
    print(f"  {GREEN}[✓]{RESET} ViaProxy installed: {GRAY}{asset['name']}{RESET}")
    return True


def install_from_bundle(bundle: Path, settings: dict, theme: Theme) -> bool:
    """Plugin jar, overlay files and the launcher itself from a setup package's bundle folder."""
    jars = sorted(bundle.glob("PikaStats-[0-9]*.jar"))
    if not jars:
        print(f"  {RED}[x]{RESET} the setup package has no PikaStats jar ({bundle})")
        return False
    plugins = viaproxy_dir(settings) / "plugins"
    plugins.mkdir(parents=True, exist_ok=True)
    for old in plugins.glob("PikaStats-*.jar"):
        if old.name != jars[-1].name:
            old.unlink(missing_ok=True)
    shutil.copy2(jars[-1], plugins / jars[-1].name)
    print(f"  {GREEN}[✓]{RESET} plugin installed: {GRAY}{jars[-1].name}{RESET}")

    overlay = ROOT / "overlay"
    overlay.mkdir(parents=True, exist_ok=True)
    for name in ("main.py", "themes.py", "requirements.txt"):
        source = bundle / "overlay" / name
        if source.exists():
            shutil.copy2(source, overlay / name)
    print(f"  {GREEN}[✓]{RESET} overlay files installed")
    return install_overlay_packages(settings, theme)


def python_works(python: Path | str) -> bool:
    try:
        return subprocess.run([str(python), "-c", "import sys"], capture_output=True, timeout=60).returncode == 0
    except (OSError, subprocess.SubprocessError):
        return False


def overlay_venv(theme: Theme) -> Path | None:
    """The overlay's private environment; rebuilt when broken (e.g. pyvenv.cfg missing)."""
    venv = ROOT / "runtime" / "venv"
    python = venv / ("Scripts/python.exe" if WINDOWS else "bin/python")
    if python.exists() and python_works(python):
        return python
    if venv.exists():
        print(f"  {YELLOW}[~]{RESET} the overlay's Python environment is broken - rebuilding it...")
        shutil.rmtree(venv, ignore_errors=True)
    print(f"  {theme.main}[>]{RESET} creating a private Python environment for the overlay...")
    result = subprocess.run([sys.executable, "-m", "venv", str(venv)], capture_output=True, text=True)
    if result.returncode == 0 and python_works(python):
        return python
    shutil.rmtree(venv, ignore_errors=True)
    return None


def install_overlay_packages(settings: dict, theme: Theme) -> bool:
    requirements = str(ROOT / "overlay" / "requirements.txt")
    pip_flags = ["-m", "pip", "install", "--disable-pip-version-check", "-q"]
    python = overlay_venv(theme)
    print(f"  {theme.main}[>]{RESET} installing overlay packages (PySide6, websocket-client - ~80 MB download, takes a minute)...")
    if python is not None:
        if subprocess.run([str(python), *pip_flags, "-r", requirements]).returncode == 0:
            settings["python"] = str(python)
            print(f"  {GREEN}[✓]{RESET} overlay ready")
            return True
    else:
        print(f"  {YELLOW}[~]{RESET} no private environment possible with this Python - using it directly")
    # Fallback: this Python itself, packages in the user's site-packages.
    if subprocess.run([sys.executable, *pip_flags, "--user", "-r", requirements]).returncode == 0 \
            and subprocess.run([sys.executable, "-c", "import PySide6, websocket"], capture_output=True).returncode == 0:
        settings["python"] = sys.executable
        print(f"  {GREEN}[✓]{RESET} overlay ready")
        return True
    print(f"  {RED}[x]{RESET} pip could not install the overlay packages")
    return False


def write_api_keys(settings: dict, keys: list[str]) -> None:
    """Stores keys in plugins/PikaStats/config.properties (PikaStats adds its other settings itself)."""
    config = viaproxy_dir(settings) / "plugins" / "PikaStats" / "config.properties"
    config.parent.mkdir(parents=True, exist_ok=True)
    lines = config.read_text(encoding="utf-8").splitlines() if config.exists() else []
    lines = [line for line in lines if not line.startswith(("api-key=", "api-keys="))]
    lines += ["api-key=", "api-keys=" + ",".join(keys)]
    config.write_text("\n".join(lines) + "\n", encoding="utf-8")


API_KEY_PATTERN = re.compile(r"(?<![A-Za-z0-9_])[A-Za-z0-9]{2,10}_[A-Za-z0-9]{20,64}(?![A-Za-z0-9_])")


def clipboard_text() -> str:
    """Text on the Windows clipboard ("" when there is none or it is busy)."""
    if not WINDOWS:
        return ""
    import ctypes
    user32, kernel32 = ctypes.windll.user32, ctypes.windll.kernel32
    user32.GetClipboardData.restype = ctypes.c_void_p
    kernel32.GlobalLock.restype = ctypes.c_void_p
    kernel32.GlobalLock.argtypes = [ctypes.c_void_p]
    kernel32.GlobalUnlock.argtypes = [ctypes.c_void_p]
    if not user32.OpenClipboard(None):
        return ""
    try:
        handle = user32.GetClipboardData(13)  # CF_UNICODETEXT
        pointer = kernel32.GlobalLock(handle) if handle else None
        if not pointer:
            return ""
        try:
            return ctypes.wstring_at(pointer)[:4096]
        finally:
            kernel32.GlobalUnlock(handle)
    finally:
        user32.CloseClipboard()


def flash_window() -> None:
    """Blinks this console in the taskbar, so the player sees the setup moved on."""
    if WINDOWS:
        import ctypes
        window = ctypes.windll.kernel32.GetConsoleWindow()
        if window:
            ctypes.windll.user32.FlashWindow(window, True)


def getkey_or_clipboard(prompt: str, skip: set[str]) -> tuple[str, str | None]:
    """Like getkey, but also returns as soon as an API key (not in skip) is copied anywhere.
    Returns (key pressed, None) or ("", api key)."""
    if not WINDOWS:
        return getkey(prompt), None
    import msvcrt
    print(prompt, end="", flush=True)
    while True:
        if msvcrt.kbhit():
            key = msvcrt.getwch()
            if key in ("\x00", "\xe0"):
                msvcrt.getwch()
                continue
            print()
            return key.lower(), None
        match = API_KEY_PATTERN.search(clipboard_text())
        if match and match.group(0) not in skip:
            print()
            return "", match.group(0)
        time.sleep(0.25)


def test_api_key(key: str) -> str:
    status, _ = api_get("https://stats.pika-network.net/api/profile/Notch", key)
    if status in (200, 400, 404):
        return "ok"
    if status in (401, 403):
        return "invalid"
    if status == 429:
        return "ok (rate limited right now)"
    return f"could not check (HTTP {status or 'no connection'})"


def add_api_key(settings: dict, keys: list[str], value: str) -> bool:
    """Tests and saves a key. False when it does not look like a key or PikaNetwork rejects it."""
    value = value.strip().strip('"')
    if len(value) < 8 or " " in value:
        print(f"  {RED}[x]{RESET} that does not look like an API key")
        return False
    print(f"  {GRAY}checking the key with PikaNetwork...{RESET}")
    result = test_api_key(value)
    if result == "invalid":
        print(f"  {RED}[x]{RESET} PikaNetwork rejected that key - check it and try again")
        return False
    if value not in keys:
        keys.append(value)
    write_api_keys(settings, keys)
    print(f"  {GREEN}[✓]{RESET} key saved ({result})")
    return True


def api_keys_menu(settings: dict, theme: Theme, first_run: bool = False) -> None:
    keys = api_keys(settings)
    # Keys already handled are not picked up from the clipboard again.
    seen: set[str] = set(keys)
    while True:
        rule(theme, "API KEYS")
        print(f"  {GRAY}PikaStats needs a free PikaNetwork API key to look up stats.{RESET}")
        print(f"  {GRAY}Get one: join play.pika-network.net and type {RESET}{BOLD}/apikey{RESET}{GRAY} in chat.{RESET}")
        if WINDOWS:
            print(f"  {GREEN}Just copy the key{RESET}{GRAY} (Ctrl+C) - this window picks it up by itself, you do not need to"
                  f" come back here.{RESET}")
        print(f"  {GRAY}Each key allows ~120 lookups per minute; add keys from more accounts for more.{RESET}\n")
        for index, key in enumerate(keys, 1):
            masked = key[:4] + "••••••" + key[-4:] if len(key) > 8 else "****"
            print(f"  {theme.main}{index}{RESET} {masked}")
        if not keys:
            print(f"  {YELLOW}no keys yet{RESET}")
        print(f"\n  {theme.main}a{RESET} add key   {theme.main}r{RESET} remove key   {theme.main}t{RESET} test keys   "
              f"{theme.main}{'n' if first_run else 'b'}{RESET} {'continue' if first_run else 'back'}")
        choice, copied = getkey_or_clipboard(f"\n  {theme.main}keys{RESET}> ", seen)
        if copied:
            seen.add(copied)
            flash_window()
            print(f"  {GREEN}[✓]{RESET} found a key on the clipboard: {copied[:4]}••••{copied[-4:]}")
            if add_api_key(settings, keys, copied) and first_run:
                return
        elif choice == "a":
            value = ask(f"  paste your key with Ctrl+V, then press Enter > ")
            seen.add(value.strip().strip('"'))
            if add_api_key(settings, keys, value) and first_run:
                return
        elif choice == "r" and keys:
            index = ask("  number to remove > ")
            if index.isdigit() and 1 <= int(index) <= len(keys):
                keys.pop(int(index) - 1)
                write_api_keys(settings, keys)
                print(f"  {GREEN}[✓]{RESET} removed")
        elif choice == "t":
            for key in keys:
                print(f"  {key[:4]}•••• {test_api_key(key)}")
        elif choice in ("n", "b", "q", "\r", "\x1b"):
            if first_run and not keys:
                print(f"  {YELLOW}[!]{RESET} no key: stats lookups will not work until you add one (menu key a)")
            return


def write_start_files(settings: dict) -> None:
    """Start PikaStats.bat in the install folder plus desktop and Start menu shortcuts."""
    starter = ROOT / "Start PikaStats.bat"
    starter.write_text(
        "@echo off\r\ntitle PIKA//CTRL\r\nchcp 65001 >nul\r\nmode con: cols=104 lines=42 >nul 2>&1\r\n"
        f'cd /d "%~dp0"\r\n"{sys.executable}" "%~dp0launcher.py" %*\r\nif errorlevel 1 pause\r\n', encoding="utf-8")
    if not WINDOWS:
        return
    targets = [Path(os.environ.get("USERPROFILE", Path.home())) / "Desktop" / "PikaStats.lnk",
               Path(os.environ.get("APPDATA", "")) / "Microsoft/Windows/Start Menu/Programs/PikaStats.lnk"]
    for link in targets:
        if link.parent.exists():
            try:
                write_shortcut(link, starter, ROOT, "PikaStats launcher")
            except OSError as error:
                print(f"  {YELLOW}[!]{RESET} could not create {link.name} in {link.parent}: {error}")


def write_shortcut(link: Path, target: Path, working_dir: Path, description: str) -> None:
    """Writes a Windows .lnk file directly (MS-SHLLINK format), without PowerShell or COM."""
    import struct

    def string_data(text: str) -> bytes:
        return struct.pack("<H", len(text)) + text.encode("utf-16-le")

    # LinkInfo with VolumeID + local path; the Unicode path fields (header size 0x24) keep any user name intact.
    ansi_path = str(target).encode("mbcs", errors="replace") + b"\0" if WINDOWS else str(target).encode() + b"\0"
    unicode_path = str(target).encode("utf-16-le") + b"\0\0"
    volume_id = struct.pack("<IIII", 17, 3, 0, 0x10) + b"\0"  # size, DRIVE_FIXED, serial, label offset, ""
    header_size = 0x24
    volume_offset = header_size
    path_offset = volume_offset + len(volume_id)
    suffix_offset = path_offset + len(ansi_path)
    unicode_offset = suffix_offset + 1
    unicode_suffix_offset = unicode_offset + len(unicode_path)
    link_info_size = unicode_suffix_offset + 2
    link_info = struct.pack("<IIIIIIIII", link_info_size, header_size, 0x1, volume_offset, path_offset, 0,
                            suffix_offset, unicode_offset, unicode_suffix_offset)
    link_info += volume_id + ansi_path + b"\0" + unicode_path + b"\0\0"

    flags = 0x2 | 0x4 | 0x10 | 0x80 | 0x200  # HasLinkInfo, HasName, HasWorkingDir, IsUnicode, HasExpString
    header = struct.pack("<I16sII8s8s8sIIIHHII", 0x4C,
                         bytes.fromhex("0114020000000000c000000000000046"),
                         flags, 0x20, b"\0" * 8, b"\0" * 8, b"\0" * 8, 0, 0, 1, 0, 0, 0, 0)
    # EnvironmentVariableDataBlock: the target path Explorer resolves the shortcut with.
    target_ansi = (str(target).encode("mbcs", errors="replace") if WINDOWS else str(target).encode())[:259]
    target_wide = str(target).encode("utf-16-le")[:518]
    env_block = struct.pack("<II", 0x314, 0xA0000001) + target_ansi.ljust(260, b"\0") + target_wide.ljust(520, b"\0")
    data = (header + link_info + string_data(description) + string_data(str(working_dir)) + env_block
            + b"\0\0\0\0")
    link.write_bytes(data)


HOW_TO_PLAY = [
    "Start ViaProxy + overlay from the launcher (key o).",
    "In the ViaProxy window: open Accounts and add your Minecraft account, then on General check the "
    "server is play.pika-network.net and press Start.",
    "In Minecraft, join the server localhost:25568.",
    "Type -help in chat.",
]


# ----------------------------------------------------------------------------- setup windows
# After the install steps the setup asks nothing more in its console window: on some PCs that console
# stopped taking keys once Java/ViaProxy/pip had run in it. These small windows avoid it.

GUI_BG, GUI_CARD, GUI_TEXT, GUI_DIM, GUI_ACCENT, GUI_BAD = "#0d1117", "#161b22", "#e6edf3", "#8b949e", "#00d27a", "#f85149"


def gui_available() -> bool:
    try:
        import tkinter
        root = tkinter.Tk()
        root.destroy()
        return True
    except Exception:  # noqa: BLE001 - no tkinter or no display: use the console instead
        return False


def gui_root(title: str):
    import tkinter as tk
    root = tk.Tk()
    root.title(title)
    root.configure(bg=GUI_BG, padx=22, pady=18)
    root.resizable(False, False)
    root.attributes("-topmost", True)  # stays visible next to a windowed Minecraft
    return root


def gui_button(parent, text: str, command, primary: bool = False):
    import tkinter as tk
    return tk.Button(parent, text=text, command=command, relief="flat", cursor="hand2", padx=14, pady=6,
                     font=("Segoe UI", 10, "bold"), bd=0,
                     bg=GUI_ACCENT if primary else GUI_CARD, fg=GUI_BG if primary else GUI_TEXT,
                     activebackground=GUI_ACCENT, activeforeground=GUI_BG)


def gui_show(root) -> None:
    root.update_idletasks()
    x = (root.winfo_screenwidth() - root.winfo_width()) // 2
    y = (root.winfo_screenheight() - root.winfo_height()) // 3
    root.geometry(f"+{x}+{y}")
    root.lift()
    root.focus_force()
    flash_window()


def setup_key_window(settings: dict) -> None:
    """Asks for the PikaNetwork API key in a small window; also picks a copied key up by itself."""
    import threading
    import tkinter as tk
    keys = api_keys(settings)
    root = gui_root("PikaStats setup - API key")
    state = {"busy": False, "seen": set(keys), "timer": None}

    def close() -> None:
        if state["timer"] is not None:
            root.after_cancel(state["timer"])
        root.destroy()

    tk.Label(root, text="PikaNetwork API key", bg=GUI_BG, fg=GUI_TEXT, font=("Segoe UI", 15, "bold")).pack(anchor="w")
    tk.Label(root, text="1. Join play.pika-network.net and type /apikey in chat.\n"
                        "2. Copy the key (Ctrl+C) - this window picks it up by itself.\n"
                        "    Or paste it below and press Save.",
             bg=GUI_BG, fg=GUI_DIM, justify="left", font=("Segoe UI", 10)).pack(anchor="w", pady=(6, 10))
    entry = tk.Entry(root, width=48, font=("Consolas", 11), bg=GUI_CARD, fg=GUI_TEXT, insertbackground=GUI_TEXT,
                     relief="flat", highlightthickness=1, highlightbackground="#30363d", highlightcolor=GUI_ACCENT)
    entry.pack(fill="x", ipady=6)
    status = tk.Label(root, text="Waiting for your key..." if not keys else f"{len(keys)} key(s) already saved.",
                      bg=GUI_BG, fg=GUI_DIM, font=("Segoe UI", 10), anchor="w", justify="left", wraplength=420)
    status.pack(fill="x", pady=(8, 12))
    row = tk.Frame(root, bg=GUI_BG)
    row.pack(fill="x")

    def set_status(text: str, color: str = GUI_DIM) -> None:
        status.configure(text=text, fg=color)

    def clipboard() -> str:
        try:
            return root.clipboard_get()
        except tk.TclError:
            return ""

    def try_key(value: str) -> None:
        value = value.strip().strip('"')
        if state["busy"] or not value:
            return
        if len(value) < 8 or " " in value:
            set_status("That does not look like an API key.", GUI_BAD)
            return
        state["busy"] = True
        state["seen"].add(value)
        set_status("Checking the key with PikaNetwork...")

        def work() -> None:
            result = test_api_key(value)
            root.after(0, lambda: finished(value, result))

        threading.Thread(target=work, daemon=True).start()

    def finished(value: str, result: str) -> None:
        state["busy"] = False
        if result == "invalid":
            set_status("PikaNetwork rejected that key. Check it and try again.", GUI_BAD)
            return
        if value not in keys:
            keys.append(value)
        write_api_keys(settings, keys)
        set_status(f"Key saved ({result}). Continuing...", GUI_ACCENT)
        root.after(1300, close)

    def paste() -> None:
        text = clipboard()
        match = API_KEY_PATTERN.search(text)
        entry.delete(0, "end")
        entry.insert(0, match.group(0) if match else text.strip()[:80])
        try_key(entry.get())

    def poll() -> None:
        if not state["busy"]:
            match = API_KEY_PATTERN.search(clipboard())
            if match and match.group(0) not in state["seen"]:
                entry.delete(0, "end")
                entry.insert(0, match.group(0))
                flash_window()
                try_key(match.group(0))
        state["timer"] = root.after(500, poll)

    gui_button(row, "Save key", lambda: try_key(entry.get()), primary=True).pack(side="left")
    gui_button(row, "Paste", paste).pack(side="left", padx=8)
    gui_button(row, "Skip for now" if not keys else "Continue", close).pack(side="right")
    root.protocol("WM_DELETE_WINDOW", close)
    entry.bind("<Return>", lambda event: try_key(entry.get()))
    gui_show(root)
    entry.focus_set()
    state["timer"] = root.after(300, poll)
    root.mainloop()


def setup_done_window() -> bool:
    """'PikaStats is installed' with the how-to; True when the player wants the launcher now."""
    import tkinter as tk
    root = gui_root("PikaStats is installed")
    choice = {"open": False}
    tk.Label(root, text="PikaStats is installed!", bg=GUI_BG, fg=GUI_ACCENT,
             font=("Segoe UI", 15, "bold")).pack(anchor="w")
    tk.Label(root, text="A PikaStats shortcut is on your desktop and in the Start menu.", bg=GUI_BG, fg=GUI_DIM,
             font=("Segoe UI", 10)).pack(anchor="w", pady=(4, 10))
    for number, step in enumerate(HOW_TO_PLAY, 1):
        tk.Label(root, text=f"{number}.  {step}", bg=GUI_BG, fg=GUI_TEXT, font=("Segoe UI", 10), justify="left",
                 wraplength=440).pack(anchor="w", pady=2)
    row = tk.Frame(root, bg=GUI_BG)
    row.pack(fill="x", pady=(14, 0))

    def open_launcher() -> None:
        choice["open"] = True
        root.destroy()

    gui_button(row, "Open launcher", open_launcher, primary=True).pack(side="left")
    gui_button(row, "Close", root.destroy).pack(side="right")
    gui_show(root)
    root.mainloop()
    return choice["open"]


def open_launcher_window() -> None:
    """Starts the launcher in its own new console window (never in the setup's console)."""
    starter = ROOT / "Start PikaStats.bat"
    if WINDOWS and starter.exists():
        subprocess.Popen(["cmd", "/c", str(starter), "--fast"], cwd=ROOT, creationflags=subprocess.CREATE_NEW_CONSOLE)
    else:
        subprocess.Popen([sys.executable, str(ROOT / "launcher.py"), "--fast"], cwd=ROOT)


def run_setup(source: Path, theme: Theme) -> int:
    """First-time setup wizard, started by 'Install PikaStats.bat' from the setup package folder."""
    global ROOT, SETTINGS_FILE, STATE_FILE
    clear()
    banner(theme)
    target = INSTALL_DIR
    lock = Path(os.environ.get("TEMP", str(target))) / "pikastats-setup.pid"
    try:
        other = int(lock.read_text(encoding="utf-8").strip())
    except (OSError, ValueError):
        other = 0
    running = other and other != os.getpid() and pid_alive(other) and (not WINDOWS or b"python" in subprocess.run(
        ["tasklist", "/FI", f"PID eq {other}", "/NH"], capture_output=True).stdout.lower())
    if running:
        print(f"\n  {YELLOW}[!]{RESET} The PikaStats setup is already running in another window (process {other}).\n"
              f"      Finish or close that one first (its X button, or end python.exe in Task Manager).")
        return 1
    lock.write_text(str(os.getpid()), encoding="utf-8")
    if WINDOWS and "WindowsApps" in sys.executable:
        # The Store build redirects AppData\Local into a private folder, so nothing would end up
        # where the launcher, shortcuts and overlay look for it.
        print(f"\n  {RED}[x]{RESET} This is the Microsoft Store Python ({sys.executable}).\n"
              f"      It cannot install PikaStats. Start 'Install PikaStats.bat' instead - it uses or\n"
              f"      installs a normal Python for you.")
        return 1
    print()
    rule(theme, "PIKASTATS SETUP")
    print(f"""  This installs PikaStats for you. It will:
    {theme.main}1{RESET} use Java 17+ if you have it, or download a portable Java (no admin needed)
    {theme.main}2{RESET} download ViaProxy (the proxy you connect Minecraft through)
    {theme.main}3{RESET} install the PikaStats plugin and the desktop stats overlay
    {theme.main}4{RESET} ask for your PikaNetwork API key
    {theme.main}5{RESET} add a {BOLD}PikaStats{RESET} shortcut to your desktop

  Install folder: {BOLD}{target}{RESET}
  Nothing is installed anywhere else, and no admin rights are needed.""")
    if (target / ".pikastats-installed").exists():
        print(f"\n  {YELLOW}PikaStats is already installed here.{RESET} Continuing repairs and updates it;"
              f" your settings and keys are kept.")
    answer = getkey(f"\n  {theme.main}install now? [y/n]{RESET} ")
    if answer not in ("y", "j", "\r"):
        print(f"  {GRAY}cancelled - nothing was changed.{RESET}")
        return 0

    target.mkdir(parents=True, exist_ok=True)
    for name in ("launcher.py",):
        if source.resolve() != target.resolve():
            shutil.copy2(source / name, target / name)
    if (source / "bundle").exists() and source.resolve() != target.resolve():
        shutil.rmtree(target / "bundle", ignore_errors=True)
        shutil.copytree(source / "bundle", target / "bundle")
    ROOT = target
    SETTINGS_FILE = ROOT / "launcher.json"
    STATE_FILE = ROOT / ".launcher-pids.json"
    settings = load_settings()
    settings["viaproxy_dir"] = str(ROOT / "viaproxy")

    steps = [
        ("JAVA", lambda: install_java(settings, theme)),
        ("VIAPROXY", lambda: install_viaproxy(settings, theme)),
        ("PLUGIN + OVERLAY", lambda: install_from_bundle(ROOT / "bundle", settings, theme)),
    ]
    for index, (name, step) in enumerate(steps, 1):
        rule(theme, f"STEP {index}/{len(steps) + 2} :: {name}")
        if not step():
            save_settings(settings)
            print(f"\n  {RED}Setup could not finish.{RESET} Check your internet connection and run "
                  f"'Install PikaStats.bat' again - finished steps are skipped.")
            return 1
        save_settings(settings)
    rule(theme, f"STEP {len(steps) + 1}/{len(steps) + 2} :: API KEY")
    use_windows = gui_available()
    if use_windows:
        print(f"  {theme.main}[>]{RESET} a small window asks for your API key (it may be behind other windows)")
        setup_key_window(settings)
        count = len(api_keys(settings))
        print(f"  {GREEN + f'[✓] {count} API key(s) saved' if count else YELLOW + '[!] no API key yet - add one later (launcher key a)'}{RESET}")
    else:
        api_keys_menu(settings, theme, first_run=True)
    rule(theme, f"STEP {len(steps) + 2}/{len(steps) + 2} :: SHORTCUTS")
    write_start_files(settings)
    (ROOT / ".pikastats-installed").write_text(VERSION + "\n", encoding="utf-8")
    save_settings(settings)
    print(f"  {GREEN}[✓]{RESET} desktop + Start menu shortcut: {BOLD}PikaStats{RESET}")
    if use_windows:
        print(f"\n  {GREEN}{BOLD}PikaStats is installed!{RESET} {GRAY}(this window closes by itself){RESET}")
        if setup_done_window():
            open_launcher_window()
        return 0
    print(f"""
  {GREEN}{BOLD}PikaStats is installed!{RESET}
  How to play:
    {theme.main}1{RESET} start ViaProxy + overlay from the launcher (key {BOLD}o{RESET})
    {theme.main}2{RESET} in the ViaProxy window: open {BOLD}Accounts{RESET} and add your Minecraft account,
       then on {BOLD}General{RESET} check the server is {BOLD}play.pika-network.net{RESET} and press {BOLD}Start{RESET}
    {theme.main}3{RESET} in Minecraft, join the server {BOLD}localhost:25568{RESET}
    {theme.main}4{RESET} type {BOLD}-help{RESET} in chat""")
    getkey(f"\n  {GRAY}press any key to open the launcher...{RESET}")
    return subprocess.call([sys.executable, str(ROOT / "launcher.py"), "--fast"], cwd=ROOT)


def update_install(settings: dict, theme: Theme) -> None:
    """Newest ViaProxy, then plugin/overlay/launcher from the newest setup package on GitHub."""
    if pid_alive(load_pids().get("viaproxy", 0)):
        print(f"  {YELLOW}[!]{RESET} stop ViaProxy first (k)")
        return
    rule(theme, "UPDATE")
    install_viaproxy(settings, theme)
    repo = settings.get("github_repo", SETUP_REPO_DEFAULT)
    release = http_json(f"https://api.github.com/repos/{repo}/releases/latest")
    asset = next((item for item in (release or {}).get("assets", [])
                  if item.get("name", "").startswith("PikaStats-Setup") and item["name"].endswith(".zip")), None)
    if asset is None:
        print(f"  {GRAY}[-] no PikaStats release found on github.com/{repo} - plugin and overlay unchanged{RESET}")
        save_settings(settings)
        return
    installed = (ROOT / "bundle" / "version.txt").read_text(encoding="utf-8").strip() \
        if (ROOT / "bundle" / "version.txt").exists() else ""
    if installed and installed in asset["name"]:
        print(f"  {GREEN}[✓]{RESET} PikaStats up to date ({installed})")
        return
    archive = ROOT / "runtime" / asset["name"]
    if not download(asset["browser_download_url"], archive, asset["name"], theme):
        return
    import zipfile
    unpacked = ROOT / "runtime" / "update"
    shutil.rmtree(unpacked, ignore_errors=True)
    with zipfile.ZipFile(archive) as zipped:
        zipped.extractall(unpacked)
    archive.unlink(missing_ok=True)
    package = next((path.parent for path in unpacked.rglob("launcher.py")), None)
    if package is None or not (package / "bundle").exists():
        print(f"  {RED}[x]{RESET} the downloaded package is incomplete")
        return
    shutil.rmtree(ROOT / "bundle", ignore_errors=True)
    shutil.copytree(package / "bundle", ROOT / "bundle")
    shutil.copy2(package / "launcher.py", ROOT / "launcher.py")
    shutil.rmtree(unpacked, ignore_errors=True)
    install_from_bundle(ROOT / "bundle", settings, theme)
    save_settings(settings)
    print(f"  {GREEN}[✓]{RESET} updated - restart the launcher to use its new version")


def repair_install(settings: dict, theme: Theme) -> None:
    if pid_alive(load_pids().get("viaproxy", 0)):
        print(f"  {YELLOW}[!]{RESET} stop ViaProxy first (k)")
        return
    rule(theme, "REPAIR")
    ok = install_java(settings, theme) and install_viaproxy(settings, theme) \
        and install_from_bundle(ROOT / "bundle", settings, theme)
    write_start_files(settings)
    save_settings(settings)
    print(f"  {GREEN + '[✓] everything repaired' if ok else RED + '[x] repair could not finish'}{RESET}")


# ----------------------------------------------------------------------------- main menu

PRESETS = {
    "v": ("viaproxy only", ["viaproxy"]),
    "o": ("viaproxy + overlay", ["viaproxy", "overlay"]),
}

TOOLS_PLAYER = [("l", "lookup player"), ("s", "status scan"), ("k", "stop all"), ("r", "restart selected"),
                ("a", "api keys"), ("t", "overlay theme"), ("d", "overlay demo"), ("u", "update"),
                ("x", "repair"), ("c", "open folder"), ("g", "settings"), ("q", "quit")]


def draw_menu(settings: dict, theme: Theme, message: str) -> None:
    clear()
    banner(theme)
    pids = load_pids()
    print()
    rule(theme, f"MODULES  (toggle with 1-{len(COMPONENTS)}, ENTER = start)")
    for index, (key, info) in enumerate(COMPONENTS.items(), 1):
        on = key in settings["selected"]
        box = f"{theme.main}[■]{RESET}" if on else f"{GRAY}[ ]{RESET}"
        running = f"{GREEN}● running{RESET}" if pid_alive(pids.get(key, 0)) else f"{GRAY}○ idle{RESET}"
        print(f"  {theme.main}{index}{RESET} {box} {BOLD}{info['name']:<9}{RESET} {GRAY}{info['desc']:<44}{RESET} {running}")
    rule(theme, "QUICK DEPLOY")
    print("  " + "   ".join(f"{theme.main}{k}{RESET} {GRAY}{label}{RESET}" for k, (label, _) in PRESETS.items()))
    rule(theme, "TOOLS")
    tools = TOOLS_PLAYER
    if not api_keys(settings):
        print(f"  {YELLOW}[!] no API key yet - press a to add one, or stats lookups will not work{RESET}")
    for row in range(0, len(tools), 4):
        print("  " + "".join(f"{theme.main}{k}{RESET} {GRAY}{label:<22}{RESET}" for k, label in tools[row:row + 4]))
    rule(theme)
    if message:
        print(message)


def main() -> int:
    if "--setup" in sys.argv:
        theme = Theme(DEFAULTS["accent"])
        if (ROOT / ".pikastats-installed").exists():
            # Double-clicked the installer inside an existing install: just start.
            pass
        else:
            return run_setup(ROOT, theme)
    settings = load_settings()
    theme = Theme(settings["accent"])
    if settings.get("boot_animation") and "--fast" not in sys.argv:
        boot(theme)
    message = ""
    while True:
        draw_menu(settings, theme, message)
        message = ""
        key = getkey(f"\n  {theme.main}root@pika{RESET}:{GRAY}~{RESET}$ ")
        if key.isdigit() and 1 <= int(key) <= len(COMPONENTS):
            component = list(COMPONENTS)[int(key) - 1]
            if component in settings["selected"]:
                settings["selected"].remove(component)
            else:
                settings["selected"].append(component)
            save_settings(settings)
            continue
        if key in PRESETS:
            settings["selected"] = list(PRESETS[key][1])
            save_settings(settings)
            print()
            start_selected(settings, theme)
        elif key in ("\r", "\n", "e"):
            print()
            start_selected(settings, theme)
        elif key == "l":
            lookup(settings, theme)
        elif key == "s":
            status_report(settings, theme)
        elif key == "k":
            kill_all(theme)
        elif key == "r":
            for component in settings["selected"]:
                stop(component)
            time.sleep(1.5)
            start_selected(settings, theme)
        elif key == "a":
            api_keys_menu(settings, theme)
            continue
        elif key == "u":
            update_install(settings, theme)
        elif key == "x":
            repair_install(settings, theme)
        elif key == "d":
            python = settings.get("python") or ("py" if WINDOWS else "python3")
            args = [python] + (["-3"] if python == "py" else []) + [str(overlay_dir() / "main.py"), "--demo"]
            subprocess.Popen(args, cwd=overlay_dir())
            message = f"  {GREEN}[✓]{RESET} overlay demo opened"
            continue
        elif key == "t":
            overlay_theme(theme)
        elif key == "c":
            open_path(ROOT)
            continue
        elif key == "g":
            theme = settings_menu(settings, theme)
            continue
        elif key in ("q", "\x1b"):
            print(f"  {GRAY}session closed. running modules keep running (use k to stop them).{RESET}")
            return 0
        else:
            continue
        getkey(f"\n  {GRAY}press any key...{RESET}")


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except KeyboardInterrupt:
        print(RESET)
