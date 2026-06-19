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


def extract_archive(archive: str, dest: str) -> str:
    """Unpack a sherpa-onnx model .tar.bz2 (Python has bz2 + tar built in,
    Android/Java doesn't) into dest and return the directory that actually holds
    the files (the single top-level folder inside the archive, if there is one).
    The Kotlin side then locates the specific .onnx/tokens by name."""
    import tarfile

    os.makedirs(dest, exist_ok=True)
    with tarfile.open(archive, "r:bz2") as tar:
        tar.extractall(dest)
    entries = [e for e in os.listdir(dest) if not e.startswith(".")]
    if len(entries) == 1 and os.path.isdir(os.path.join(dest, entries[0])):
        return os.path.join(dest, entries[0])
    return dest


def get_personality() -> str:
    """Current dials as 'humor|honesty|discretion|sarcasm' for the settings UI."""
    if _tars is None:
        return "75|90|70|30"
    p = _tars.settings.personality
    return "|".join(str(v) for v in (p.humor, p.honesty, p.discretion, p.sarcasm))


def set_personality(humor: int, honesty: int, discretion: int, sarcasm: int) -> str:
    """Live-update the TARS dials (the in-film 'settings bench'). No rebuild
    needed — the prompt reads them fresh on every reply."""
    if _tars is None:
        return ""
    p = _tars.settings.personality
    p.humor = max(0, min(100, int(humor)))
    p.honesty = max(0, min(100, int(honesty)))
    p.discretion = max(0, min(100, int(discretion)))
    p.sarcasm = max(0, min(100, int(sarcasm)))
    return "humor {}%, honesty {}%, discretion {}%, sarcasm {}%".format(
        p.humor, p.honesty, p.discretion, p.sarcasm
    )


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


def last_brain_report() -> str:
    """A detailed account of the last reply: which brain answered and why the
    others were skipped/failed. Surfaced in the log for visibility."""
    if _tars is None:
        return "core not started"
    r = _tars.brain
    used = r.last_used or "none"
    tried = getattr(r, "last_errors", None) or []
    report = "used={}".format(used)
    if tried:
        report += " | tried: " + " ; ".join(tried)
    return report


def recommended_model() -> str:
    """The model the device should run, as
    'id|filename|url|size_mb|mmproj_filename|mmproj_url|mmproj_mb', or '' if nothing
    fits (then TARS falls back to the scripted offline brain). The mmproj fields are
    blank for text-only models. The Android side downloads these and points the
    embedded llama.cpp server at them."""
    from . import models
    spec = models.recommend()
    if spec is None:
        return ""
    return "|".join([
        spec.id, spec.filename, spec.url, str(spec.file_mb),
        spec.mmproj_filename, spec.mmproj_url, str(spec.mmproj_mb),
    ])


def system_prompt() -> str:
    """TARS's current character prompt (dials + memory), so the Kotlin vision path
    can ask the multimodal model to comment on what the camera sees *in character*
    without going through the text chat loop."""
    from .personality import build_system_prompt
    if _tars is None:
        from .config import Personality
        return build_system_prompt(Personality())
    return build_system_prompt(_tars.settings.personality, _tars.memory.facts_text())


def diagnostics() -> str:
    """A short status report for the in-app Logs view."""
    from . import models
    ram = models.total_ram_mb()
    pick = models.recommend(ram)
    lines = [
        "device RAM: {}".format("{} MB".format(ram) if ram else "unknown"),
        "recommended local model: {}".format(pick.id if pick else "none (offline brain)"),
    ]
    if _tars is not None:
        order = ",".join(b.name for b in _tars.brain.brains)
        lines.append("brain order: {}".format(order))
        lines.append("last brain used: {}".format(_tars.brain.last_used or "auto"))
        lines.append("memory: {}".format(_tars.settings.memory_path))
    else:
        lines.append("core not initialised yet")
    return "\n".join(lines)
