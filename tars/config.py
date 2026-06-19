"""Configuration: load settings from the environment (and an optional .env).

Kept dependency-free (no python-dotenv) so the core runs anywhere, including
stripped-down Android Python.
"""
from __future__ import annotations

import os
import tempfile
from dataclasses import dataclass
from pathlib import Path
from typing import List


def _default_memory_path() -> str:
    """Where to keep memory.db when TARS_MEMORY isn't set. Path.home() can raise
    on Android (no HOME), so fall back to a temp dir there."""
    try:
        base = Path.home()
    except (RuntimeError, OSError):
        base = Path(tempfile.gettempdir())
    return str(base / ".tars" / "memory.db")


def _load_dotenv(path: Path) -> None:
    """Minimal .env reader: KEY=VALUE lines, '#' comments. Does not override
    variables already set in the real environment."""
    if not path.exists():
        return
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, _, value = line.partition("=")
        os.environ.setdefault(key.strip(), value.strip())


def _int(name: str, default: int) -> int:
    try:
        return int(os.environ[name])
    except (KeyError, ValueError):
        return default


@dataclass
class Personality:
    """The famous TARS dials. Mutable at runtime via CLI commands / the app."""
    humor: int = 75
    honesty: int = 90
    discretion: int = 70
    sarcasm: int = 30
    name: str = "TARS"


@dataclass
class Settings:
    personality: Personality
    brain_order: List[str]
    local_url: str
    local_model: str
    ollama_url: str
    ollama_model: str
    memory_path: Path
    history_turns: int


def load_settings() -> Settings:
    _load_dotenv(Path(".env"))
    personality = Personality(
        humor=_int("TARS_HUMOR", 75),
        honesty=_int("TARS_HONESTY", 90),
        discretion=_int("TARS_DISCRETION", 70),
        sarcasm=_int("TARS_SARCASM", 30),
        name=os.environ.get("TARS_NAME", "TARS"),
    )
    # On-device llama.cpp model first; the scripted offline brain is the safety
    # net. (Ollama stays available for anyone running a desktop server, but it's
    # not in the default chain — a phone runs the embedded engine.)
    brain_order = [
        b.strip()
        for b in os.environ.get("TARS_BRAIN_ORDER", "local,offline").split(",")
        if b.strip()
    ]
    memory_path = Path(os.environ.get("TARS_MEMORY") or _default_memory_path())
    return Settings(
        personality=personality,
        brain_order=brain_order,
        local_url=os.environ.get("TARS_LOCAL_URL", "http://127.0.0.1:8080"),
        local_model=os.environ.get("TARS_LOCAL_MODEL", "local"),
        ollama_url=os.environ.get("OLLAMA_URL", "http://127.0.0.1:11434"),
        ollama_model=os.environ.get("OLLAMA_MODEL", "qwen3:4b"),
        memory_path=memory_path,
        history_turns=_int("TARS_HISTORY_TURNS", 12),
    )
