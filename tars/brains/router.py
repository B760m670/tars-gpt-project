"""The hybrid brain: try each driver in order, fall back on failure.

This is what lets TARS think with the on-device llama.cpp model when it's loaded,
and still answer in character (the scripted offline brain) when it isn't.
"""
from __future__ import annotations

from typing import Dict, List

from .base import Brain, BrainError


class BrainRouter(Brain):
    name = "router"

    def __init__(self, brains: List[Brain]):
        self.brains = brains
        self.last_used = None
        self.last_errors: List[str] = []   # why each brain was skipped/failed

    def available(self) -> bool:
        return any(b.available() for b in self.brains)

    def reply(self, system: str, messages: List[Dict[str, str]]) -> str:
        self.last_errors = []
        for brain in self.brains:
            if not brain.available():
                self.last_errors.append("{}: unavailable".format(brain.name))
                continue
            try:
                out = brain.reply(system, messages)
                self.last_used = brain.name
                return out
            except BrainError as e:
                self.last_errors.append("{}: {}".format(brain.name, e))
        self.last_used = None
        if self.last_errors:
            raise BrainError("no brain could answer -> " + " | ".join(self.last_errors))
        raise BrainError(
            "no brain available. Tap 'Brain' to download the on-device model, "
            "or run a llama.cpp/Ollama server."
        )
