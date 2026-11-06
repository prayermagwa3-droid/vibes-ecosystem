# Vibes Vyron X

Consumer-facing Android AI workspace for the Vibes ecosystem.

## Unified Quantum connection

Vyron X is the primary Android client for the canonical `VibesQuantumServer` Ktor engine. It stores the Render primary server ROOT URL by default (`https://vibes-quantum-api.onrender.com`); the validated HTTPS endpoint pool also includes the Cloudflare custom host, DuckDNS and the configured Vibes custom domains. All REST requests use `/api/v1/` paths. Remote hosts require HTTPS. The former standalone Quantum Android project has been removed; Quantum capabilities are server-owned and exposed through the versioned API.

Vyron X supports Quantum email/password registration and login, optional phone verification when the server's phone verifier is configured, and Google identity sign-in. The client sends the fixed product ID `vibes-vyron-x` and a locally persisted installation UUID when registering the installation. Quantum returns the account bearer session and installation identity used by the same server authorization layer.

The Android client uses that bearer session for:

- `GET /api/v1/models` — model discovery for this registered Vyron X installation.
- `POST /api/v1/chat/completions` — cloud AI chat and streaming responses.
- `GET /api/v1/me/approved-knowledge/search` — trainer-approved shared Quantum knowledge retrieval.
- The signed-in account and encrypted private-vault routes.

The server accepts the account session on account and AI routes only while it is live and associated with the registered `vibes-vyron-x` application. Existing app-scoped API keys and their scopes remain separate for narrowly authorized developer/Quantum integrations. Vyron X does not require a user to paste a shared app API key into Settings.

Native account sessions use signed 15-minute access JWTs plus 30-day rotating refresh tokens. The Android client encrypts both tokens with Android Keystore-backed storage, refreshes shortly before access-token expiry, persists the rotated refresh token, and clears the local session after confirmed revocation or refresh-token reuse. The server persists refresh-session state; see `VibesQuantumServer` deployment documentation for durable storage requirements.

## Vyron X interface

`app/src/main/assets/vyron/index.html` is the local three-column chat interface based on the supplied UI demo. `MainActivity` loads it into a restricted Android WebView and exposes a small native bridge for authenticated app functions:

- Real signed-in display name and email from the saved Vibes account profile.
- Recent chats and messages from the local `ConversationStore`.
- Streaming chat through `VibesEngineClient`.
- Code, Deep Think and Web Search modes.
- Explicit **Understand context** action: sends user-selected text to the authenticated Theory of Mind endpoint only after an in-app consent confirmation. Cloud processing requires a separate opt-in; local providers are preferred. Results are labelled as tentative hypotheses and quote only evidence present in the supplied text.
- Existing on-device web search results and their source links.
- File picking, voice recognition, Settings and private Memory screens.
- Code-block copy and ZIP export.

The UI contains no fixed sample user, sample conversation, or fabricated sources. Running the HTML file directly in a normal browser displays a non-authenticated preview; live Vibes functions are supplied by the Android app's native bridge.

## Attachment and privacy limits

The current `/api/v1/chat/completions` client sends attachment names and MIME metadata only; it does not upload file bytes. Vyron X therefore warns users that selecting a file does not mean the cloud model has read its contents. Private vault notes are encrypted on-device with AES-256-GCM before upload. The encryption key is device-bound and has no recovery/migration flow in this build. Ordinary cloud AI conversations are **not end-to-end encrypted** because cloud inference must process the prompt to generate a response.

## Main source files

- `app/src/main/java/com/vibes/only/AuthActivity.kt` — native sign-in entry point and Google Credential Manager integration.
- `app/src/main/java/com/vibes/only/VibesEngineClient.kt` — account session, chat, model discovery and approved knowledge.
- `app/src/main/java/com/vibes/only/MainActivity.kt` — WebView host, conversation bridge, attachments and voice.
- `app/src/main/java/com/vibes/only/controller/ChatController.kt` — app-facing chat controller and local fallback.
- `app/src/main/java/com/vibes/only/ui/SettingsActivity.kt` — account and gateway verification controls.
- `app/src/main/assets/vyron/index.html` — supplied-demo-inspired live app UI.

