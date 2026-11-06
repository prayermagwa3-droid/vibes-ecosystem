# Vyron X — Phase 5 Realtime Voice

## Scope

This phase migrates app speech synthesis away from the legacy completed-audio `/audio/speech` workflow and adds live streaming speech-to-text. It updates both the Android client and the Vibes Quantum Ktor server.

## Android changes

- `SpeechManager` now captures mono PCM16 at 24 kHz with `AudioRecord` and sends short 100 ms chunks to the authenticated Vibes Realtime transcription WebSocket.
- Partial transcription deltas update the WebView composer, native chat composer, and Voice Studio transcript as they arrive. A committed turn delivers the final transcript.
- When the Realtime connection cannot be established or drops, the client retains an authenticated completed-recording fallback through `POST /api/v1/audio/transcriptions`.
- `TtsManager` now requests speech through the authenticated Vibes Realtime speech WebSocket and plays incoming PCM16 audio deltas through `AudioTrack`.
- The client keeps the Vibes account bearer token in the native client; it never contains an OpenAI provider key.
- Voice Studio and Settings disclose that synthesized speech is AI-generated. Rate values are normalized to the Realtime instruction range used by this project (50–200%).
- Existing foreground/background voice paths use the same updated speech and TTS managers.

## Server changes

- Adds authenticated WebSocket routes `/api/v1/audio/realtime/transcriptions` and `/api/v1/audio/realtime/speech`.
- Relays provider WebSockets server-to-server. Provider keys are loaded only from server environment variables.
- Uses `gpt-live-transcribe` for partial transcription with 24 kHz PCM16, manual commit, a `languages` hint array where supplied, and low-delay captions.
- Uses `gpt-realtime-2.1-mini` for Realtime speech synthesis and streams PCM16 `response.output_audio.delta` data to the client.
- Applies Vibes account authentication, per-user mutation rate limiting, cloud entitlement reservations, bounded session timeouts, payload limits, and hashed user safety identifiers.
- Retires the legacy Vibes `POST /api/v1/audio/speech` route with HTTP 410. The recorded-file STT route remains available as a compatibility fallback.

## Server environment

Set these only on the Ktor server:

```dotenv
VIBES_STT_BASE_URL=https://api.openai.com/v1
VIBES_STT_API_KEY=your-server-side-key
VIBES_STT_MODEL=gpt-transcribe
VIBES_STT_REALTIME_MODEL=gpt-live-transcribe
VIBES_TTS_BASE_URL=https://api.openai.com/v1
VIBES_TTS_API_KEY=your-server-side-key
VIBES_TTS_REALTIME_MODEL=gpt-realtime-2.1-mini
VIBES_TTS_DEFAULT_VOICE=marin
VIBES_TTS_VOICES=marin,cedar,coral,alloy,ash,sage,shimmer,verse,ballad,echo
```

`VIBES_STT_API_KEY` and `VIBES_TTS_API_KEY` can fall back to `VIBES_UPSTREAM_API_KEY` if the latter is already used by the server. Never copy provider keys into the Android project.

## Verification status

- Static contract checks: passed.
- XML parsing: passed.
- JavaScript syntax/HTML checks: passed.
- ZIP integrity: passed after release packaging.
- Kotlin parser diagnostics: no syntax-specific `expecting`/unexpected-token diagnostics were reported; full compilation was blocked by absent external Android/Ktor classpaths.
- Android `./gradlew assembleDebug`: not run successfully because Gradle 9.6.1+ is not installed in this environment.
- Server `./gradlew test`: not run successfully because Gradle 9.6.1 is not installed or cached in this environment.
- Realtime provider connectivity/audio quality has not been live-tested against a configured API key. Configure the server environment and test on a physical Android device with microphone permission before production use.
