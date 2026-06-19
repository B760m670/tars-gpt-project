# TARS — a mind that lives in your device

An open, free, portable "TARS brain" inspired by the robot from *Interstellar*:
it talks, remembers, and has a tunable personality (humor / honesty / sarcasm —
the famous in-film settings). Built to run anywhere — Linux, desktop, server,
and weak Android phones — because the heavy thinking is *swappable*.

## The idea: four sockets

The core is like a power strip with four sockets. Each socket has an interface,
and you can plug in any compatible "driver" — the core doesn't care which:

| Socket  | Job                       | Drivers (now → later)                          |
|---------|---------------------------|------------------------------------------------|
| Ears    | speech → text (STT)       | *(stub)* → Vosk / whisper.cpp / openWakeWord   |
| **Brain** | text → witty TARS reply | **on-device llama.cpp (GGUF) + Ollama (power users) + offline fallback** |
| Voice   | text → speech (TTS)       | **Piper (sherpa-onnx), RU + EN** → **AI clone of the original TARS voice** (F5-TTS / XTTS / RVC) |
| Memory  | remember you & the talk   | **SQLite** → + vector recall                   |

**"Hybrid brain"** = the Brain socket holds several drivers and falls back:
the **on-device model** does the real thinking; and, as a guaranteed last
resort, a **dependency-free offline brain** so TARS is *never* dead — with zero
setup, no network, he still answers in character. Everything runs locally and
free — no cloud, no API keys, no quotas.

**Bilingual.** TARS replies in the language you speak to him — **Russian or
English** — keeping the same dry character in both. (The offline fallback
detects the language too.)

## Free but powerful

All of this is $0 and fully offline:

- **Brain (on-device, free):** an embedded **llama.cpp** engine loads a **GGUF
  model you pick from the built-in manager** — a ladder of **Qwen3** from *Feather*
  (0.6B, runs on a modest phone) to *Heavy* (8B, flagships). TARS reads the device
  RAM and recommends the heaviest model that fits (a 6 GB phone gets **Qwen3-4B**);
  type `/models` to see the list. Qwen3's "thinking" mode is turned off so TARS
  speaks instead of monologuing.
  (This is the "install models, light to heavy" idea — done with the engine
  Ollama itself uses, so nothing extra has to be installed on the phone.)
- **Brain (power users):** point at an **Ollama** server on a PC/Termux.
- **Voice (later):** the *original* TARS voice is reproduced by **AI voice
  cloning** from film audio (F5-TTS / Coqui XTTS v2 / RVC) — not a robotic TTS.
  Heavy clone runs on a capable device or a free GPU (Colab / HF Spaces); weak
  phones get a lighter "trained-on-TARS" voice or pre-generated phrases.

## Run milestone 1 (talk to TARS in your console)

Needs only Python 3.8+ (standard library — no pip installs for the brain).

```bash
python -m tars
```

Out of the box this runs on the offline brain (in-character, but not a real
mind). For full thinking on a desktop, run a local engine and point TARS at it:
- `ollama serve` (then `ollama pull qwen2.5:3b`), or
- a llama.cpp `llama-server` on `127.0.0.1:8080`.

On Android the app does this for you — tap **Brain** to download the GGUF that
fits your phone and the embedded `llama-server` starts automatically.

In the chat:
```
/humor 75        # tune personality live (0–100), like the film
/honesty 90
/sarcasm 40
/remember name=Cooper
/facts
/settings
/help
/quit
```

## Roadmap

1. ✅ **M1 — Character in the console.** Portable core, swappable hybrid brain,
   personality settings, memory.
2. **M2 — Ears.** Wake word ("Hey TARS") + offline STT → TARS hears you.
3. **M3 — Voice.** AI clone of the original TARS voice plugged into the Voice
   socket; cloud-clone on capable devices, light voice on weak ones.
4. **M4 — Android app.** 🚧 In progress: a Chaquopy app that runs the `tars`
   core inside an APK (minSdk 21 = Android 5), built and published by GitHub
   Actions. Next: an always-on background service so TARS answers anytime. See
   [`android/`](android/).
5. **M5 — Skills & richer memory.** Tools/skills + vector recall.

Prior art we lean on: TarsGPT, TARS-AI Community, plus the standard
openWakeWord / Vosk / Piper-sherpa / F5-TTS stack.
