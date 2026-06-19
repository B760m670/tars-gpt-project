"""Brain socket: swappable LLM drivers + a hybrid router that falls back across
them (the on-device llama.cpp model first, the scripted offline brain last)."""
from __future__ import annotations

from ..config import Settings
from .base import Brain, BrainError
from .local import LocalBrain
from .offline import OfflineBrain
from .ollama import OllamaBrain
from .router import BrainRouter


def build_brain(settings: Settings) -> BrainRouter:
    registry = {
        "local": lambda: LocalBrain(settings.local_url, settings.local_model),
        "ollama": lambda: OllamaBrain(settings.ollama_url, settings.ollama_model),
        "offline": lambda: OfflineBrain(),
    }
    brains = [registry[name]() for name in settings.brain_order if name in registry]
    # The offline brain is the guaranteed last resort: if the user's brain_order
    # didn't include it, append it so TARS is never left without an answer.
    if not any(b.name == "offline" for b in brains):
        brains.append(OfflineBrain())
    return BrainRouter(brains)


__all__ = ["Brain", "BrainError", "BrainRouter", "LocalBrain", "build_brain"]
