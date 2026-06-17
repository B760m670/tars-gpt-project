"""Memory socket: persistent conversation history + simple facts, in SQLite.

Milestone 1 keeps it deliberately small (recent-turn window + key/value facts).
Vector recall arrives in a later milestone behind this same interface.
"""
from __future__ import annotations

import sqlite3
import threading
import time
from pathlib import Path
from typing import Dict, List, Tuple


class Memory:
    def __init__(self, path: Path):
        path.parent.mkdir(parents=True, exist_ok=True)
        # On Android the connection is opened on the UI thread but used from a
        # worker thread, so allow cross-thread use and serialize access with a
        # lock (SQLite forbids sharing a connection across threads otherwise).
        self.db = sqlite3.connect(str(path), check_same_thread=False)
        self._lock = threading.Lock()
        with self._lock:
            self.db.execute(
                "CREATE TABLE IF NOT EXISTS turns ("
                "id INTEGER PRIMARY KEY AUTOINCREMENT, ts REAL, role TEXT, content TEXT)"
            )
            self.db.execute(
                "CREATE TABLE IF NOT EXISTS facts (key TEXT PRIMARY KEY, value TEXT)"
            )
            self.db.commit()

    def add_turn(self, role: str, content: str) -> None:
        with self._lock:
            self.db.execute(
                "INSERT INTO turns (ts, role, content) VALUES (?, ?, ?)",
                (time.time(), role, content),
            )
            self.db.commit()

    def recent_turns(self, n: int) -> List[Dict[str, str]]:
        with self._lock:
            rows = self.db.execute(
                "SELECT role, content FROM turns ORDER BY id DESC LIMIT ?", (n,)
            ).fetchall()
        return [{"role": r, "content": c} for r, c in reversed(rows)]

    def remember(self, key: str, value: str) -> None:
        with self._lock:
            self.db.execute(
                "INSERT INTO facts (key, value) VALUES (?, ?) "
                "ON CONFLICT(key) DO UPDATE SET value = excluded.value",
                (key, value),
            )
            self.db.commit()

    def facts(self) -> List[Tuple[str, str]]:
        with self._lock:
            return self.db.execute(
                "SELECT key, value FROM facts ORDER BY key"
            ).fetchall()

    def facts_text(self) -> str:
        return "\n".join(f"- {k}: {v}" for k, v in self.facts())
