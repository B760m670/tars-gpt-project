# TARS — an on-device mind for your phone

A free, offline AI companion inspired by TARS from *Interstellar*. The thinking
runs **entirely on the phone** — no cloud, no API keys, no accounts. v1 is the
brain: a real on-device LLM you talk to in **Russian or English**.

## How it works (v1: brain + text)

- **Brain — llama.cpp, in-process.** The app embeds the llama.cpp engine and runs
  a **Qwen3** GGUF model directly inside the process via JNI (no local server, no
  ports). Qwen3 is bilingual (RU/EN) and carries the dry TARS character well at
  phone-sized models. The app reads device RAM and picks the model that fits
  (0.6B / 1.7B).
- **Personality dials.** Humor / Honesty / Discretion / Sarcasm — the in-film
  "settings bench", applied live to the running engine.
- **Offline fallback.** With no model loaded yet, TARS still answers in character
  (scripted) so he's never dead.

Everything runs locally. The model is **Qwen3** (open weights, by Alibaba) — it
runs 100% on the device; nothing is sent anywhere.

## Install

The debug APK is published to the repo's **`latest`** GitHub release (built by
GitHub Actions). Download it on the phone and install. Then:

1. Tap **Brain** once — it downloads the model (from this repo's `models`
   release) and loads the engine. Wait for `engine READY` and the self-test line.
2. Talk to TARS. The on-device model answers; replies in your language.

> First time only: the model GGUFs must exist in the `models` release. They're
> mirrored there from HuggingFace by the **Mirror models to release** workflow
> (`.github/workflows/models.yml`) — HuggingFace is only ever touched by the CI
> runner, never by the phone.

## Build

CI (`.github/workflows/android.yml`) compiles llama.cpp (pinned tag) for arm64
via the app's CMake `externalNativeBuild`, then assembles a signed debug APK.
arm64-only, `minSdk 30`.

## Roadmap

1. ✅ **Brain + text** — real on-device Qwen3, personality dials, offline fallback.
2. **Voice** — a tunable TARS voice: a base TTS + an on-device effect bench
   (pitch/formant, comms band-pass, grit, "vintage space radio" filter).
3. **Ears** — wake word + on-device speech input.

## Layout

```
android/app/src/main/
  cpp/            JNI bridge to llama.cpp (tars_llm.cpp) + CMake
  java/com/tars/app/
    LlamaNative   raw JNI surface
    LlamaBrain    drives the engine on a worker thread
    Personality   the TARS character prompt + dials
    OfflineBrain  scripted, never-dead fallback
    ModelCatalog  device-RAM-aware model pick + release URLs
    ModelDownloader  OkHttp download (resume, honest errors)
    MainActivity  the console UI
```
