# Vibes Quantum Server 2.8.0 — Voice and AI Upgrade Report

## Scope

This release implements the server-side portions of voice upgrades 14 and 15 on top of the 2.7.0 platform baseline.

## 14. Realtime text-to-speech migration

- The authenticated `POST /api/v1/audio/speech` compatibility endpoint no longer calls the retiring `/v1/audio/speech` API.
- It opens a server-to-provider Realtime WebSocket session using `gpt-realtime-2.1-mini`, sends text input, collects `response.output_audio.delta` chunks, and returns 24 kHz mono PCM as WAV (default) or raw PCM.
- Provider credentials remain on the server. Supported voices are restricted to the Realtime voice set.
- MP3, AAC and Opus are intentionally rejected by this adapter; clients must accept WAV or PCM.
- The retiring OpenAI `/audio/speech` model family is scheduled to shut down on January 6, 2027. See https://developers.openai.com/api/docs/deprecations and https://developers.openai.com/api/docs/guides/realtime.

## 15. Streaming speech-to-text

- Added authenticated `WS /api/v1/audio/transcriptions/stream` for account-bound Vyron X sessions.
- The server opens a provider Realtime transcription session with `gpt-live-transcribe` (configurable only to the explicitly permitted model `gpt-transcribe`), PCM16 mono at 24 kHz, and low-delay transcription.
- Client `input_audio_buffer.append`, `input_audio_buffer.commit` and `input_audio_buffer.clear` messages are validated and relayed. A local `ping`/`pong` heartbeat avoids sending non-provider events upstream.
- Provider lifecycle events are forwarded; the server emits `session.ready` only after receiving `session.updated`, and forwards partial `conversation.item.input_audio_transcription.delta` and final `conversation.item.input_audio_transcription.completed` events unchanged.
- Audio chunks are limited to 72 KiB decoded PCM per frame; the overall session is limited to 24 MiB and 10 minutes. Connections are rate-limited and reserve/settle the existing per-user `stt` cloud entitlement bucket.
- The completed-recording `POST /api/v1/audio/transcriptions` endpoint remains available for mobile clients during migration.

## Configuration

Set a server-only key:

```dotenv
VIBES_REALTIME_API_KEY=...
VIBES_REALTIME_BASE_URL=wss://api.openai.com/v1/realtime
VIBES_REALTIME_TTS_MODEL=gpt-realtime-2.1-mini
VIBES_REALTIME_STT_MODEL=gpt-live-transcribe
VIBES_TTS_DEFAULT_VOICE=marin
VIBES_REALTIME_STT_SESSION_RESERVE_USD=0.02
```

Optional per-modality Realtime URL overrides are `VIBES_TTS_REALTIME_URL` and `VIBES_STT_REALTIME_URL`. For separate credentials, configure `VIBES_TTS_API_KEY` and/or `VIBES_STT_API_KEY`. Never place these credentials in the Android app, browser bundle, or API response.

## Client migration still required

The Android project is not included in this server archive. The Android microphone client must:

1. Connect to `/api/v1/audio/transcriptions/stream` using the `Authorization: Bearer <access-token>` header.
2. Convert microphone capture to mono PCM16 at 24,000 Hz and append small base64 chunks.
3. Commit the input buffer at speech-turn completion, and render delta transcript events as provisional text keyed by `item_id`; replace the provisional text with the matching completed event.
4. Continue to support the completed-recording endpoint until the client migration is released.
5. For TTS, accept WAV or raw PCM and honor `X-Vibes-Audio-Sample-Rate`; MP3/AAC/Opus are no longer returned by this route.

## Tests and verification

Added/extended:

- `RealtimeVoiceProtocolTest`: Realtime STT session shape, locale validation, accepted event set, chunk bounds, Realtime TTS model/audio configuration, and WAV framing.
- `PlatformPersistenceTest`: regression coverage that live transcription reservations use the shared `stt` cloud-entitlement category and settle without leaving reserved spend behind.

Verification status in this build environment:

- `./gradlew test --no-daemon`: **not executed**; the repository wrapper requires Gradle 9.6.1, which is neither installed nor cached in this environment.
- `python3 -m pytest -q`: collection stopped on two missing worker import paths (`alis_evaluation_lab`, `alis_research_pipeline`); these are unrelated to the Kotlin voice changes.
- `python3 -m pytest -q scripts/test_*.py tom-evaluation/tests`: many tests completed without reported failures, but the command exceeded the 120-second execution limit before pytest printed its final summary. It is not counted as a passing suite.

Run the full Kotlin suite with the project's Gradle 9.6.1 wrapper in the deployment/build environment before production rollout. Then test against a live Realtime credential and update the Android client; source-level protocol tests do not prove upstream account/model entitlement or real-device microphone compatibility.
