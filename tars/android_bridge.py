"""Bridge between the Android (Kotlin) UI and the TARS core.

Chaquopy runs this module inside the APK. Kotlin calls init() once with the
app's private data dir (so memory.db lands somewhere writable), then respond()
for each message. Keeping all the Python entry points here means the Android
layer never has to know about the core's internals.
"""
from __future__ import annotations

import os
from pathlib import Path

_tars = None


def init(data_dir: str) -> bool:
    """Create the TARS instance, storing memory under the app's data dir."""
    global _tars
    os.environ["TARS_MEMORY"] = str(Path(data_dir) / "memory.db")
    from .config import load_settings
    from .core import Tars
    _tars = Tars(load_settings())
    return True


def respond(text: str) -> str:
    if _tars is None:
        init(".")
    return _tars.respond(text)


def active_brain() -> str:
    """Which driver answered last (or 'auto' before the first reply) — handy for
    a status line in the UI."""
    if _tars is None:
        return "offline"
    return _tars.brain.last_used or "auto"


def diagnostics() -> str:
    """A short status report for the in-app Logs view."""
    from . import models
    ram = models.total_ram_mb()
    pick = models.recommend(ram)
    lines = [
        "device RAM: {}".format("{} MB".format(ram) if ram else "unknown"),
        "recommended local model: {}".format(pick.id if pick else "none (cloud + offline brain)"),
    ]
    if _tars is not None:
        order = ",".join(b.name for b in _tars.brain.brains)
        lines.append("brain order: {}".format(order))
        lines.append("last brain used: {}".format(_tars.brain.last_used or "auto"))
        lines.append("memory: {}".format(_tars.settings.memory_path))
    else:
        lines.append("core not initialised yet")
    return "\n".join(lines)
