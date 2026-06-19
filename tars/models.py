"""Model manager: a curated catalog of on-device GGUF models, light to heavy,
plus device-aware recommendation and (optional) download.

This is the "install Ollama-style models" idea done the portable way: instead of
shipping the Ollama server (which doesn't run on a phone), we let the embedded
llama.cpp engine load a GGUF model the user picks here. The app reads the
device's RAM and recommends the heaviest model that comfortably fits — so the
same TARS scales from a weak phone (tiny model, or none → offline brain) to a
flagship (a real 7B mind), all free and on-device.

Dependency-free: RAM detection via /proc/meminfo (works on Linux *and* Android),
download via urllib.
"""
from __future__ import annotations

import urllib.request
from dataclasses import dataclass
from pathlib import Path
from typing import List, Optional

# Headroom factor: a model is only "fits" if its working-set RAM is at most this
# fraction of total device RAM, leaving room for Android, the app, and the OS.
# 0.7 lets a 6 GB phone (Galaxy A32-class) reach the 3B model, which carries the
# TARS character noticeably better than the smaller ones.
_HEADROOM = 0.7


@dataclass(frozen=True)
class ModelSpec:
    id: str
    label: str           # human name, light -> heavy
    params: str          # e.g. "3B"
    file_mb: int         # download size of the main GGUF (Q4_K_M, approx)
    min_ram_mb: int      # working-set RAM the model needs at runtime
    repo: str            # Hugging Face repo
    filename: str        # GGUF file inside the repo
    note: str
    mmproj_filename: str = ""   # vision projector GGUF (multimodal models only)
    mmproj_mb: int = 0          # its download size

    @property
    def url(self) -> str:
        return "https://huggingface.co/{}/resolve/main/{}".format(self.repo, self.filename)

    @property
    def vision(self) -> bool:
        return bool(self.mmproj_filename)

    @property
    def mmproj_url(self) -> str:
        if not self.mmproj_filename:
            return ""
        return "https://huggingface.co/{}/resolve/main/{}".format(self.repo, self.mmproj_filename)


# Qwen3-Instruct (text). TARS is a voice robot — he hears and speaks; this is the
# brain we're verifying first because it's light enough to actually load on a 6 GB
# phone (and it's the model already downloaded on the test device). Vision returns
# as a separate, verified step on a multimodal model (Qwen2.5-VL, see VISION_CATALOG),
# once the core hear->think->speak loop is proven on-device.
CATALOG: List[ModelSpec] = [
    ModelSpec(
        id="qwen3-0.6b", label="Feather", params="0.6B",
        file_mb=500, min_ram_mb=1200,
        repo="Qwen/Qwen3-0.6B-GGUF",
        filename="Qwen3-0.6B-Q4_K_M.gguf",
        note="Lightest. A pulse on a modest phone — terse, but his own words.",
    ),
    ModelSpec(
        id="qwen3-1.7b", label="Light", params="1.7B",
        file_mb=1100, min_ram_mb=2400,
        repo="Qwen/Qwen3-1.7B-GGUF",
        filename="Qwen3-1.7B-Q4_K_M.gguf",
        note="The reliable pick for a 6 GB phone (Galaxy A32-class): loads in "
             "~15s and answers in seconds — fast enough to actually feel alive.",
    ),
    ModelSpec(
        id="qwen3-4b", label="Standard", params="4B",
        file_mb=2500, min_ram_mb=4400,
        repo="Qwen/Qwen3-4B-GGUF",
        filename="Qwen3-4B-Q4_K_M.gguf",
        note="Richer character, but heavy: ~1-2 min to load and slow to speak on a "
             "6 GB phone. Recommended only for 8 GB+ devices.",
    ),
    ModelSpec(
        id="qwen3-8b", label="Heavy", params="8B",
        file_mb=5000, min_ram_mb=8000,
        repo="Qwen/Qwen3-8B-GGUF",
        filename="Qwen3-8B-Q4_K_M.gguf",
        note="A real on-device mind. Flagships with 12 GB+ only.",
    ),
]

# Multimodal brains (vision). Wired and ready, but not the default until the core
# loop is verified and we've confirmed one actually loads in 6 GB on the A32.
VISION_CATALOG: List[ModelSpec] = [
    ModelSpec(
        id="qwen2.5-vl-3b", label="Standard (vision)", params="3B",
        file_mb=2200, min_ram_mb=3700,
        repo="ggml-org/Qwen2.5-VL-3B-Instruct-GGUF",
        filename="Qwen2.5-VL-3B-Instruct-Q4_K_M.gguf",
        mmproj_filename="mmproj-Qwen2.5-VL-3B-Instruct-f16.gguf",
        mmproj_mb=1300,
        note="TARS's eyes + voice on a 6 GB phone: sees through the camera and talks.",
    ),
]


def by_id(model_id: str) -> Optional[ModelSpec]:
    for spec in CATALOG:
        if spec.id == model_id:
            return spec
    return None


def total_ram_mb() -> Optional[int]:
    """Total device RAM in MB, or None if it can't be read. /proc/meminfo exists
    on Linux and Android alike."""
    try:
        for line in Path("/proc/meminfo").read_text().splitlines():
            if line.startswith("MemTotal:"):
                return int(line.split()[1]) // 1024  # kB -> MB
    except (OSError, ValueError, IndexError):
        pass
    return None


def fits(spec: ModelSpec, ram_mb: int) -> bool:
    return spec.min_ram_mb <= ram_mb * _HEADROOM


def recommend(ram_mb: Optional[int] = None) -> Optional[ModelSpec]:
    """The heaviest model that comfortably fits this device. None means even the
    lightest won't fit — TARS falls back to the scripted offline brain."""
    if ram_mb is None:
        ram_mb = total_ram_mb()
    if not ram_mb:
        return None
    fitting = [s for s in CATALOG if fits(s, ram_mb)]
    return fitting[-1] if fitting else None


def download(spec: ModelSpec, dest_dir: Path, on_progress=None) -> Path:
    """Download a model's GGUF into dest_dir. Returns the local path. Skips the
    download if the file is already there with the expected size."""
    dest_dir.mkdir(parents=True, exist_ok=True)
    target = dest_dir / spec.filename
    expected = spec.file_mb * 1024 * 1024
    if target.exists() and abs(target.stat().st_size - expected) < expected * 0.1:
        return target

    with urllib.request.urlopen(spec.url, timeout=60) as resp:
        total = int(resp.headers.get("Content-Length", expected))
        done = 0
        tmp = target.with_suffix(target.suffix + ".part")
        with open(tmp, "wb") as f:
            while True:
                chunk = resp.read(1 << 20)
                if not chunk:
                    break
                f.write(chunk)
                done += len(chunk)
                if on_progress:
                    on_progress(done, total)
        tmp.replace(target)
    return target
