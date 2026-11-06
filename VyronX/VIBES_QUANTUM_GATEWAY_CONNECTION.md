# Vyron X → Vibes Quantum connection contract

**Canonical server:** `VibesQuantumServer` (Kotlin/Ktor)  
**Primary Android client:** `VibesVyronX`  
**Former Quantum Android project:** removed; the Ktor server is the only Quantum runtime

## Base URL rule

The default server ROOT is Render: `https://vibes-quantum-api.onrender.com`. The failover list then checks Cloudflare (`https://api.vibesquantum.tech`), DuckDNS (`https://vibesquantum.duckdns.org`), `vibesquantum.tech`, `vibesquantum.com`, and their configured API aliases. Do not append `/v1`, `/api`, or `/api/v1` to the saved base URL. Every REST endpoint is rooted at `/api/v1/`; WebSockets use `wss://HOST/api/v1/...`. Remote cleartext HTTP is rejected. Localhost/emulator HTTP is permitted only for development. Every fallback hostname must be DNS-configured to reach the same Quantum backend before release; never add an untrusted domain because bearer tokens may be sent to the selected failover host.

## Authentication boundary

Vyron X supports `POST /api/v1/auth/email/register`, `POST /api/v1/auth/email/login`, `POST /api/v1/auth/phone/start` + `POST /api/v1/auth/phone/verify` (when a phone verifier is configured), and native Google sign-in through `POST /api/v1/auth/google`. All flows return a signed 15-minute access JWT and a rotating 30-day refresh token accepted by the same Quantum user-session routes. Account tokens are never placed in URL query parameters. App-bound keys are separate credentials and must be narrowly scoped; Quantum provider keys and worker credentials exist only in the server environment. Refresh/session state is persisted by the configured auth security store; for Render, keep the `/var/data` persistent disk attached or configure a shared PostgreSQL store before running multiple instances.

## Server-discovered capabilities

Vyron X fetches these authenticated catalogue endpoints on a background worker:

- `GET /api/v1/categories`
- `GET /api/v1/tools?categoryId={categoryId}` (optional focused lookup)
- `GET /api/v1/agents`
- `GET /api/v1/quantum/modules`
- `GET /api/v1/quantum/migration/status`

The chat UI builds its category and tool selectors from the returned catalogue. The selected category/tool is passed as user task context, not as a security privilege. Quantum remains the authority for actual endpoint permissions and execution.

## Main connection endpoints

- Health: `GET /api/v1/health`
- Readiness: `GET /api/v1/ready`
- Email registration/login: `POST /api/v1/auth/email/register`, `POST /api/v1/auth/email/login`
- Phone verification (when Twilio Verify is configured): `POST /api/v1/auth/phone/start`, `POST /api/v1/auth/phone/verify`
- Google sign-in: `POST /api/v1/auth/google`
- Current account: `GET /api/v1/auth/me`
- Models/provider status: `GET /api/v1/models`, `GET /api/v1/providers/status`
- Chat: `POST /api/v1/chat/completions` (SSE streaming)
- Theory of Mind: `POST /api/v1/theory-of-mind/analyze`
- Agent directory: `GET /api/v1/agents`
- Agent execution: `POST /api/v1/agents/execute` or task lifecycle under `/api/v1/agents/tasks`
- Projects: `/api/v1/workspace/jobs` and `/api/v1/workspace/brain`
- Shared approved knowledge: `GET /api/v1/me/approved-knowledge/search`
- Logout: `POST /api/v1/auth/logout`

## Quantum feature migration

The previous standalone Quantum Android project has been removed from this package. Server capabilities are owned by Ktor; device-bound features such as microphone permissions, audio capture, local file pickers, notifications and UI rendering remain in Vyron X. Non-Android Python evaluation/research/training assets are preserved under `VibesQuantumServer/workers/quantum-labs/` pending isolated worker deployment.

Actual model-weight training is not silently run on the phone or simulated by the server. Quantum exposes `/api/v1/training/jobs` as a scoped proxy to a separately deployed worker. It remains disabled until the operator configures all of the following on the server:

- `VIBES_TRAINING_ENABLED=true`
- `VIBES_TRAINING_WORKER_URL` (HTTPS; local HTTP only for loopback development)
- `VIBES_TRAINING_WORKER_API_KEY` (server-only secret, at least 32 characters)
- `VIBES_TRAINING_ARTIFACT_ALLOWED_HOSTS` (comma-separated exact HTTPS artifact hosts)
- `VIBES_TRAINING_JOBS_FILE` (durable job mapping path)

The calling app key must carry the `training:execute` scope. The proxy validates the dataset URL against the host allow-list, requires a SHA-256 hash and human approval metadata, refuses redirects, requires an idempotency key and scopes job IDs to the calling app. With no configured worker, Quantum returns `TRAIN_001`; it never reports a fake successful training run.

## Operator requirements

- Keep Google OAuth, AI provider, administrator, app-key and worker secrets on the server only.
- Use valid HTTPS externally; terminate TLS at a reverse proxy or secure tunnel with automatic certificate renewal.
- Keep the Ktor server data directory persistent; test backup restoration.
- There is no legacy Quantum Android app in this package; distribute Vyron X as the sole primary Android client.

## Current limitations to keep visible

- Vyron native accounts use a signed 15-minute access JWT plus a 30-day rotating refresh token. The client automatically refreshes and persists the replacement token; refresh reuse is detected server-side and requires a fresh sign-in.
- The primary app currently uses server-backed HTTP/SSE chat; authenticated WebSocket `/api/v1/ws/chat` integration is not yet validated end-to-end.
- Play Billing and production server-side subscription entitlements still require completion/verification.
- Training-weight execution requires a real trusted worker and remains disabled by default.
- The server and Android Gradle builds must still be run in an environment with the JDK/Gradle wrapper JAR/Android SDK available.


### Quantum Voice audio endpoints

All Voice STT/TTS work is routed through the authenticated Quantum server. Live STT captures 24 kHz mono PCM16 with Android `AudioRecord`, streams short chunks over `WS /api/v1/audio/realtime/transcriptions`, and displays `transcript.partial` captions before the final `transcript.completed` result. If the Realtime socket is unavailable or drops, Vyron falls back to a local M4A recording or a WAV fallback and uses `POST /api/v1/audio/transcriptions`. Realtime TTS connects to `WS /api/v1/audio/realtime/speech` and plays PCM16 `audio.delta` chunks through Android `AudioTrack`; the voice is AI-generated and disclosed in Voice Studio. `GET /api/v1/audio/capabilities` and `GET /api/v1/audio/voices` describe server availability. The old `POST /api/v1/audio/speech` endpoint is retired (HTTP 410). Provider credentials remain on the server. Cloud speech requires a valid Vibes account session, network access, and Realtime STT/TTS configuration.

Voice input uploads completed recordings and returns a final transcript; live partial transcription is not implemented. Speech output is AI-generated and currently uses the provider speech endpoint, with a 4,096-character limit per request.


## Account access and refresh tokens

Sign-in returns an HS256-signed access JWT (15 minutes) and an opaque rotating refresh token (30 days). Vyron X stores both using Android Keystore-backed encryption. The app refreshes when the access token has 60 seconds or less remaining and atomically replaces the stored refresh token on success. The server detects refresh-token reuse and revokes the token family; in that case the user must sign in again. Temporary network errors or HTTP 429/5xx do not clear the local refresh token.
