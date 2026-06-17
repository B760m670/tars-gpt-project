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
| **Brain** | text → witty TARS reply | **Gemini, Groq (free cloud) + on-device llama.cpp + Ollama + offline fallback** |
| Voice   | text → speech (TTS)       | *(stub)* → **AI clone of the original TARS voice** (F5-TTS / XTTS / RVC) |
| Memory  | remember you & the talk   | **SQLite** → + vector recall                   |

**"Hybrid brain"** = the Brain socket holds several drivers and falls back:
online → free cloud model (smarter); offline → a local model; and, as a
guaranteed last resort, a **dependency-free offline brain** so TARS is *never*
dead — with zero setup, no key and no network, he still answers in character.

**Bilingual.** TARS replies in the language you speak to him — **Russian or
English** — keeping the same dry character in both. (The offline fallback
detects the language too.)

## Free but powerful

All of this is $0:

- **Brain (cloud, free, no card):** Google **Gemini** (AI Studio, ~1500 req/day),
  **Groq** (Llama 3.3 70B, fast), and others. Get a key in a minute.
- **Brain (on-device, free):** an embedded **llama.cpp** engine loads a **GGUF
  model you pick from the built-in manager** — a ladder from *Feather* (0.5B,
  runs on a modest phone) to *Heavy* (7B, flagships). TARS reads the device RAM
  and recommends the heaviest model that fits; type `/models` to see the list.
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
cd tars
cp .env.example .env        # then put a free key in it (see links below)
python -m tars
```

Get a free key (no credit card):
- Gemini: https://aistudio.google.com/apikey  → `GEMINI_API_KEY`
- Groq:   https://console.groq.com/keys       → `GROQ_API_KEY`
- or run `ollama serve` locally for full offline.

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
