"""Local on-device brain: llama.cpp running *inside* the device.

This is the driver for the model the user picks from the model manager. It talks
to a llama.cpp server (`llama-server`) over its OpenAI-compatible endpoint —
the very engine Ollama uses under the hood, but embedded directly so nothing
external has to be installed. On a phone, the app starts this server on
localhost and loads the chosen GGUF; on a desktop you can run `llama-server`
yourself to develop against it.

Same stdlib-only HTTP as the cloud brains, so the core stays dependency-free.
"""
from __future__ import annotations

from typing import Dict, List

from .base import Brain, BrainError
from ._http import get_json, post_json


class LocalBrain(Brain):
    name = "local"

    def __init__(self, url, model=""):
        self.url = url.rstrip("/")
        self.model = model or "local"

    def available(self) -> bool:
        """True only when the embedded llama.cpp server is up with a model
        loaded. llama-server reports readiness on /health."""
        try:
            data = get_json("{}/health".format(self.url), timeout=2)
            return str(data.get("status", "")).lower() in ("ok", "ready", "")
        except Exception:
            return False

    def reply(self, system: str, messages: List[Dict[str, str]]) -> str:
        # Qwen3 ships with a "thinking" mode that emits a long internal monologue
        # before the answer — wrong for a deadpan, spoken TARS and far too slow on
        # a phone. The "/no_think" soft switch in the system message turns it off
        # (the server is started with --jinja so the model's own template honours
        # it). Harmless on models that don't have the switch.
        sys_text = system.rstrip() + "\n\n/no_think"
        msgs = [{"role": "system", "content": sys_text}]
        msgs.extend({"role": m["role"], "content": m["content"]} for m in messages)
        # Qwen3's recommended non-thinking sampling (temp 0.7 / top_p 0.8) keeps
        # him sharp and in-character without rambling.
        payload = {
            "model": self.model,
            "messages": msgs,
            "temperature": 0.7,
            "top_p": 0.8,
        }
        data = post_json(
            "{}/v1/chat/completions".format(self.url), payload, timeout=180
        )
        try:
            return data["choices"][0]["message"]["content"].strip()
        except (KeyError, IndexError):
            raise BrainError("unexpected local response: {}".format(str(data)[:300]))
