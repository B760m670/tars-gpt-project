"""Configuration: load settings from the environment (and an optional .env).

Kept dependency-free (no python-dotenv) so the core runs anywhere, including
stripped-down Android Python.
"""
from __future__ import annotations

import os
import tempfile
from dataclasses import dataclass
from pathlib import Path
from typing import List, Optional


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
    """The famous TARS dials. Mutable at runtime via CLI commands."""
    humor: int = 90
    honesty: int = 90
    sarcasm: int = 30
    name: str = "TARS"


@dataclass
class Settings:
    personality: Personality
    brain_order: List[str]
    gemini_key: Optional[str]
    gemini_model: str
    groq_key: Optional[str]
    groq_model: str
    local_url: str
    local_model: str
    ollama_url: str
    ollama_model: str
    memory_path: Path
    history_turns: int


def load_settings() -> Settings:
    _load_dotenv(Path(".env"))
    personality = Personality(
        humor=_int("TARS_HUMOR", 90),
        honesty=_int("TARS_HONESTY", 90),
        sarcasm=_int("TARS_SARCASM", 30),
        name=os.environ.get("TARS_NAME", "TARS"),
    )
    brain_order = [
        b.strip()
        for b in os.environ.get("TARS_BRAIN_ORDER", "gemini,groq,local,ollama,offline").split(",")
        if b.strip()
    ]
    memory_path = Path(os.environ.get("TARS_MEMORY") or _default_memory_path())
    return Settings(
        personality=personality,
        brain_order=brain_order,
        gemini_key=os.environ.get("GEMINI_API_KEY") or None,
        gemini_model=os.environ.get("GEMINI_MODEL", "gemini-2.0-flash"),
        groq_key=os.environ.get("GROQ_API_KEY") or None,
        groq_model=os.environ.get("GROQ_MODEL", "llama-3.3-70b-versatile"),
        local_url=os.environ.get("TARS_LOCAL_URL", "http://127.0.0.1:8080"),
        local_model=os.environ.get("TARS_LOCAL_MODEL", "local"),
        ollama_url=os.environ.get("OLLAMA_URL", "http://127.0.0.1:11434"),
        ollama_model=os.environ.get("OLLAMA_MODEL", "qwen2.5:3b"),
        memory_path=memory_path,
        history_turns=_int("TARS_HISTORY_TURNS", 12),
    )
