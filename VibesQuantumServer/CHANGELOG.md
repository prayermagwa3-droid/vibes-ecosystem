## 2.9.0 — Automated restore drills and fail-closed training sandbox

- Added a persisted weekly scratch restore-drill scheduler. It creates a fresh backup, restores into an isolated scratch directory, compares every restored file and digest against the archive, persists the latest report, and removes scratch data in `finally` (cleanup failures are reported). First run is immediate when no successful drill exists; a failed drill is retried after a 24-hour cooldown during the hourly due check. A shared-volume OS file lock prevents duplicate scheduled work across replicas.
- Exposed latest restore-drill status and configured interval from the admin backup status endpoint.
- Removed direct host-process training execution. The reference worker now requires a digest-pinned container image, a matching SHA-256 labelled fully pinned dependency lock, explicit memory/CPU/PID/tmpfs/wall-clock limits, no network, read-only root, dropped capabilities, non-root execution, an artifact suffix allow-list and redacted logs (including common provider key prefixes and PEM private-key blocks).
- Added authenticated worker sandbox readiness attestation; the Quantum training gateway verifies it before create, get or cancel requests. Worker API stays 503/fail-closed until all sandbox controls are present.
- Added regression tests for scratch restore verification, training fail-closed behaviour, lock-file validation, artifact allow-listing, process fallback refusal and log redaction.
- Full Kotlin/Gradle test suite still requires the project Gradle 9.6.1 distribution; worker Python tests are run and reported separately.

## 2.8.0 — Realtime voice migration and live transcription

- Replaced the legacy `/audio/speech` provider call with a server-side Realtime WebSocket synthesis flow using `gpt-realtime-2.1-mini`; the existing authenticated HTTP endpoint remains as a WAV/PCM compatibility facade.
- Added an authenticated `/api/v1/audio/transcriptions/stream` WebSocket using `gpt-live-transcribe`, 24 kHz mono PCM16 chunks, bounded session limits, partial transcript deltas, final transcript events, per-user rate limiting, and cloud-entitlement reservations.
- Kept completed-recording `/api/v1/audio/transcriptions` available while the Vyron X Android client is updated to use the live protocol.
- Added focused protocol tests and server-side Realtime configuration documentation. The Android microphone capture/client changes are not included in this server-only archive.

# Changelog

## 2.7.0 — Transactional platform, WebSocket sync and entitlements

- Added JDBC SQLite/PostgreSQL storage with checksum-verified V0001–V0003 up/down migrations, schema inspection and explicit latest-migration rollback.
- Persisted account/auth state, app registry, hashed app API keys, user installations, encrypted-vault ciphertext, conversation history, billing/entitlement state, cloud reservations/events, gateway usage, learning documents and curriculum documents in SQL when configured; retained one-time legacy JSON imports and documented remaining file stores.
- Added authenticated `/api/v1/ws/chat` with ordered message sequences, send/ack/resume, replay and per-device cursor tracking.
- Added account-owned conversation list/replay/ack endpoints and bridged authenticated legacy chat-completion requests into the same history store using `conversation_id` and `X-Vibes-Conversation-ID`.
- Added explicit provider failure classes and failover policy for timeout, 429, 5xx, auth, client, network and mid-stream conditions.
- Added Play Developer API purchase-token verification, restore, server-owned Free/Pro Lite/Pro/Pro+ tier policy and expiry checks.
- Added transactional daily per-user cloud call/spend reservations and idempotent settlement for authenticated chat, WebSocket chat, opt-in Theory of Mind, and STT/TTS.
- Extracted gateway registry stores, gateway DTOs, platform routes, workspace DTOs and workspace persistence into dedicated Kotlin files. The Android `MainActivity.kt` is not part of this server archive, and deeper route decomposition remains open.
- Added Kotlin migration, conversation, budget, provider and persistence tests. Python support suite: 73 passed, 4 skipped; SQLite migration SQL apply/rollback check passed. Gradle/Kotlin tests remain unverified here because Gradle 9.6.1 is unavailable/offline.
- Added `PLATFORM_UPGRADE_IMPLEMENTATION_REPORT.md` with rollout steps, limitations and verification status.

## 2.6.2 — Durable authentication and security rotation

