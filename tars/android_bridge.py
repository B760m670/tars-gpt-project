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
_data_dir = "."
_term = None


def init(data_dir: str) -> bool:
    """Create the TARS instance, storing memory under the app's data dir."""
    global _tars, _data_dir
    _data_dir = data_dir
    os.environ["TARS_MEMORY"] = str(Path(data_dir) / "memory.db")
    from .config import load_settings
    from .core import Tars
    _tars = Tars(load_settings())
    return True


def terminal(line: str) -> str:
    """Run one terminal command (shell, or 'py <code>' for Python) in a session
    rooted at the app's data dir. Backs the manual terminal now and the agentic
    mode later."""
    global _term
    if _term is None:
        from .terminal import Terminal
        _term = Terminal(base_dir=_data_dir)
    return _term.run(line)


def extract_voice(archive: str, dest: str) -> str:
    """Unpack a sherpa-onnx Piper voice .tar.bz2 (Python has bz2 + tar built in,
    Android/Java doesn't) and return 'model|tokens|dataDir' for the Kotlin TTS
    engine. dataDir is the espeak-ng-data folder Piper needs for phonemes."""
    import glob
    import tarfile

    os.makedirs(dest, exist_ok=True)
    with tarfile.open(archive, "r:bz2") as tar:
        tar.extractall(dest)

    def first(pattern):
        hits = glob.glob(os.path.join(dest, "**", pattern), recursive=True)
        return hits[0] if hits else ""

    onnx = first("*.onnx")
    tokens = first("tokens.txt")
    data_dirs = glob.glob(os.path.join(dest, "**", "espeak-ng-data"), recursive=True)
    data_dir = data_dirs[0] if data_dirs else ""
    return "|".join([onnx, tokens, data_dir])


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


def recommended_model() -> str:
    """The model the device should run, as 'id|filename|url|size_mb', or '' if
    nothing fits (then TARS leans on cloud + the offline brain). The Android
    side downloads this and points the embedded llama.cpp server at it."""
    from . import models
    spec = models.recommend()
    if spec is None:
        return ""
    return "|".join([spec.id, spec.filename, spec.url, str(spec.file_mb)])


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
