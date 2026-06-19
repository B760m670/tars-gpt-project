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
"""
from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

# Make the repo-root `tars` package importable when run as tools/brain_demo.py.
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

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
    """Download (cached) and load Qwen3-1.7B via llama-cpp-python."""
    try:
        from huggingface_hub import hf_hub_download
        from llama_cpp import Llama
    except ImportError:
        sys.exit("Install deps first:  pip install llama-cpp-python huggingface_hub")
    print(f"… fetching {MODEL_REPO}/{MODEL_FILE} (cached after first run)")
    path = hf_hub_download(repo_id=MODEL_REPO, filename=MODEL_FILE)
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


def _reply(llm, system: str, history: list, user_text: str) -> str:
    """One turn, exactly as LocalBrain does it on the phone."""
    messages = [{"role": "system", "content": system}]
    messages.extend(history)
    messages.append({"role": "user", "content": "/no_think " + user_text})
    out = llm.create_chat_completion(
        messages=messages, temperature=0.7, top_p=0.8, max_tokens=220
    )
    text = out["choices"][0]["message"]["content"]
    return _THINK.sub("", text).strip()


def run_demo(llm, system: str) -> None:
    history: list = []
    for user_text in _DEMO_TURNS:
        reply = _reply(llm, system, history, user_text)
        print(f"\n\033[36mYOU >\033[0m {user_text}")
        print(f"\033[33mTARS>\033[0m {reply}")
        history.append({"role": "user", "content": user_text})
        history.append({"role": "assistant", "content": reply})


def run_chat(llm, system: str) -> None:
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
        reply = _reply(llm, system, history, user_text)
        print(f"\033[33mTARS>\033[0m {reply}")
        history.append({"role": "user", "content": user_text})
        history.append({"role": "assistant", "content": reply})
        history[:] = history[-24:]  # keep the window bounded


def main() -> None:
    ap = argparse.ArgumentParser(description="Run the real TARS brain locally.")
    ap.add_argument("--chat", action="store_true", help="interactive conversation")
    args = ap.parse_args()

    system = build_system_prompt(Personality())
    llm = _load_model()
    print("\n=== TARS brain — real model, real character, no scripts ===")
    if args.chat:
        run_chat(llm, system)
    else:
        run_demo(llm, system)


if __name__ == "__main__":
    main()
