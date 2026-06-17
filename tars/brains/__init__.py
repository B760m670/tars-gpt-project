"""Brain socket: swappable LLM drivers + a hybrid router that falls back across
them (free cloud first, local offline second)."""
from __future__ import annotations

from ..config import Settings
from .base import Brain, BrainError
from .gemini import GeminiBrain
from .groq import GroqBrain
from .offline import OfflineBrain
from .ollama import OllamaBrain
from .router import BrainRouter


def build_brain(settings: Settings) -> BrainRouter:
    registry = {
        "gemini": lambda: GeminiBrain(settings.gemini_key, settings.gemini_model),
        "groq": lambda: GroqBrain(settings.groq_key, settings.groq_model),
        "ollama": lambda: OllamaBrain(settings.ollama_url, settings.ollama_model),
        "offline": lambda: OfflineBrain(),
    }
    brains = [registry[name]() for name in settings.brain_order if name in registry]
    # The offline brain is the guaranteed last resort: if the user's brain_order
    # didn't include it, append it so TARS is never left without an answer.
    if not any(b.name == "offline" for b in brains):
        brains.append(OfflineBrain())
    return BrainRouter(brains)


__all__ = ["Brain", "BrainError", "BrainRouter", "build_brain"]