- Persisted eight-hour admin console sessions as token digests, re-check the live email allowlist on every authorization, and support targeted admin-cookie revocation.
- Replaced in-memory bearer sessions with HS256 15-minute access JWTs and 30-day rotating refresh tokens. Refresh-token reuse revokes the complete session family.
- Added a lock-coordinated durable auth store for login throttles and refresh rotation, a file-backed signing key, atomic state replacement, and protected file permissions.
- Unified Google/email/phone host-based login request throttles and validate `X-Forwarded-For` only when the direct peer is an explicitly trusted proxy.
- Audited every supplied `X-Vibes-Gateway-Key` header and privileged admin action with actor, route, timestamp and outcome, without storing secrets or request credentials.
- Added `VIBES_GATEWAY_KEY_PREVIOUS` overlap support plus a tested no-downtime key-rotation path and admin email allowlist runbook.
- Added Kotlin auth unit and Ktor integration tests covering Google JWT verification, allowlisting, `email_verified`, Origin checks, persisted throttling, access/refresh lifecycle, audit redaction and staged key rotation.
- Added `SECURITY_KEY_ROTATION.md` and `AUTH_SECURITY_IMPLEMENTATION_REPORT.md` with deployment and verification requirements.

## 2.6.1 — Shared approved-knowledge sync

- Added trainer-only `POST /api/v1/knowledge/approved` publishing with app-bound scope validation, field bounds, source allowlist checks, server-side content-hash verification, idempotent publication, superseded-version audit history and atomic persistence in the existing curriculum store.
- Added `GET /api/v1/knowledge/approved/search` for approved-claim retrieval by apps holding `knowledge:read`.
- Added `POST /api/v1/knowledge/approved/withdraw` for batch revocation (up to 100 source IDs), plus `DELETE /api/v1/knowledge/approved/{sourceItemId}` for single-record revocation. Revoked records remain in the audit store and are excluded from approved retrieval.
- Added `knowledge:publish` and `knowledge:read` to the API-key scope allowlist and operator key-creation UI.
- Added regression tests for publish/retrieve/single and batch withdrawal, app identity, content hashes, source restrictions, idempotence and version superseding. Gradle execution still requires a compatible local Gradle installation.

## 2.6.0 — Phase 9 AI Council and Phase 10 task agents

- Added an optional multi-provider AI Council with Standard/Council/Deep Think/off modes, twelve selectable review roles, qualitative confidence, normalized claims, candidate disagreement detection, source de-duplication and source-provenance notes.
- Added configurable OpenAI-compatible and Cohere v2 adapters, local-first routing, cloud cost-price requirements, request budgets, deadlines, cancellation, bounded retries, concurrency and per-provider circuit breakers.
- Added approved-claim retrieval from the existing curriculum registry; pending source candidates and synthetic exercises are not used as evidence. Request-scoped evidence/context remains local-only unless Online Boost is explicitly enabled.
- Added admin review queue endpoints for disagreement, claim review and evaluation failures, with sanitized metadata and no prompt/source-body logging.
- Added six scoped agents (Research, Code, Write, Memory, Voice, Plan), typed task results, app-scoped task access, idempotency, progress/checkpoints, cancellation, audit metadata and approval gates for consequential tasks.
- Added a fail-closed, optional container sandbox for Python/Node with no network, limited resources, a read-only workspace/root filesystem and non-root execution. Build and external side-effect adapters remain unavailable until configured.
- Added dashboard panels for Council roles, evidence and provenance, provider status, agent tasks/approval/cancellation and future multimodal capability status; all provider examples are disabled by default and use no assumed live model IDs/prices.
- Added Kotlin tests for provider budgets/redaction/failure handling, candidate disagreement/source duplication, approved knowledge, task idempotency/approval/cancellation and app isolation. Full Gradle execution still requires the project-matched distribution and wrapper JAR.

## 2.5.0 — Phase 8: privacy-preserving learning and streaming

- Added app-scoped, admin-configurable learning policies with disabled-by-default collection, consent version checks, category allowlists, retention, daily quotas, idempotency and duplicate suppression.
- Added minimal feedback endpoints for ratings, task outcomes, structured error categories and explicit teaching examples; examples remain pending review.
- Added app-scoped consent, opt-out, subject export and deletion workflows. Raw subject references are not stored; tenant and subject identifiers are checked at request boundaries.
- Added an admin privacy-learning dashboard and scoped key permission for `learning:feedback`.
- Enabled OpenAI-compatible `stream: true` chat completions using bounded SSE forwarding and added a streaming playground.
- Improved reduced-motion-aware UI motion, live progress/status, keyboard focus and mobile layout.

## 2.4.3 — Phase 6 source connectors and Phase 7 curriculum lab (unreleased)

