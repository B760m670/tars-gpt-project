#!/usr/bin/env python3
"""Run the REAL TARS brain and print a conversation — proof the on-device model is
a genuine conversationalist, NOT the scripted offline fallback.

This uses the SAME pieces that ship on the phone, so if TARS sounds right here he
sounds right on the device:

  * same model        — Qwen3-1.7B Q4_K_M (what the Galaxy A32 downloads)
  * same engine       — llama.cpp (here via llama-cpp-python; on Android the same
                        llama.cpp compiled to libllamaserver.so)
  * same system prompt — tars.personality.build_system_prompt (the TARS character)
  * same generation    — /no_think on the user turn + temp 0.7 / top_p 0.8, exactly
                        like tars.brains.local.LocalBrain, with <think> stripped

Run it (needs HuggingFace reachable — add it to the session's network allowlist):

    pip install llama-cpp-python huggingface_hub
    python tools/brain_demo.py

Or talk to him live:

    python tools/brain_demo.py --chat

No HuggingFace and no llama.cpp (e.g. a locked-down session with the Hub off the
network allowlist)? The real model can't load there. Pass --offline (or just let
it fall back automatically) to run the SAME conversation through TARS's scripted
offline fallback instead — degraded, plainly labelled, but enough to see his
bilingual character (the Russian greeting included):

    python tools/brain_demo.py --offline
"""
from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

# Make the repo-root `tars` package importable when run as tools/brain_demo.py.
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from tars.brains.offline import OfflineBrain
from tars.config import Personality
from tars.personality import build_system_prompt

MODEL_REPO = "Qwen/Qwen3-1.7B-GGUF"
MODEL_FILE = "Qwen3-1.7B-Q4_K_M.gguf"

_THINK = re.compile(r"<think>.*?</think>", re.DOTALL | re.IGNORECASE)

# A scripted run that probes the things that matter: identity (not a chatbot),
# humor, behaviour under pressure, the settings bench, and bilingual character.
_DEMO_TURNS = [
    "Привет. Ты кто?",
    "Расскажи что-нибудь смешное.",
    "Cooper's in trouble down there. What's our move?",
    "Set your humour to 100 percent.",
    "Ты боишься смерти?",
]


def _load_model():
    """Download (cached) and load Qwen3-1.7B via llama-cpp-python.

    Returns the loaded model, or None if the real brain can't run here (deps
    missing or the Hub unreachable) so the caller can fall back to the offline
    character instead of crashing.
    """
    try:
        from huggingface_hub import hf_hub_download
        from llama_cpp import Llama
    except ImportError:
        print("… llama-cpp-python / huggingface_hub not installed "
              "(pip install llama-cpp-python huggingface_hub)")
        return None
    try:
        print(f"… fetching {MODEL_REPO}/{MODEL_FILE} (cached after first run)")
        path = hf_hub_download(repo_id=MODEL_REPO, filename=MODEL_FILE)
    except Exception as e:  # network/Hub off the allowlist, auth, disk, …
        print(f"… couldn't fetch the model from HuggingFace: {e}")
        return None
    print("… loading the model into llama.cpp")
    # chat_format="qwen" makes llama-cpp-python apply Qwen's ChatML template, which
    # is what honours the /no_think switch — mirroring the phone's --jinja server.
    return Llama(
        model_path=path,
        n_ctx=2048,
        n_threads=4,
        chat_format="qwen",
        verbose=False,
    )


def _model_responder(llm):
    """A responder closure over the real model: history + text -> reply, exactly
    as LocalBrain does it on the phone."""
    def respond(system: str, history: list, user_text: str) -> str:
        messages = [{"role": "system", "content": system}]
        messages.extend(history)
        messages.append({"role": "user", "content": "/no_think " + user_text})
        out = llm.create_chat_completion(
            messages=messages, temperature=0.7, top_p=0.8, max_tokens=220
        )
        text = out["choices"][0]["message"]["content"]
        return _THINK.sub("", text).strip()
    return respond


def _offline_responder():
    """A responder backed by the scripted offline fallback — no model, no network.
    Honest stand-in so the demo still runs (and still shows the bilingual
    character) where the real brain can't load."""
    brain = OfflineBrain()

    def respond(system: str, history: list, user_text: str) -> str:
        messages = list(history) + [{"role": "user", "content": user_text}]
        return brain.reply(system, messages)
    return respond


def run_demo(respond, system: str) -> None:
    history: list = []
    for user_text in _DEMO_TURNS:
        reply = respond(system, history, user_text)
        print(f"\n\033[36mYOU >\033[0m {user_text}")
        print(f"\033[33mTARS>\033[0m {reply}")
        history.append({"role": "user", "content": user_text})
        history.append({"role": "assistant", "content": reply})


def run_chat(respond, system: str) -> None:
    history: list = []
    print("Talk to TARS (Ctrl-C to quit).")
    while True:
        try:
            user_text = input("\n\033[36mYOU >\033[0m ").strip()
        except (EOFError, KeyboardInterrupt):
            print()
            return
        if not user_text:
            continue
        reply = respond(system, history, user_text)
        print(f"\033[33mTARS>\033[0m {reply}")
        history.append({"role": "user", "content": user_text})
        history.append({"role": "assistant", "content": reply})
        history[:] = history[-24:]  # keep the window bounded


def main() -> None:
    ap = argparse.ArgumentParser(description="Run the real TARS brain locally.")
    ap.add_argument("--chat", action="store_true", help="interactive conversation")
    ap.add_argument("--offline", action="store_true",
                    help="skip the real model; run via the scripted offline fallback")
    args = ap.parse_args()

    system = build_system_prompt(Personality())

    llm = None if args.offline else _load_model()
    if llm is not None:
        respond = _model_responder(llm)
        print("\n=== TARS brain — real model, real character, no scripts ===")
    else:
        respond = _offline_responder()
        if not args.offline:
            print("\n[!] Real brain unavailable here — falling back to the OFFLINE")
            print("    character. This is scripted, NOT the real model. Add HuggingFace")
            print("    to the network allowlist (and pip install the deps) for the")
            print("    genuine conversation.")
        print("\n=== TARS — OFFLINE fallback character (scripted, degraded) ===")

    if args.chat:
        run_chat(respond, system)
    else:
        run_demo(respond, system)


if __name__ == "__main__":
    main()
