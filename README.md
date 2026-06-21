# TARS — a terminal companion for your phone

A free AI companion inspired by TARS from *Interstellar*, presented as a
black-screen, phosphor-green **CRT terminal**. You talk to TARS in **Russian or
English**; he answers in the same scrolling log.

## Architecture

The mind is a pluggable `Brain`:

- **StubBrain** — placeholder so the terminal runs before anything is wired.
- **RemoteBrain** (next) — a **pool of free, OpenAI-compatible cloud providers**
  (Google Gemini, Groq, Cerebras, OpenRouter-free, …) with **auto-failover**:
  call the preferred provider, and on a rate-limit / quota error put it on a
  cooldown and fall through to the next. For a single user the combined free
  tiers behave effectively unlimited.

Why cloud, not on-device: phone-sized local models are too weak for fluent
Russian and real knowledge. Free cloud models are far stronger; the cost is that
requests leave the device (not private) and each provider has rate limits — which
the pool smooths over. Providers' free API keys are supplied by the user.

## Install

The debug APK is published to the repo's **`latest`** GitHub release (built by
GitHub Actions). Download it on the phone and install.

## Build

CI (`.github/workflows/android.yml`) assembles a signed debug APK with Gradle.
Pure Kotlin/Android, `minSdk 30`, no native code.

## Layout

```
android/app/src/main/
  java/com/tars/app/
    Brain         the pluggable mind interface (+ StubBrain)
    MainActivity  the TARS terminal UI
  res/layout/activity_main.xml   black CRT screen, green monospace
```

## Roadmap

1. ✅ **Terminal shell** — TARS CRT terminal + pluggable brain.
2. **Cloud pool brain** — RemoteBrain over a pool of free providers, key entry,
   auto-failover, model routing.
3. **Voice / ears** — TTS with an effect bench, then speech input.