See `VIBES_QUANTUM_GATEWAY_CONNECTION.md` for the connection contract and `README_BUILD.md` for build requirements.



## Realtime Voice (Phase 5)

Vyron X now streams live microphone transcription through the authenticated Quantum WebSocket at `/api/v1/audio/realtime/transcriptions`. Android captures mono 24 kHz PCM16 in short chunks; partial transcript deltas update the composer and Voice Studio while the user is speaking. The final committed transcript replaces the partial text. If the Realtime connection cannot be established or drops, the client falls back to a local M4A/WAV recording and the existing authenticated `POST /api/v1/audio/transcriptions` endpoint.

Speech synthesis now uses the authenticated Quantum WebSocket at `/api/v1/audio/realtime/speech` with `gpt-realtime-2.1-mini`; PCM16 output deltas are played incrementally through Android `AudioTrack`. The provider API key stays on the Ktor server. The old Quantum `POST /api/v1/audio/speech` route returns HTTP 410 and no longer calls the legacy OpenAI Speech API. Spoken output is AI-generated and the app discloses that in Voice & Speech settings and Voice Studio. Realtime voice requires server-side STT/TTS credentials and a valid Vibes account session.

The OpenAI `tts-1`, `tts-1-hd`, and `gpt-4o-mini-tts` models are scheduled to shut down on January 6, 2027; the Realtime route is the supported migration path, not a replacement model ID passed to `/audio/speech`. For server configuration and protocol details, see the Quantum server's Quantum Voice section and `VIBES_QUANTUM_GATEWAY_CONNECTION.md`.

## Server-owned Quantum catalogue

After sign-in, Vyron X asynchronously retrieves `/api/v1/categories`, `/api/v1/agents`, `/api/v1/quantum/modules` and `/api/v1/quantum/migration/status` from the canonical Ktor server. The Chat Tools panel renders the category and tool descriptors returned by Quantum. The selected values are task context only; all permissions and actual execution remain server-authorized.

The older email/password and phone client flows now call the routes implemented by the server (`/api/v1/auth/email/login`, `/api/v1/auth/email/register`, `/api/v1/auth/phone/start` and `/api/v1/auth/phone/verify`). The app normalizes the server's `AccountSessionResponse` into its secure local profile and uses the same account bearer session for supported Vibes API calls. Email magic-link delivery, general OAuth, passkeys and password-reset delivery are explicitly unavailable until their Ktor services are implemented; the client does not report those flows as successful.

All connections use the configured server ROOT URL. Do not include `/api`, `/api/v1` or `/v1` in the saved host.

## Phase 3 UI refresh

The WebView interface has been rebuilt around the responsive Vyron X AI workspace. See [`PHASE3_UI_REFRESH.md`](PHASE3_UI_REFRESH.md) for the UI changes, retained bridge capabilities, asset cleanup, verification notes and features that still require native/server support.


## Endpoint failover

Vyron X uses a validated HTTPS gateway pool: Render first, Cloudflare's `api.vibesquantum.tech` host next, then DuckDNS and the `vibesquantum.tech` / `vibesquantum.com` hosts, including their `api.` aliases. Extra hosts can be supplied at APK build time with `VIBES_EXTRA_GATEWAY_URLS`. GET/HEAD calls may fail over automatically after connection or selected edge failures; write requests retry only when the connection was never established or Cloudflare reports 521–524. Configure every fallback as an alias of the same backend before enabling it, because authenticated requests can carry account bearer tokens. See the ecosystem root `PUBLIC_ENDPOINTS_AND_HOSTING.md` for DNS, deployment and build-time configuration.
