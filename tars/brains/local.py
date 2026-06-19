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

import re
from typing import Dict, List

from .base import Brain, BrainError
from ._http import get_json, post_json

# Strips Qwen3 thinking blocks that leak into the output when thinking mode
# can't be turned off cleanly (e.g. older --jinja builds that ignore /no_think
# in the system message).
_THINK_RE = re.compile(r"<think>.*?</think>", re.DOTALL | re.IGNORECASE)


class LocalBrain(Brain):
    name = "local"

    def __init__(self, url, model=""):
        self.url = url.rstrip("/")
        self.model = model or "local"

    def available(self) -> bool:
        """True once the embedded llama.cpp server is up with a model loaded.

        /health is the canonical signal (200 {"status":"ok"} when ready, 503 while
        loading). But builds differ, and a wrongly-failed check here is exactly what
        sent every message to the offline brain — so we ALSO accept a live
        /v1/models, which only answers 200 once the model is loaded. Either one
        means the engine can think."""
        try:
            data = get_json("{}/health".format(self.url), timeout=3)
            if str(data.get("status", "")).lower() in ("ok", "ready", ""):
                return True
        except Exception:
            pass
        try:
            get_json("{}/v1/models".format(self.url), timeout=3)
            return True
        except Exception:
            return False

    def reply(self, system: str, messages: List[Dict[str, str]]) -> str:
        # Qwen3 thinking mode: /no_think MUST go into the last *user* message,
        # not the system message (the Jinja template only checks the user turn).
        # We prepend it to the final user message so the model skips the
        # <think>…</think> chain-of-thought block — wrong for a spoken robot and
        # far too slow on a phone CPU.
        msgs_raw = list(messages)
        if msgs_raw and msgs_raw[-1]["role"] == "user":
            last = msgs_raw[-1].copy()
            if not last["content"].startswith("/no_think"):
                last["content"] = "/no_think " + last["content"]
            msgs_raw[-1] = last

        msgs = [{"role": "system", "content": system}]
        msgs.extend({"role": m["role"], "content": m["content"]} for m in msgs_raw)

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
            text = data["choices"][0]["message"]["content"].strip()
        except (KeyError, IndexError):
            raise BrainError("unexpected local response: {}".format(str(data)[:300]))

        # Safety net: strip any <think>…</think> that still leaked through.
        text = _THINK_RE.sub("", text).strip()
        return text
