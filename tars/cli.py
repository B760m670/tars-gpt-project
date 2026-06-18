"""Milestone 1 entry point: talk to TARS in your console.

Run:  python -m tars
"""
from __future__ import annotations

from . import models
from .brains import BrainError
from .config import Settings, load_settings
from .core import Tars

BANNER = r"""
  ______ ___    ____  _____
 /_  __//   |  / __ \/ ___/     TARS  —  console (milestone 1)
  / /  / /| | / /_/ /\__ \      a mind that lives in your device
 / /  / ___ |/ _, _/___/ /      type /help for commands, /quit to leave
/_/  /_/  |_/_/ |_|/____/
"""

HELP = """\
commands:
  /humor N      set humor 0-100 (live, like the film)
  /honesty N    set honesty 0-100
  /discretion N set discretion 0-100
  /sarcasm N    set sarcasm 0-100
  /settings     show current dials and active brain
  /remember k=v store a fact TARS should keep (e.g. /remember name=Cooper)
  /facts        list remembered facts
  /models       on-device brain models (light->heavy) + what fits this device
  /help         this help
  /quit         power down\
"""


def _clamp(text: str) -> int:
    try:
        value = int(text)
    except ValueError:
        return 0
    return max(0, min(100, value))


def print_models() -> None:
    ram = models.total_ram_mb()
    pick = models.recommend(ram)
    ram_str = "{} MB".format(ram) if ram else "unknown"
    print("on-device brain models (free, offline, llama.cpp) — device RAM: {}".format(ram_str))
    for spec in models.CATALOG:
        if ram and models.fits(spec, ram):
            mark = "<- recommended" if pick and spec.id == pick.id else "fits"
        elif ram:
            mark = "too heavy"
        else:
            mark = ""
        print("  {:<9} {:<5} {:>5} MB file  needs ~{} MB RAM   {}".format(
            spec.label, spec.params, spec.file_mb, spec.min_ram_mb, mark))
    if not pick:
        print("  -> nothing fits comfortably; TARS uses cloud + the offline brain here.")
    print("  set TARS_LOCAL_URL to a running llama.cpp server to use the 'local' brain.")


def handle_command(cmd: str, tars: Tars, settings: Settings) -> bool:
    """Returns False if TARS should shut down, True otherwise."""
    parts = cmd.split()
    name = parts[0].lower()
    arg = parts[1] if len(parts) > 1 else ""
    p = settings.personality

    if name in ("/quit", "/exit"):
        print("TARS> Powering down. 90% honesty: I'll miss you. Maybe.")
        return False
    if name == "/help":
        print(HELP)
    elif name == "/humor" and arg:
        p.humor = _clamp(arg); print("[humor = {}%]".format(p.humor))
    elif name == "/honesty" and arg:
        p.honesty = _clamp(arg); print("[honesty = {}%]".format(p.honesty))
    elif name == "/discretion" and arg:
        p.discretion = _clamp(arg); print("[discretion = {}%]".format(p.discretion))
    elif name == "/sarcasm" and arg:
        p.sarcasm = _clamp(arg); print("[sarcasm = {}%]".format(p.sarcasm))
    elif name == "/settings":
        print("[humor={}% honesty={}% discretion={}% sarcasm={}% | brain={}]".format(
            p.humor, p.honesty, p.discretion, p.sarcasm, tars.brain.last_used or "auto"))
    elif name == "/remember":
        kv = cmd.split(" ", 1)[1] if " " in cmd else ""
        key, _, value = kv.partition("=")
        if key.strip() and value.strip():
            tars.memory.remember(key.strip(), value.strip())
            print("[remembered {}]".format(key.strip()))
        else:
            print("usage: /remember key=value")
    elif name == "/facts":
        print(tars.memory.facts_text() or "[no facts yet]")
    elif name == "/models":
        print_models()
    else:
        print("unknown command; /help")
    return True


def main() -> None:
    settings = load_settings()
    tars = Tars(settings)
    print(BANNER)

    smart = any(b.name != "offline" and b.available() for b in tars.brain.brains)
    if not smart:
        print("[!] Running on the OFFLINE brain only — TARS stays in character but")
        print("    can't truly think yet. Add a FREE key (no credit card) for the")
        print("    full mind:")
        print("    Gemini -> https://aistudio.google.com/apikey  (GEMINI_API_KEY)")
        print("    Groq   -> https://console.groq.com/keys        (GROQ_API_KEY)")
        print("    or run Ollama locally. See .env.example.\n")

    while True:
        try:
            user = input("you> ").strip()
        except (EOFError, KeyboardInterrupt):
            print("\nTARS> See you on the other side.")
            return
        if not user:
            continue
        if user.startswith("/"):
            if not handle_command(user, tars, settings):
                return
            continue
        try:
            reply = tars.respond(user)
        except BrainError as e:
            print("[brain error] {}".format(e))
            continue
        print("TARS> {}".format(reply))