- Added manual-only, opt-in source research endpoints using a common connector interface. Wikipedia uses its public search API; Brave Search, YouTube Data API metadata and Reddit OAuth adapters require server-side credentials.
- Added HTTPS exact-host allowlists, redirects refused, bounded response streams, timeout, retry/backoff, conservative request spacing, per-run request/byte/candidate budgets and global admin request rate limiting.
- Added a bounded atomic JSON source-candidate store, URL/hash deduplication, retrieval timestamp and license/provenance fields. Every result remains `PENDING_REVIEW`; no automatic publication.
- Added the admin Source Learning dashboard for connector configuration/health, manual gap-focused runs, pending candidates and recent run budget usage. Provider keys are never returned to the browser.
- Added deterministic service and route tests. Build/test execution remains blocked in this source bundle unless a compatible Gradle installation is available because `gradle-wrapper.jar` is absent.
- Not included: user document upload, video/caption download, automatic scheduling, or connector approval actions. These remain out of scope until their auth/rights/review controls are implemented and verified.
- Added the Phase 7 Curriculum Lab with configurable curricula, prerequisite-cycle prevention, sort order and difficulty levels.
- Added an admin-only reviewed-claim registry requiring evidence references and language/domain metadata, plus deterministic synthetic question generation linked to approved claim IDs.
- Generated practice items are marked synthetic and not independent evidence, use a separate PRACTICE split, and are deduplicated across repeated generation runs.
- Added candidate accept/edit/discard, human review for Shona/Ndebele output, trainer quality ratings, and few-shot retrieval limited to accepted 4–5-rated examples.
- Added curriculum service tests for evidence boundaries, language review, deduplication, few-shot eligibility, and prerequisite cycle prevention. Full Gradle execution remains blocked in the current environment because Gradle 9.6.1 and the wrapper JAR are unavailable.

- Rate limits (registry mutations, Google sign-in) are keyed on the real client when the server sits behind a reverse proxy. `X-Forwarded-For` is honoured only from peers listed in the new `VIBES_TRUSTED_PROXIES` setting. Previously every client behind one proxy shared one bucket.
- Chat completions count each request once. Failures while sending the response no longer record a second failure, and the send is outside the upstream error handling.
- The API key list reads the store once, so the returned count matches the returned keys.
- Fixed an assertion in the AI status test that could never match pretty-printed JSON.
- Added tests for trusted-proxy address resolution.

## 2.4.1 — Shared AI inference and app integration

- Added an OpenAI-compatible text-only `POST /v1/chat/completions` endpoint and app-key-protected `GET /v1/models`. Provider credentials remain on the server.
- Added server-side model allowlisting, text/content validation, a route-scoped 65,536-byte body cap (including chunked bodies), a 4,096-token output cap, 2 MiB upstream response cap, 60-second timeout, and 20 requests/minute per app key.
- Added request IDs and in-memory, aggregate per-app usage totals without storing prompt/completion text.
- Hardened Google Sign-In by requiring same-origin POSTs from `https://vibesquantum.tech`, in addition to signature, audience, issuer and expiry validation.
- Connected Vibes Only AI's actual provider path to Quantum's `X-Vibes-API-Key` scheme and added a one-tap provider preset; the dedicated app key is stored separately in the encrypted Android vault. Quantum mode disables streaming and image payloads for compatibility with the text-only proxy.
- Updated Vibes Vyron X and Voice Studio gateway integration guidance, app-scoped key permission requirements and deployment instructions.
- Added a server regression test for oversized chat request bodies.

## 2.3.0 — Drawer console and app API credentials

- Replaced the long landing page with a responsive drawer-based gateway console and separate Overview, Connected Apps, API Explorer, API Keys, Google Account, and Settings views.
- Added Google Identity Services frontend integration, configuration messaging and session controls.
- Added per-app API key issuance, app-ID binding, narrow registration/heartbeat scopes, SHA-256 digest persistence and admin revocation. Plaintext app keys are returned only once.
- Added production domain metadata for `https://vibesquantum.tech`. DNS, hosting and TLS must be configured independently.
- Updated server documentation, environment examples, CSP allowlists and ignore rules.

## 2.2.0

- Persistent app registry, gateway health and statistics, safer request validation and server-side Google ID-token verification.


## Phase 2 — Vibes Vyron X account-session integration (2026-11-02)

- Added product-bound Google account-session authorization to `GET /v1/models` and `POST /v1/chat/completions` for the registered `vibes-vyron-x` Android installation. Such sessions receive only `ai:models` and `ai:chat`; existing app-scoped API-key authorization and Quantum trainer scopes remain separate.
- Added `GET /api/v1/me/approved-knowledge/search`, which retrieves only trainer-approved curriculum claims for the signed-in Vyron X account. Publishing and withdrawal still require Quantum's separate `knowledge:publish` app key.
- Kept the per-installation `vyronx_…` identity separate from the fixed `vibes-vyron-x` product ID so encrypted-vault records remain scoped to an individual installation.
- Previous account sessions were process-local and were invalidated on restart; the current 2.9.x auth implementation persists refresh-token/session metadata in the configured auth security store and issues short-lived JWT access tokens.
