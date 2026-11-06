# Vibes Quantum Gateway 2.9.0

Kotlin 2.4.21 / JVM 21 + Ktor 3.5.2 server and live web dashboard for controlled discovery and registration of Vibes ecosystem applications.

## Highlights in 2.9.0

Implementation and verification details: `BACKUP_AND_TRAINING_SANDBOX_UPGRADE_REPORT.md`.

- Weekly automated scratch restore drill (first run immediately when no prior successful drill exists), with archive/extracted-file SHA-256 comparison, persisted report and scratch cleanup in a finally block (cleanup failures are reported).
- ALIS training worker remains fail-closed until a digest-pinned container image, hash-pinned dependency lock, enforced OS/resource limits, artifact allow-list and redacted logs are attested by the worker.

- Migrated server-side text-to-speech from the retiring `/audio/speech` models to the Realtime API using `gpt-realtime-2.1-mini`; the compatibility HTTP route returns 24 kHz mono WAV/PCM.
- Added authenticated Realtime streaming transcription with partial transcript deltas and final events over `/api/v1/audio/transcriptions/stream`.
- Retained completed-recording transcription while the Android client transitions to live PCM audio capture.

## Highlights in 2.7.0

- Added transactional SQLite/PostgreSQL persistence, checksum-verified versioned migrations and an explicit latest-migration rollback task.
- Added authenticated WebSocket chat, ordered durable conversation history, acknowledgements/replay, server-side conversation sync, Play Billing verification and subscription entitlements.
- Added transactional per-account daily cloud usage reservations and settlement for chat, Theory of Mind opt-in and audio calls.
- Added provider failure classification/fallback tests and split gateway/platform/workspace DTOs and persistence into focused modules.

## Highlights in 2.6.2

- Persisted eight-hour Google admin sessions and shared per-host login throttles in a lock-coordinated protected auth-state file.
- Added HS256 15-minute access JWTs and 30-day rotating refresh tokens with reuse detection and family-wide revocation.
- Added trusted-proxy-aware client IP resolution, complete emergency-key presentation audit events, privileged admin action audit events, and staged gateway-key/email allowlist rotation guidance.
- Added Kotlin unit and Ktor integration coverage for Google ID-token verification, allowlisting, `email_verified`, Origin/CSRF checks, rate limits, token rotation and key rotation.

## Highlights in 2.6.1

- Added trainer-scoped publishing, validated content-hash persistence, app-scoped approved-knowledge retrieval, and trainer-only withdrawal/revocation.
- Added least-privilege key scopes for `knowledge:publish` and `knowledge:read`, with dashboard controls and deployment persistence guidance.

## Highlights in 2.6.0

- Phase 6 opt-in, manual source research with official Wikipedia search and optional Brave Search / YouTube Data API metadata connectors.
- Authenticated research status, candidate inbox, and bounded manual run endpoints; API/provider keys stay server-side.
- Per-run request/byte/candidate budgets, bounded retry/backoff, exact host allowlists, HTTPS-only requests, no redirects, atomic candidate persistence and URL/content deduplication.
- Results store only short excerpts and source provenance as `PENDING_REVIEW`; new source content is never automatically promoted.
- Phase 7 Curriculum Lab supports reviewed claims, configurable curriculum paths, synthetic practice generation, trainer review and few-shot example retrieval. Synthetic questions never count as independent evidence.
- Phase 8 adds tenant-isolated, opt-in feedback policies; current-version category-specific consent; per-app retention and quotas; duplicate suppression; and per-subject export/opt-out/deletion. Learning begins disabled and teaching examples stay pending review.
- `stream: true` forwards real server-sent events from streaming-capable upstream providers; providers that return JSON are not presented as streaming. The dashboard now includes a stream playground and reduced-motion-aware UI refinements.

## Highlights in 2.4.1

- **Updated toolchain:** Kotlin 2.4.31, Ktor 3.6.0, kotlinx.serialization 1.11.0 and Logback 1.6.5.
- **Persistent registry:** registration, updates, heartbeats and removals are saved to a JSON file by default. Writes use a temporary file and atomic replacement when the filesystem supports it; failed writes roll back the in-memory change.
- **Registry controls:** registration and heartbeat require the `X-Vibes-Gateway-Key` header. Registry-changing requests are limited to 60 per client address per minute.
- **Input safety:** app IDs, display names, versions, capability lists and base URLs are validated. HTTPS is required for published app URLs; HTTP is accepted only for localhost development. URL user-info, query strings, fragments and control characters are rejected.
- **Safer web defaults:** standard browser security headers and a same-origin Content Security Policy are enabled.
- **Operational visibility:** health reports uptime, registry mode and configuration warnings; gateway info advertises features and limits; statistics show online/offline apps and capability counts.
- **More resilient dashboard:** request timeouts, protection against stale search responses, visibility-aware polling and a visible persistence/registration status.
- **App-bound API keys:** generate unique keys for individual Vibes applications; only SHA-256 digests are persisted, secrets are returned once, and active keys can be revoked.
- **Scoped app mutations:** per-app credentials can register/update their own matching app ID and send that app's heartbeat; they cannot remove other apps or administer gateway settings.
- **Drawer-based console:** responsive navigation separates Overview, Connected Apps, API Explorer, API Keys, Google Account and Settings into views instead of one long page.
- **Google Sign-In UI:** uses Google Identity Services when `GOOGLE_CLIENT_ID` is configured, with server-side ID-token verification and short-lived user sessions.
- **Canonical URL:** website metadata and the dashboard use `https://vibes-quantum-api.onrender.com` as the intended public URL.
- **Tests:** added API-key authorization, one-time-secret and persistence coverage.

## Run locally

Requirements: JDK 17 or newer and a compatible Gradle installation (the source archive does not include a Gradle wrapper JAR).

```bash
cd VibesQuantumServer
export VIBES_GATEWAY_KEY="replace-with-a-long-random-secret-at-least-32-characters"
export VIBES_REGISTRY_FILE="data/vibes-apps.json" # optional; this is the default
export VIBES_API_KEYS_FILE="data/vibes-api-keys.json" # optional; stores hashed app-key records
export VIBES_LEARNING_STORE_FILE="data/vibes-learning.json" # optional; per-app policies, consent and feedback
export GOOGLE_CLIENT_ID="your-web-client-id.apps.googleusercontent.com"
export VIBES_ADMIN_EMAILS="your-admin-account@example.com"
# export VIBES_TRUSTED_PROXIES="10.0.0.5"          # optional; only your reverse proxy IPs, for correct per-client rate limits
export PORT=8080                                   # optional
# export HOST=127.0.0.1                            # optional; defaults to 0.0.0.0

gradle test
gradle run
```

Open `http://127.0.0.1:8080/` for the public service page and `http://127.0.0.1:8080/console/` for the authenticated operator console. The default public base URL is `https://vibes-quantum-api.onrender.com`; set `VIBES_PUBLIC_BASE_URL` and `VIBES_PUBLIC_ORIGINS` to the verified public hosts you control. Project metadata does not provision DNS, hosting or TLS. The application serves on `HOST`/`PORT`, with defaults `0.0.0.0:8080`.

**Production deployment:** place the service behind a TLS-enabled reverse proxy or terminate TLS at the hosting platform. The admin console uses Google Identity Services plus an exact server-side email allowlist. Keep `VIBES_ADMIN_EMAILS` restricted to administrator accounts. `VIBES_GATEWAY_KEY` is retained only as an emergency server-side credential for privileged API requests; never put it in Vyron X, browser JavaScript or app bundles. Generate a strong random value (at least 32 characters) and rotate it if exposed.

## Quantum administrator authentication

The operator console now uses **Google Sign-In + a server-side email allowlist** as its normal authentication method. Configure all three server environment values:

- `GOOGLE_CLIENT_ID`: the Google Cloud **Web application** OAuth client ID.
- `VIBES_ADMIN_EMAILS`: comma-separated exact administrator email addresses. Only verified Google identities matching this list are admitted.
- `VIBES_GATEWAY_KEY`: a strong random emergency credential (32+ characters). It remains server-only and continues to authorize existing privileged API routes for emergency recovery; it is not used for routine browser sign-in.

Add the exact console origin you use (Render, Cloudflare custom domain, or another verified hostname) to the OAuth client's **Authorized JavaScript origins** and to `VIBES_PUBLIC_ORIGINS`, then visit `/console/` and choose **Sign in with Google**. The server verifies the Google ID token signature, issuer, audience, expiry and verified-email claim before checking the allowlist. Successful login creates an eight-hour HttpOnly, Secure-on-HTTPS, SameSite=Strict session cookie. Its SHA-256 digest and verified email are persisted in the shared auth-state store, so it survives server restarts; the live email allowlist is checked on each request. Cookie writes require the expected same-origin `Origin` header.

All existing privileged route groups accept this admin session, while app-bound `X-Vibes-API-Key` permissions and ordinary Vyron X user sessions remain separate. Emergency API recovery continues to accept `X-Vibes-Gateway-Key`. Do not delete the emergency key or place it in an APK/browser bundle.

## Render environment setup

The public `/` route is a service landing page. Open `/console/` for operator administration, app-bound key management, and endpoint diagnostics. The **API / Connections** view reports safe metadata from `/api/v1/health` and `/api/v1/providers/status` and lists the Render environment variables required for the gateway.

Set these in the Render service's **Environment** panel (not in browser fields or source files):

- `GOOGLE_CLIENT_ID`: Google Web OAuth client ID used by the admin console Google sign-in.
- `VIBES_ADMIN_EMAILS`: comma-separated exact email allowlist for Quantum administrators (lowercase recommended).
- `VIBES_GATEWAY_KEY`: unique random emergency credential of at least 32 characters; not the normal console login.
- `VIBES_UPSTREAM_API_KEY`: the provider API key used only by the server.
- `VIBES_UPSTREAM_BASE_URL`: the provider's OpenAI-compatible API base URL.
- `VIBES_UPSTREAM_MODEL`: the default model ID supported by that provider.
- `VIBES_ALLOWED_MODELS`: optional comma-separated allowlist of permitted model IDs.

For the emergency gateway credential, generate one locally with `python -c 'import secrets; print(secrets.token_urlsafe(48))'`. Do not commit the generated value. Configure `GOOGLE_CLIENT_ID` and `VIBES_ADMIN_EMAILS` in the server environment, then redeploy/restart Quantum. Add the deployed HTTPS origin to the Google OAuth client's authorized JavaScript origins. Open `/console/` and sign in with an allowlisted Google account. The console's **API Keys** section still manages scoped app keys; the emergency gateway credential remains server-only and is not entered for routine console login. App keys and provider keys are not interchangeable.

The trainer app's approved web-source queue now has a separate authenticated publishing path, documented in **Shared trainer-approved knowledge (v2.6.1)** below and in the repository-level `SHARED_KNOWLEDGE_SYNC.md`. This is distinct from the Phase 6 source-connector candidate inbox: that admin research inbox remains `PENDING_REVIEW` and is not automatically promoted or mirrored into the Android trainer queue. Do not enter gateway/provider secrets into the Android WebView training page.

## API

| Method | Path | Purpose | Access |
|---|---|---|---|
| GET | `/api/v1/health` | Health, uptime, registry mode and warnings | Public |
| GET | `/api/v1` | API index | Public |
| POST | `/api/v1/knowledge/approved` | Publish reviewed trainer web-source Q&A (max 25 per batch) | Quantum app key + `knowledge:publish` |
| POST | `/api/v1/knowledge/approved/withdraw` | Batch revoke trainer-owned records (max 100 IDs) | Quantum app key + `knowledge:publish` |
| DELETE | `/api/v1/knowledge/approved/{sourceItemId}` | Revoke one trainer-owned record | Quantum app key + `knowledge:publish` |
| GET | `/api/v1/knowledge/approved/search?q=...&limit=5` | Search approved records for chat context | App key + `knowledge:read` |
| GET | `/api/v1/me/approved-knowledge/search?q=...&limit=5` | Search trainer-approved claims for a signed-in Vyron X user | Unexpired Google session for registered `vibes-vyron-x` |
| GET | `/api/v1/gateway/info` | Feature and configuration metadata | Public |
| GET | `/api/v1/gateway/stats` | Registration totals, online/offline and capabilities | Public |
| GET | `/api/v1/apps` | Search and page through the app directory | Public |
| GET | `/api/v1/apps/{appId}` | Read one app record | Public |
| POST | `/api/v1/apps` | Register or update an app | Gateway key required |
| PUT | `/api/v1/apps/{appId}/heartbeat` | Refresh an app heartbeat | Gateway key required |
| DELETE | `/api/v1/apps/{appId}` | Remove an app | Gateway key required |
| GET | `/api/v1/keys` | List API key metadata (never plaintext keys) | Gateway key required |
| GET | `/api/v1/models` | List server-allowed AI models | App key with `ai:models` or `ai:chat` |
| POST | `/api/v1/chat/completions` | OpenAI-compatible text completion proxy | App key with `ai:chat`, or an unexpired session from a registered Vibes Vyron X installation |
| GET | `/api/v1/theory-of-mind/capabilities` | Read opt-in text analysis capabilities | Signed-in Vyron X user session |
| POST | `/api/v1/theory-of-mind/analyze` | Generate evidence-grounded, tentative emotion/intent hypotheses | Signed-in Vyron X session + explicit request consent |
| GET | `/api/v1/providers/status` | Safe inference configuration metadata | Public |
| GET | `/api/v1/gateway/usage` | Aggregate per-app AI usage totals | Gateway key required |
| POST | `/api/v1/keys` | Issue one app-bound API key; plaintext is shown once | Gateway key required |
| GET/PUT | `/api/v1/learning/apps/{appId}/policy` | Read or configure tenant learning policy | Gateway key required |
| GET | `/api/v1/learning/apps/{appId}/summary` | Per-app counts and quota status | Gateway key required |
| GET | `/api/v1/learning/apps/{appId}/feedback` | View retained feedback; subject hashes are redacted | Gateway key required |
| POST | `/api/v1/learning/consent` | App-side consent/opt-out; `learning:feedback` scope | App key bound to `appId` |
| POST | `/api/v1/learning/feedback` | Submit one permitted minimal signal | App key + current category-specific consent |
| POST | `/api/v1/learning/subjects/export` | Export retained subject-scoped data for that app | App key bound to `appId` |
| POST | `/api/v1/learning/subjects/delete` | Delete subject consent and signal records for that app | App key bound to `appId` |
| DELETE | `/api/v1/keys/{keyId}` | Revoke an app-bound API key | Gateway key required |

### Register an app

```bash
curl -X POST http://127.0.0.1:8080/api/v1/apps \
  -H 'Content-Type: application/json' \
  -H 'X-Vibes-Gateway-Key: YOUR_SERVER_SECRET' \
  -d '{"appId":"vibes-notes","displayName":"Vibes Notes","baseUrl":"https://notes.example.com","capabilities":["notes","search"],"version":"1.0.0"}'
```

### Refresh a heartbeat

```bash
curl -X PUT http://127.0.0.1:8080/api/v1/apps/vibes-notes/heartbeat \
  -H 'X-Vibes-Gateway-Key: YOUR_SERVER_SECRET'
```

### Issue an app-specific API key

Sign in to the web console with an allowlisted Google account, then unlock **API Keys** using the authenticated admin session. Create a unique key name and bound `appId`, and select the required scopes. The raw app key is returned only once; persist it securely in the app's Android Keystore-backed secret storage or a server environment and use `X-Vibes-API-Key` over HTTPS. The server persists only a SHA-256 digest in `VIBES_API_KEYS_FILE`.

Keys can be issued with `apps:register` and/or `apps:heartbeat` scopes. They are app-ID bound: a key issued for `vibes-vyron-x` cannot register or heartbeat a different app ID. An allowlisted Google admin session is the normal requirement to issue/revoke keys or delete app records; the emergency gateway key remains accepted for recovery.

```bash
curl -X POST https://vibes-quantum-api.onrender.com/api/v1/apps \
  -H 'Content-Type: application/json' \
  -H 'X-Vibes-API-Key: YOUR_APP_SPECIFIC_KEY' \
  -d '{"appId":"vibes-vyron-x","displayName":"Vibes Vyron X","baseUrl":"https://your-app.example","capabilities":["assistant","voice"],"version":"1.0.0"}'
```

### Discover and filter apps

`GET /api/v1/apps` accepts:

| Query | Purpose | Example |
|---|---|---|
| `q` | Search app ID, name, URL, version and capabilities | `?q=notes` |
| `capability` | Exact, case-insensitive capability filter | `?capability=search` |
| `status` | Filter by `online` or `offline` | `?status=online` |
| `limit` | Page size, maximum 100 (default 50) | `?limit=20` |
| `offset` | Number of matching records to skip, from 0 to 1,000,000 | `?offset=20` |

The response includes `count` for the current page, `total` matching records, `limit`, `offset`, `filters` and `apps`. Invalid status or pagination values return HTTP 400.

## Registry storage and recovery

- `VIBES_REGISTRY_FILE`, `VIBES_API_KEYS_FILE`, `VIBES_USER_APP_REGISTRY_FILE`, and `VIBES_ENCRYPTED_VAULT_FILE` are legacy import sources. With `VIBES_DATABASE_URL` configured, app registry, hashed app API keys, user-installation registry, and opaque encrypted-vault records are stored in the SQL `document_store` after one-time import. Without a database URL they retain their legacy file behavior.
- Database-backed stores include accounts, auth sessions/refresh history/login failures, app registry and app keys, user installations, encrypted vault, conversations, entitlement and purchase state, cloud spend reservations/usage, gateway usage, learning documents, and curriculum/approved-knowledge documents.
- Keep legacy files as recovery copies until a database backup has been tested. The JSON-document stores are transactionally persisted but currently cached in process memory between writes; use a single server process for SQLite, and do not horizontally scale these cached stores until they are moved to normalized SQL operations with cross-instance cache invalidation.

## Security and implementation notes

- Login throttles are durable in the SQL database when `VIBES_DATABASE_URL` is configured; otherwise legacy file persistence is used. Admin sign-in failures are counted per resolved client address. `X-Forwarded-For` is ignored unless the direct peer IP is explicitly listed in `VIBES_TRUSTED_PROXIES`.
- Browser security headers include `X-Content-Type-Options`, `X-Frame-Options`, a referrer policy, a restrictive Content Security Policy and a permissions policy.
- Public discovery exposes registered app metadata. Do not put secrets or private internal URLs into public registration fields.
- The registry key is a server-side administrator credential; never expose it in a web page, mobile bundle or public repository.
- App-specific API keys are stored as SHA-256 digests, shown once, bound to an app ID and revocable. Keep each raw key in Android Keystore-backed storage or a server-side secret manager.
- Google Sign-In for ordinary Vyron X users does not grant admin access. Quantum admin access verifies the Google ID token signature and claims on the server, requires `email_verified=true`, and requires an exact match in `VIBES_ADMIN_EMAILS`. The admin cookie is HttpOnly, Secure on HTTPS, SameSite=Strict, persists across restarts, and expires after eight hours. Cookie-write endpoints require an exact same-origin `Origin`; admin authorization also re-checks the live allowlist, so removing an email disables existing cookies on each updated instance. `VIBES_GATEWAY_KEY` remains an emergency-only server credential accepted by privileged API routes; every supplied emergency-key header and every privileged authorization decision is recorded without logging the secret. A registered Vyron X account session receives no admin, workspace, trainer-publish, or other app-key permissions.
- User sessions use HS256 signed access JWTs valid for 15 minutes and opaque 30-day refresh tokens. Refresh tokens rotate on each use; reuse of a consumed token revokes the full session family. With the SQL database configured, account/auth state is transactionally persisted in `document_store`; otherwise the compatibility file store is used. All instances must share the PostgreSQL database and JWT signing key. See [`SECURITY_KEY_ROTATION.md`](SECURITY_KEY_ROTATION.md) for key rotation, proxy trust, and audit procedures.
- The gateway provides an optional, scoped text inference proxy through `POST /api/v1/chat/completions`; existing app clients may use an app-bound `ai:chat` key, while the official Vibes Vyron X Android client may use its unexpired, Google-verified account session after registering the fixed `vibes-vyron-x` product ID. Phase 8 supports real upstream SSE streaming when the configured provider returns `text/event-stream`; tool calls and multimodal content are still not forwarded.
- Remaining file-backed services include source-candidate state, workspace job artifacts/brain files, training-job records, the security audit JSONL trail, the council review queue, and backup catalog. Keep their persistent paths protected and backed up; migrate these in a later storage pass before treating every service as database-only.

## Theory of Mind (text-grounded, opt-in)

Vyron X exposes `GET /api/v1/theory-of-mind/capabilities` and `POST /api/v1/theory-of-mind/analyze` for explicitly user-requested analysis of text the user supplies. The endpoint requires a registered Vyron X user session and `consentToAnalyze: true`; cloud providers are excluded unless `allowCloudProcessing: true` is separately set. It prefers local providers, limits input and output sizes, does not persist submitted content, and removes any emotion or intention hypothesis whose evidence quote is not an exact substring of the submitted text. Outputs are hypotheses, never verified facts or diagnoses. Raw audio and facial-emotion analysis are not supported by this capability. Evaluation metrics and a small test harness live in `tom-evaluation/`.

## Platform persistence, chat sync, subscriptions and budgets (v2.7.0)

`VibesDatabase` uses JDBC transactions and checksum-verified, versioned up/down SQL migrations in `src/main/resources/db/migration/`. SQLite is the default for a single Termux/server process; PostgreSQL is supported for shared multi-instance deployments. Configure `VIBES_DATABASE_URL` (and credentials for PostgreSQL). Run `./gradlew databaseStatus` to inspect versions and `./gradlew rollbackDatabase` to roll back exactly the latest migration. Rollback is operator-controlled and potentially destructive: stop writers and take a verified backup first. Current migrations are V0001–V0003; rollback is not automatic on startup.

The database stores account records, auth sessions/refresh rotation history/login throttles, app registry and hashed app API keys, user-app installations, encrypted-vault ciphertext, conversation messages and device acknowledgements, subscription/purchase state, cloud spend reservations/events, gateway usage, and learning/curriculum documents. These JSON document stores import their previous JSON file on first startup when the database key is absent. Source-candidate state, workspace job artifacts/brain files, training-job records, security audit JSONL, council review queue, and backup catalog remain file-backed; keep those persistent paths/backups configured. Several registry documents are still process-cached, so the current supported deployment is one server process (SQLite for Termux); PostgreSQL does not yet make every cache safe for horizontal scaling.

Authenticated account chat can use `wss://<host>/api/v1/ws/chat`. The first server envelope is `ready`; clients send JSON envelopes with `type=send`, `ack`, or `resume`. Server messages include monotonically allocated conversation `sequence` values, `message` records, `delta` chunks and `message.done`. `resume` replays messages after `lastReceivedSequence` (up to 500 per batch; repeat from the last returned sequence when `hasMore=true`). Acknowledgements are scoped to user + device + conversation and cannot move backwards. The complete assistant message is persisted before delta frames are emitted, so reconnect replay recovers it. Important limitation: the current ProviderRouter returns buffered completions; these WebSocket deltas are transport chunks, not upstream token-by-token model streaming. The legacy SSE completion endpoint is still available. For authenticated Vyron X account requests it also accepts `conversation_id` in the request body (or creates one), returns `X-Vibes-Conversation-ID`, and syncs user/assistant text to the same conversation repository; API-key-only app requests are not associated with a personal account history.

Conversation sync endpoints are `GET /api/v1/conversations`, `GET /api/v1/conversations/{id}/messages?after=<sequence>`, and `POST /api/v1/conversations/{id}/ack`. They require a valid VYRON X account bearer session and enforce per-user ownership.

Play subscription routes are `GET /api/v1/billing/entitlement`, `POST /api/v1/billing/verify`, and `POST /api/v1/billing/restore`. Configure `VIBES_PLAY_PACKAGE_NAME`, `VIBES_PLAY_SERVICE_ACCOUNT_JSON`, and `VIBES_PLAY_PRODUCT_TIERS` with real Play Console product IDs mapped to `PRO_LITE`, `PRO`, or `PRO_PLUS`. The backend verifies purchase tokens with the Google Play Developer API; client-reported tier/expiry values are not trusted. Expiry is checked server-side and expired subscriptions fall back to `FREE`. Renewals/changes are refreshed when the app verifies or restores the purchase; Real-time Developer Notifications are not implemented in this phase, so configure the Android client to verify/restore on resume and after purchase. Billing needs a linked Play Console service account and a live Play test purchase to verify end-to-end.

Default account limits are Free 10 cloud calls / $0.10 per UTC day, Pro Lite 100 / $1, Pro 500 / $5, and Pro+ 2,000 / $20. Configure the matching `VIBES_*_DAILY_*` variables to change them. Reservation and settlement are transactional and settlement is idempotent. They are enforced on authenticated VYRON X chat, WebSocket chat, Theory of Mind cloud opt-in, and STT/TTS routes. App-key-only council, agent and workspace operations do not have an end-user account identity and remain governed by provider/request budgets, not a per-user subscription quota; do not present them as per-user billed calls.

ProviderRouter now classifies timeouts, 429, 5xx, authentication, client and network failures. It tries another configured provider for transient/rate-limit/server/auth/network failures before any response is committed; client errors stop fallback. Authentication failures temporarily suppress that provider. The provider policy tests cover timeout, 429, 5xx, auth, client and mid-stream no-fallback rules. The existing buffered Router does not itself stream, while the direct SSE adapter remains pinned to its configured upstream; no failover is attempted after SSE bytes have started.

The large server source was partially decomposed: gateway DTOs, gateway registry stores, platform routes, workspace DTOs and workspace persistence live in separate files. `Routing.kt` still contains a large route-wiring/orchestration surface. This server archive does not contain Android `MainActivity.kt`, so that Android file cannot be split here; it must be handled in the VYRON X Android project archive.

## Verification status

Kotlin tests were added for migration apply/rollback/re-apply, document-store persistence across database instances, conversation sequence/idempotency/ack behavior, cloud spend caps/exactly-once settlement, provider fallback classification, and legacy learning/curriculum import plus user-installation/vault document persistence. The full Gradle suite has **not** been executed in this environment: the wrapper requires Gradle 9.6.1, which is unavailable/offline here. Source presence is not a passing test result. Run `./gradlew test --no-daemon` on JDK 21 with Gradle 9.6.1 and resolve any platform-specific JDBC/WebSocket issues before production rollout. A staging Play Billing purchase and a multi-instance PostgreSQL concurrency test are also required before enabling paid subscriptions or horizontal scaling.

## Build and test

```bash
gradle test
gradle run
```

This source archive does not include the Gradle wrapper JAR. Build/test execution must be performed in an environment with Gradle and dependency access configured.

## User token lifecycle

Google, email, and phone sign-in responses include a 15-minute signed JWT `accessToken` and a 30-day opaque `refreshToken`. Use `Authorization: Bearer <accessToken>` for authenticated requests. Renew with `POST /api/v1/auth/refresh` and JSON `{"refreshToken":"..."}`; persist the returned access and refresh tokens together. A refresh token is single-use. Replaying an already-consumed token is treated as theft/reuse and revokes that session family. `POST /api/v1/auth/logout` revokes the current session; `POST /api/v1/auth/logout-all` revokes every session for the current account.

See [`SECURITY_KEY_ROTATION.md`](SECURITY_KEY_ROTATION.md) for the persistent storage requirements, cross-instance setup, trusted-proxy configuration, audit review, and staged no-downtime key/allowlist rotation.

## Google Sign-In setup

Create an OAuth **Web application** client in Google Cloud. Add the Render origin and each verified browser domain you will use to its authorized JavaScript origins and to the server's `VIBES_PUBLIC_ORIGINS`; set `VIBES_PUBLIC_BASE_URL=https://vibes-quantum-api.onrender.com` and `GOOGLE_CLIENT_ID` on the Ktor server. The website only receives the client ID; the server verifies the returned Google ID token. An OAuth client ID alone does not make the public domain reachable; DNS, a reachable host and HTTPS must be configured separately.


## Shared AI inference proxy (2.4.1)

Configure these on the gateway host only; do not place an upstream key in browser code or an APK:

```sh
export VIBES_UPSTREAM_BASE_URL='https://api.openai.com/v1'
export VIBES_UPSTREAM_API_KEY='your-provider-key'
export VIBES_UPSTREAM_MODEL='YOUR_VALIDATED_MODEL_ID'
export VIBES_ALLOWED_MODELS='YOUR_VALIDATED_MODEL_ID'
```

The base URL must use HTTPS, without URL credentials, query parameters, or fragments. `VIBES_ALLOWED_MODELS` is a comma-separated allowlist. The configured default model is included automatically. The gateway accepts OpenAI-compatible plain-text chat requests only; `stream: true` is forwarded as server-sent events when the upstream returns `text/event-stream`, while tool/function calling and multimodal message content are not forwarded. Requests have a 65,536-byte JSON body limit, 60,000 total prompt-character limit, 4,096 max output-token limit, 2 MiB response/stream limit, 60-second upstream connect/response-header timeout, and 20 chat requests/minute per key. These are resource controls, not a billing guarantee: apply provider-side spending limits too.

For independent integrations, issue an app-bound key with `ai:chat`; add `ai:models` if you want `GET /api/v1/models` without the chat scope. Existing keys remain valid for their original scopes and need to be re-issued to add new scopes. Keys may be sent in `X-Vibes-API-Key` or `Authorization: Bearer`. The official Vyron X Android client can instead sign in with Google through `/api/v1/auth/google`; its registered product session is restricted to `ai:chat` and `ai:models` on the AI routes and does not grant trainer, admin, or developer permissions. `GET /api/v1/providers/status` reveals only safe configuration metadata. `GET /api/v1/gateway/usage` requires `X-Vibes-Gateway-Key` and reports in-memory counts by app; prompts, completions and provider secrets are never recorded.

### Chat example

```sh
curl https://vibes-quantum-api.onrender.com/api/v1/chat/completions \
  -H 'Content-Type: application/json' \
  -H 'X-Vibes-API-Key: vq_live_YOUR_APP_KEY' \
  -d '{"messages":[{"role":"user","content":"Say hello from Vibes Quantum."}]}'
```

Google Sign-In verifies same-origin browser sign-in posts from the explicit `VIBES_PUBLIC_ORIGINS` allowlist. Add each browser host to both Google authorized JavaScript origins and `VIBES_PUBLIC_ORIGINS`. Google Sign-In and app registry features still require their own configuration. Domain metadata does not configure DNS or TLS.


## Shared AI inference gateway (v2.4.1)

The default public gateway origin is Render at `https://vibes-quantum-api.onrender.com`. Cloudflare/custom-domain and DuckDNS fallback origins must also have DNS and HTTPS configured and be listed in `VIBES_PUBLIC_ORIGINS` before browser administration uses them.

The gateway provides an OpenAI-compatible, text-only `POST /api/v1/chat/completions` endpoint and `GET /api/v1/models`. Independent Vibes apps use their own app-bound API keys with `ai:chat` and optionally `ai:models`; the official Vyron X Android app can use its own unexpired Google-verified account session, restricted to the `vibes-vyron-x` product. Include `apps:register` and `apps:heartbeat` too if the app itself registers and sends heartbeats. Create keys from the dashboard's API Keys drawer, and never paste the gateway administrator secret or upstream provider key into an Android app.

Set these values only in the server environment (not website JavaScript, HTML, or Android resources):

```bash
export VIBES_GATEWAY_KEY='use-a-long-random-admin-secret'
export VIBES_UPSTREAM_BASE_URL='https://api.openai.com/v1'
export VIBES_UPSTREAM_API_KEY='your-provider-key'
export VIBES_UPSTREAM_MODEL='YOUR_VALIDATED_MODEL_ID'
export VIBES_ALLOWED_MODELS='YOUR_VALIDATED_MODEL_ID'
export VIBES_API_KEYS_FILE='./data/vibes-api-keys.json'
export VIBES_REGISTRY_FILE='./data/vibes-apps.json'
```

The proxy enforces a route-level request-body limit, validates the model against an allowlist, forwards genuine SSE streaming only when the upstream supports it, bounds the number and size of text messages, maximum output tokens and response bytes, rate-limits each key, returns request IDs, and records only aggregate per-app usage counts. Configure provider spending limits at the upstream account too.

### Vibes Only AI connection

In Brain / API or Secret Vault settings, tap **Use Vibes Quantum shared AI**, enter `default` or one of the model IDs configured on the server, then save an app-scoped `vq_live_…` key created for the intended app. The client uses `X-Vibes-API-Key` and stores this key in its dedicated encrypted local vault, separate from generic provider keys. The proxy accepts plain-text messages and supports non-streaming JSON or real server-sent events (`stream: true`) when the configured provider supports SSE.

### Google Sign-In

Set `GOOGLE_CLIENT_ID` on the server to the Web OAuth client ID and allow `https://vibes-quantum-api.onrender.com` as an authorized JavaScript origin in Google Cloud. The domain must be deployed over HTTPS. Google sign-in is for user identity; app-scoped API keys are separate machine credentials.


## Phase 1 readiness and secret configuration

Use `GET /api/v1/health` for liveness and `GET /api/v1/ready` for readiness. The readiness route returns HTTP 503 until a non-placeholder emergency `VIBES_GATEWAY_KEY` of at least 32 characters is configured. Generate a unique value with `openssl rand -hex 32`; keep the emergency credential and `VIBES_UPSTREAM_API_KEY` only in the server environment. `VIBES_REGISTRY_FILE` and `VIBES_API_KEYS_FILE` are server-side persistence locations. Readiness reports whether inference is configured as a boolean but never returns the upstream secret. Placeholder OAuth/provider values are treated as unconfigured, and JSON bodies on chat, app registration, API-key creation, and Google sign-in are capped at 65,536 bytes.


## Phase 6 — source connectors and responsible web learning

Source learning is deliberately **off by default** and has no automatic scheduler. First enable it in the server environment with `VIBES_SOURCE_CONNECTORS_ENABLED=true`. A manual run must contain a focused 3–300 character question and one of `MISSING_EVIDENCE`, `LOW_CONFIDENCE`, or `CONTRADICTION`. It then queries only selected, configured connectors. Every connector call has an HTTPS host allowlist, timeout, bounded retry/backoff and a conservative inter-request delay. The server applies a shared admin mutation rate limit plus per-run request, downloaded-byte and candidate ceilings.

### Supported connectors

- **Wikipedia API** — built in when the global feature flag is enabled. Stores a short API excerpt and article URL with licensing/attribution notes; verify the article-specific license obligations before reuse.
- **Brave Search API** — opt in by setting `VIBES_BRAVE_SEARCH_API_KEY`. Stores only title, source URL and a short search snippet. This requires a suitable Brave plan/account and is not a free-tier guarantee.
- **YouTube Data API** — opt in by setting `VIBES_YOUTUBE_API_KEY`. Stores search metadata (title, channel, description excerpt and video URL) only. It does not download videos or full transcripts and does not treat metadata as evidence for the video's claims.
- **Reddit public discussions** — opt in by setting `VIBES_REDDIT_CLIENT_ID` and `VIBES_REDDIT_CLIENT_SECRET` for an approved Reddit OAuth application. Results are explicitly typed as public discussion/opinion, store only short excerpts and must not be considered factual corroboration by themselves. OAuth token and search requests count against the per-run budget.

Provider keys are read only by the server. They are not returned by the connector health API and must never be copied into the browser, Android APK, or source control. User-document upload, captions/transcripts, video downloads, broad crawling and scheduled jobs are **not enabled in this release**; do not assume those connectors exist. A scheduled cadence should only be considered after operational quotas, persistence and review flows have been verified in deployment.

### Endpoints

All `/api/v1/research/*` endpoints require `X-Vibes-Gateway-Key` and are protected by the existing server-side admin-key validation and mutation limiter:

- `GET /api/v1/research/connectors` — feature flag, connector configuration/status, actual run counters and configured budgets. `configured_not_yet_checked` is not a live provider-health guarantee.
- `POST /api/v1/research/runs` — manual evidence-gap run. Example body:

```json
{
  "query": "What reliable evidence explains yellowing in maize leaves?",
  "reasonForResearch": "MISSING_EVIDENCE",
  "connectorIds": ["wikipedia"]
}
```

- `GET /api/v1/research/runs` — recent saved run summaries.
- `GET /api/v1/research/candidates?limit=25&offset=0` — paginated pending candidates for the review inbox.

Each candidate records connector, title, source URL, short excerpt, publisher, license/usage notes, retrieval timestamp, query, reason and content SHA-256. These source snippets are untrusted input, never executable instructions. Candidates are deduplicated against stored source URLs and normalized title/excerpt hashes and remain `PENDING_REVIEW`; this server does not provide an approval endpoint or silently publish them to global knowledge. The queue is an evidence-only staging inbox in the gateway dashboard; this release does not automatically sync records to the Android app's local Feedback Panel. A reviewer must manually use the cited source in the existing review workflow until a separate, authenticated synchronization contract is implemented. The store holds at most 10,000 candidates and 1,000 run summaries; it refuses new candidate commits at the cap instead of silently deleting pending evidence.

### Budgets and storage

- `VIBES_SOURCE_MAX_REQUESTS_PER_RUN=8` counts retry attempts as requests.
- `VIBES_SOURCE_MAX_BYTES_PER_RUN=524288` tracks response bytes read by the connector client.
- `VIBES_SOURCE_MAX_CANDIDATES_PER_RUN=15` caps source candidates per run.
- `VIBES_SOURCE_STORE_FILE=data/vibes-source-learning.json` selects an atomic-replacement JSON store. The file is ignored by Git, and its permissions are restricted to the current OS user where POSIX permissions are supported.

Allowed configuration ranges are validated at startup. The store is bounded to 10,000 candidates and 1,000 run records. No provider cost is guessed from requests or bytes; consult the provider's billing console. Configure filesystem backups and retention for your deployment.

### Verification

Run `gradle test` from `VibesQuantumServer` in an environment with Gradle 9.6.1-compatible distribution access and JDK 21. The source bundle does not include `gradle-wrapper.jar`. Phase 6 adds deterministic tests for disabled mode, required evidence-gap reasons, review staging, deduplication, budget counts and admin authorization. Do not label this feature production-ready until those tests and a real provider smoke test are run in your target environment.

## Phase 7 — Curriculum Lab and synthetic practice

Phase 7 adds an admin-key-protected curriculum API and responsive Curriculum Lab dashboard. It supports configurable learning paths (domain, objectives, difficulty, prerequisites, enabled state and sort order), an explicitly trainer-reviewed claim registry with evidence references, synthetic practice question generation, review/edit/discard, quality ratings, and retrieval of accepted high-quality few-shot examples.

The separate JSON store is configured with `VIBES_CURRICULUM_STORE_FILE` (default `data/vibes-curriculum.json`). Back up this file before deployment. The store is bounded, writes via atomic replacement, and fails visibly on malformed or unsupported schema rather than silently resetting data.

### Curriculum API

All endpoints require the server admin header `X-Vibes-Gateway-Key` and are rate-limited:

- `GET /api/v1/curriculum` — curricula and question review snapshot.
- `GET /api/v1/curriculum/claims?limit=50&offset=0` — approved claim registry, paginated.
- `GET /api/v1/curriculum/examples?domain=agriculture&limit=12` — accepted questions rated 4–5, grouped to return up to three per question type.
- `POST /api/v1/curriculum/curricula` and `PUT /api/v1/curriculum/curricula/{id}` — create/update curricula.
- `POST /api/v1/curriculum/claims` — register a claim only when the authorized trainer supplies evidence references and review attribution.
- `POST /api/v1/curriculum/generate` — generate practice questions from approved claims in the selected domain.
- `POST /api/v1/curriculum/questions/{id}/review` — `ACCEPT`, `EDIT`, or `DISCARD` a candidate.

Generation uses deterministic templates and does not call an LLM. Question records preserve their source claim and evidence references and are always marked `synthetic=true`, `independentEvidence=false`, and `split=PRACTICE`. Repeated generation is deduplicated. Shona and Ndebele questions require a human review note before acceptance; a 1–5 trainer rating is required, and only accepted examples rated 4–5 are eligible for few-shot retrieval. This rating is a quality signal, not proof that a factual claim is true.

### Phase 7 verification

The bundled Phase 6 Python research-service tests and evaluation-lab tests are run separately. The Kotlin/Gradle build must still be run in an environment with Gradle 9.6.1 and dependencies available; do not treat the lightweight Kotlin syntax check as a substitute for a full build or test suite.


## Phase 8 — privacy-preserving learning and streaming (v2.5.0)

- Cross-app feedback is disabled per app until an administrator configures `/api/v1/learning/apps/{appId}/policy`.
- App clients need an app-bound key with the `learning:feedback` scope and must obtain separate, informed end-user/admin consent before posting any signal. The server validates app ID, current policy version, category-specific consent, retention, quota, and duplicate protection.
- Acceptable signals are thumbs up/down, task outcome, a short structured error category, and an explicitly submitted teaching example. Free-form conversation history, contacts, personal histories and voice recordings are not accepted by these endpoints. Teaching examples remain `PENDING_REVIEW`; feedback cannot write global claims.
- `POST /api/v1/learning/subjects/export` exports the currently retained subject-scoped records; `POST /api/v1/learning/subjects/delete` removes that subject's consent record and feedback for that app; posting `granted: false` to `/api/v1/learning/consent` opts out and deletes that subject's retained feedback. The raw subject reference is hashed with the tenant ID and is not persisted.
- Configure `VIBES_LEARNING_STORE_FILE` (default `data/vibes-learning.json`) on persistent writable storage, outside web roots, and protect backups. This file-backed store is not intended for concurrent multi-writer horizontal scaling.
- `POST /api/v1/chat/completions` supports `stream: true` using upstream server-sent events (SSE), with request validation, credential-scope checks (including product-bound Vyron X user sessions), rate limiting and a bounded stream response. `GET /api/v1/providers/status` reports streaming support. Providers that do not honour SSE are not transformed into simulated streams.
- Stream Playground in the console uses the current origin and an app key. The prompt is forwarded to the configured inference provider; never paste secrets or sensitive personal data there.

Consent is asserted by the integrating app after it shows its own consent UI; the gateway does not independently verify a human's identity or a screen's wording. Apps must not treat the key itself as user consent. Ordinary centralized server-side feedback is not federated learning.

## Phase 9 — AI Council, provider routing and synthesis (v2.6.0)

Phase 9 adds an optional council endpoint alongside the existing `/api/v1/chat/completions` API. The council is not a claim that multiple LLMs constitute ground truth: it compares outputs, normalizes candidate propositions, tracks source IDs/URLs, detects configured review-verdict conflicts, reports shared-source dependence, and uses a qualitative confidence label. Its fallback answer remains transparent when providers are missing, timed out, cancelled, or over budget. Private chain-of-thought and provider prompts are never exposed in the response.

### Provider configuration

1. Copy `config/ai-providers.example.json` to `config/ai-providers.json` (the real file is ignored by Git).
2. Keep each adapter disabled until you have selected a provider, checked its current API documentation, chosen a currently supported model ID, and verified its request/response compatibility.
3. Set model names through the documented `modelEnv` or `model` field. Configure maximum input/output tokens, timeout, capabilities and **both** `inputCostPer1kUsd` and `outputCostPer1kUsd` using current prices. An unpriced cloud provider is ineligible for budgeted council/agent work. Local providers can omit prices and are estimated at zero provider cost; hardware and electricity still cost money.
4. Supply API keys through server environment variables named by `apiKeyEnv`. Do not put keys in JSON, the browser, Android code, logs or source control. Set `VIBES_AI_PROVIDERS_FILE` to the server-side configuration path.

The example lists optional adapters for an OpenAI-compatible local Ollama endpoint, Google's OpenAI-compatible Gemini endpoint, OpenRouter-compatible APIs, and Cohere's native v2 chat endpoint. These are optional and not required together:

- Google Gemini compatibility: <https://ai.google.dev/gemini-api/docs/openai>
- Ollama OpenAI compatibility: <https://ollama.com/blog/openai-compatibility>
- OpenRouter API overview: <https://openrouter.ai/docs/api-reference/overview>
- Cohere v2 Chat API: <https://docs.cohere.com/v2/reference/chat>

Never assume a listed model, free tier, quota or price remains available. Validate the provider's current docs before enabling it. Supported protocols in this release are `openai-compatible` and `cohere-v2`; unknown protocols are rejected. Circuit breakers, bounded concurrency, deadlines, request cancellation, bounded retry and graceful provider fallback are implemented. Reranking is attempted only if at least one configured, credentialed, budget-eligible adapter explicitly advertises `rerank` capability; the configured provider is still responsible for ensuring that capability reflects what its model/service actually supports.

The legacy streaming/text gateway still needs `VIBES_UPSTREAM_BASE_URL`, `VIBES_UPSTREAM_API_KEY`, and current model IDs in `VIBES_UPSTREAM_MODEL` / `VIBES_ALLOWED_MODELS`. The `VIBES_UPSTREAM_INPUT_COST_PER_1K_USD` and `VIBES_UPSTREAM_OUTPUT_COST_PER_1K_USD` values enable the legacy adapter to participate in budgeted council/agent calls. The existing chat gateway's billing is not hard-capped by this estimate; configure provider-side account budgets too.

### Council modes and roles

- **Standard**: one routed answer, with a transparent limited fallback when no provider is eligible.
- **Council**: up to two candidate answers from distinct configured providers, selected review roles, source/provenance checks and final synthesis, bounded by `maxMembers`, deadlines and `maxCostUsd`.
- **Deep Think**: up to three distinct-provider candidates and up to all twelve review roles under a longer server deadline and the same caller budget controls. It is not a guarantee of correctness.
- **Off**: disable council reviews and use the Standard single-answer path.

The twelve roles are Logic, Evidence/Truth, Critic, Historian, Risk, Empathy, Strategy, Creative, Future, Domain Expert, Clarity and Vision. Role selection is returned by `GET /api/v1/council/roles`. Creative output must be treated as ideas; Future output as forecasts; Evidence/Truth findings are model reviews rather than external source authentication. Candidate provider IDs supplied by a caller are not trusted as proof of independent providers. Normalized candidate propositions can report lexical alignments and possible polarity/direction conflicts for review; they are heuristics, not factual proof. URL/content duplicates and candidate evidence-ID overlap are flagged.

Privacy rule: supplied evidence, candidate answers, prior context and retrieved local claims are treated as untrusted input; unrelated global memory is never automatically attached. When any request-scoped material exists, the service uses configured local providers by default. Cloud providers are eligible with that material only when `onlineBoost=true`; with no material at all, a configured provider may be used because local evidence is insufficient. Credential-like text is redacted, and reviewer identity is kept out of model context, but users should still avoid secrets and unnecessary personal data.

### Phase 9 endpoints

| Method | Path | Access | Purpose |
|---|---|---|---|
| GET | `/api/v1/council/roles` | Public | Discover the twelve roles and defaults |
| GET | `/api/v1/council/providers` | Allowlisted Google admin session or emergency gateway key | Inspect configured provider eligibility, capability and pricing status; never returns credentials |
| POST | `/api/v1/council/completions` | App key with `ai:chat` | Standard, Council or Deep Think answer |
| GET | `/api/v1/council/review-queue` | Google admin session or emergency gateway key | Review conflicts, evidence gaps and evaluation failures |
| POST | `/api/v1/council/review-queue/{reviewId}/review` | Google admin session or emergency gateway key | Record a reviewed/dismissed resolution |

Example request:

```json
{
  "prompt": "Compare these two claims and say what the evidence supports.",
  "mode": "COUNCIL",
  "enabled": true,
  "evidence": [
    {"id":"S1", "title":"Report", "url":"https://example.org/report", "text":"Paste a permitted short excerpt here."}
  ],
  "candidates": [],
  "priorContext": [],
  "selectedMembers": ["logic", "evidence", "critic", "risk"],
  "maxMembers": 4,
  "maxCostUsd": 0.05,
  "onlineBoost": false,
  "includeMemberSummaries": true
}
```

`estimatedCostUsd` is an estimate, not a provider invoice. Confidence is qualitative, not a numeric probability. Agreement counts never replace source independence. If evidence is missing, responses will say that; for high-impact questions, require human review.

The persistent admin queue (`VIBES_COUNCIL_REVIEW_FILE`, default `data/vibes-council-review.json`) stores short, sanitized review metadata, not prompts, source bodies or credentials. It is a single-process JSON file and is not a multi-writer distributed queue. Back up/retain it according to your deployment policy.

## Phase 10 — scoped task agents and controlled execution

The shared orchestration layer selects one task agent based on the requested agent rather than broadcasting every task. Set `agent` to `auto` for keyword/task-based selection or choose one agent explicitly. The six agents are **Research**, **Code**, **Write**, **Memory**, **Voice** and **Plan**. Every agent has a scope, allow-listed tool labels, a deadline, a token budget and a cost budget. Research tasks retrieve only trainer-approved local claims from the curriculum claim registry; those claims carry provenance and still require checking against original references. Local/request-supplied context is not sent to cloud providers unless `onlineBoost` is explicitly true. Results contain status, provenance, warnings, provider/tool metadata and an audit record. Prompts and credentials are not written to the audit record. Task records are currently in-memory and are lost when the server restarts; they are scoped to the app that created them.

### Phase 10 endpoints

| Method | Path | Access | Purpose |
|---|---|---|---|
| GET | `/api/v1/agents/capabilities` | Public | Discover agent availability, permissions and unconfigured future capabilities |
| POST | `/api/v1/agents/tasks` | App key with `agents:run` | Submit one typed agent task; use `Idempotency-Key` for retries |
| GET | `/api/v1/agents/tasks?limit=50` | App key with `agents:run` | List this app's tasks |
| GET | `/api/v1/agents/tasks/{taskId}` | App key with `agents:run` | Inspect state, progress, result and audit metadata |
| POST | `/api/v1/agents/tasks/{taskId}/approve` | Same app-bound `agents:run` permission | Explicitly approve a pending consequential action |
| DELETE | `/api/v1/agents/tasks/{taskId}` | Same app-bound `agents:run` permission | Request cancellation |

Supported `action` values include `assist`, `execute_code`, `build`, `publish`, `delete`, `send_message`, `spend_money` and `risky_command`. `assist` has no external side effect. All other actions enter `pending_approval`; **approval is not a promise that the effect is configured or will succeed**. External publishing, deleting, message-sending and spending integrations are intentionally absent, so these requests fail closed after approval without performing those effects. The build action also fails closed until an isolated build worker exists. Only the Code agent can use `execute_code`.

### Isolated execution sandbox

Execution is disabled by default. To enable Python/Node runtime evaluation, inspect `sandbox/Dockerfile`, build and scan a local image, then set `VIBES_AGENT_SANDBOX_IMAGE` to that image reference. In production, pin the base image to a verified digest and refresh it through a reviewed image-update process. `VIBES_AGENT_SANDBOX_RUNTIME` can select a Docker/Podman-compatible runtime.

The executor uses only fixed runtime entrypoints, a read-only workspace, no network, a read-only root filesystem, bounded temporary storage, memory/CPU/process limits, a non-root UID, dropped Linux capabilities, no-new-privileges and a hard runtime deadline. The server does not execute a shell command copied from a user prompt or a retrieved document. On failure or timeout, it kills the container on a best-effort basis and returns a safe failure. Runtime isolation still depends on the container engine, kernel and administrator-supplied image; use a rootless container runtime where possible and do not expose an unrestricted container socket to an untrusted tenant.

`build`, Gradle builds, network tools, publishing, deleting, sending messages and spending money are **not implemented as external tools** in this release. Image generation, video generation, real-time generated animations, and model training appear in the capability endpoint as `not_configured`; the server does not fabricate output for them. Installing those future capabilities requires an explicit adapter, safety/content checks, storage/retention rules, budget limits and test coverage.

### Phase 9–10 verification

The project includes Kotlin tests for provider budget gating, credential redaction, timeout and cancellation handling, council role presence, explicit disagreement, duplicate-source attribution, local-evidence/Online Boost routing, task idempotency, approval gates, task cancellation, app-bound task privacy and prompt-injection resistance. The static dashboard scripts can be checked with `node --check`, and the example provider config is valid JSON. The source archive does not contain `gradle-wrapper.jar`, and this execution environment has no installed Gradle command, so the full Kotlin test suite must still be run with Gradle 9.6.1-compatible tooling and JDK 21 before deployment.

## Phase 19–20 release controls

See `release/RELEASE_RUNBOOK.md` before creating a candidate. Generate a source manifest and test report with:

```bash
python3 scripts/release_pipeline.py --output release/candidate-test-report.json
python3 scripts/release_tool.py verify-manifest
python3 scripts/release_tool.py gate release/candidate-test-report.json --output release/release-gate-result.json
```

The gate is intentionally fail-closed: staging health, matched stable/candidate quality-latency-cost measurements, dedicated security/integration checks and a verified rollback drill are required. A skipped/unavailable check is not a pass. Use a separate staging copy for migration/restore tests; the SQLite helper does not automatically migrate JSON stores or Android Room data. All optional Phase 20 features are disabled in `release/release-manifest.json`; their individual problem, threat, cost, benchmark and rollback reviews are in `release/phase20-feature-review.json`.

## Shared trainer-approved knowledge (v2.6.1)

The trainer-to-server pipeline uses the existing curriculum store. Vibes Quantum may publish reviewed web-source Q&A records only with an app-bound key scoped to `knowledge:publish` and bound to `vibes-quantum-engine`. The server validates the app identity, scope, field bounds, approved HTTPS host, content hash and review timestamp before persisting a record as `APPROVED`. Vyron X can retrieve relevant approved evidence using an app-bound key with `knowledge:read`, or the official Android app can use its unexpired registered Google session through `/api/v1/me/approved-knowledge/search`; it cannot publish or withdraw data. Quantum can withdraw its own source records, which become `REVOKED` and are excluded from retrieval. See the repository-level `SHARED_KNOWLEDGE_SYNC.md` for the complete request contract and Render disk setup.

Issue new keys when adding these scopes; existing keys retain their original permissions. For production on Render, attach a persistent disk mounted at `/var/data` and set `VIBES_API_KEYS_FILE=/var/data/vibes-api-keys.json`, `VIBES_CURRICULUM_STORE_FILE=/var/data/vibes-curriculum.json`, and `VIBES_REGISTRY_FILE=/var/data/vibes-apps.json`. Without a persistent disk, server-side key and knowledge files may be lost when the service instance is replaced.


## Automatic VYRON X account registration and private vault (2026-10-30)

When the official Android VYRON X client completes a verified Google ID-token sign-in, it includes its stable installation UUID and fixed product ID `vibes-vyron-x`. The server automatically upserts the product in the app directory and creates or updates one server-issued per-user installation ID (`vyronx_<32 hex>`). A separate stable pseudonymous account ID (`usr_<32 hex>`) scopes the registration. The same account + product + installation tuple receives the same app ID on repeat sign-ins; a different account or installation receives a different ID. The installation registry status means registered; `lastSeenAt` records the most recent verified sign-in and is not a live socket-presence indicator. The client header is not authentication: the signed Google token is always verified.

- `VIBES_USER_APP_REGISTRY_FILE` (default `data/vibes-user-apps.json`) stores account-to-installation metadata.
- `GET /api/v1/auth/me/apps` returns only the signed-in user's registrations.
- `GET /api/v1/user-app-installations` returns registry metadata to gateway administrators.
- A per-user installation ID is an identity, not an API credential. The separate product-level `vibes-vyron-x` app key remains scoped for shared knowledge and app API permissions.

The private vault exposes `GET /api/v1/me/encrypted-vault` and `GET|PUT|DELETE /api/v1/me/encrypted-vault/{recordId}`. Its request body is an AES-256-GCM envelope (12-byte nonce, authenticated ciphertext); the server stores and returns ciphertext without possessing a decryption key. The default file is `data/vibes-encrypted-vault.json`, configurable with `VIBES_ENCRYPTED_VAULT_FILE`. The API caps a single ciphertext at 750,000 decoded bytes and each app installation at 250 records. The user/record ownership is taken from the verified bearer session, never from body fields.

This private-note vault is the only E2EE feature in this build. Ordinary server-side AI chats are not E2EE because inference requires readable prompt content. Android Keystore keys are device-bound and there is no cross-device recovery flow yet. Add both new data files to protected persistent storage/backups; they are excluded from source control by `.gitignore`.


## Operations, versioning, audit and backups

All supported application APIs are under `/api/v1/`; `/api/version` reports the current and supported contract versions. Responses include `X-Vibes-API-Version: v1` and correlation IDs. Breaking contracts must be introduced under a new `/api/vN/` namespace rather than silently changing v1.

Admin-only operations endpoints (send `X-Vibes-Gateway-Key`): `GET /api/v1/ops/metrics`, `GET /api/v1/ops/backups`, `POST /api/v1/ops/backups`, `POST /api/v1/ops/backups/{id}/verify`, and `POST /api/v1/ops/backups/{id}/restore-test`. Request metrics include counts, statuses and latency; structured JSON request/audit events omit authorization headers, tokens, bodies and query strings. Administrative endpoint activity is marked in the audit log.

File-store backups run every 24 hours by default and can be triggered manually. A separate scratch restore drill runs immediately when no successful drill exists, then every 7 days by default (`VIBES_BACKUP_RESTORE_TEST_INTERVAL_DAYS`). Each drill creates a fresh backup, restores it into a private scratch directory under the backup volume, byte-compares the extracted files against the archive, writes `restore-drill-latest.json`, and removes the scratch tree in a `finally` block (a cleanup failure marks the drill failed). It never writes into the live data directory. The admin `GET /api/v1/ops/backups` response includes the latest drill report and configured interval. Configure a durable private disk and set `VIBES_DATA_DIRECTORY`, `VIBES_BACKUP_DIRECTORY`, `VIBES_BACKUP_INTERVAL_HOURS`, `VIBES_BACKUP_RESTORE_TEST_INTERVAL_DAYS`, and `VIBES_SECURITY_AUDIT_FILE`. The scheduler checks hourly and retries a failed drill after a 24-hour cooldown. A shared-volume OS file lock prevents replicas sharing the backup volume from running the same scheduled backup or drill concurrently; use a persistent filesystem that supports advisory file locks. Investigate the restricted server logs and verify the backup volume before relying on backups.

Keep `VIBES_GATEWAY_KEY`, `VIBES_UPSTREAM_API_KEY`, Google client configuration, the admin email allowlist and connector credentials in server environment variables only. Never commit `.env`, place provider/emergency keys in APK resources, or return secrets to the console. Use app-bound API keys with least-privilege scopes and rotate/revoke compromised keys.

## Vibes Vyron X native account session (Phase 2)

The official Vyron X Android app may use its unexpired Google-verified account session on `/api/v1/models` and `/api/v1/chat/completions`, restricted to `ai:models` and `ai:chat`. The server binds this separately from the per-installation ID used by the encrypted vault. Approved claims are retrieved through `/api/v1/me/approved-knowledge/search`; Quantum publishing continues to require the trainer app key. Account sessions and refresh-token rotation history are stored durably in `VIBES_AUTH_SECURITY_FILE`; access JWTs last 15 minutes and refresh tokens last 30 days. All instances must share the auth-state file and JWT signing key.


## Unified Quantum runtime contract (2026-11-04)

`VibesQuantumServer` is the canonical intelligence engine; `VibesVyronX` is the primary Android client. The former standalone Quantum Android project has been removed. Non-Android evaluation, research and training assets are preserved under `VibesQuantumServer/workers/quantum-labs/`; they are not automatically deployed workers.

Server-owned discovery routes:

- `GET /api/v1/categories` and `GET /api/v1/tools?categoryId=...` — server-published category/tool metadata.
- `GET /api/v1/agents` — agent directory; `POST /api/v1/agents/execute` is the task submission alias. Existing `/api/v1/agents/tasks` remains the lifecycle route.
- `GET /api/v1/quantum/modules` and `GET /api/v1/quantum/migration/status` — migration state and route catalogue.
- `POST /api/v1/training/jobs`, `GET /api/v1/training/jobs/{id}`, `DELETE /api/v1/training/jobs/{id}` — app-scoped proxy to an optional trusted training worker.

The training worker is disabled by default and remains fail-closed until its sandbox attestation is complete. The worker must launch the trainer only inside Docker/Podman with `--network=none`, a read-only root filesystem, all Linux capabilities dropped, `no-new-privileges`, a non-root UID, PID/memory/CPU/tmpfs limits, a bounded wall-clock timeout, an explicit artifact suffix allow-list, and redacted logs. Direct host-process execution is not supported.

To build an eligible worker image, first create a fully resolved pip lock file with exact `==` versions and SHA-256 hashes (one package per line), review it, and copy it to `workers/quantum-labs/training-service/requirements-training.lock`. The unpinned `requirements-training.txt` is an input list only and is not accepted as a production lock. Build `Dockerfile.sandbox` with a Python base image pinned by digest and pass the SHA-256 of the lock file as `DEPENDENCY_LOCK_SHA256`; the Docker image label `org.vibes.dependency-lock-sha256` must match. Push/build the image, then configure the worker using its immutable `repository@sha256:...` reference.

Required worker variables (kept only on the training worker): `ALIS_SANDBOX_RUNTIME` (`docker` or `podman`), `ALIS_SANDBOX_IMAGE` (digest-pinned), `ALIS_DEPENDENCY_LOCK_FILE`, `ALIS_DEPENDENCY_LOCK_SHA256`, `ALIS_SANDBOX_MEMORY_LIMIT` (for example `8g`), `ALIS_SANDBOX_CPUS` (for example `2`), `ALIS_SANDBOX_PIDS_LIMIT` (for example `512`), `ALIS_SANDBOX_TMPFS_MB` (for example `512`), and `ALIS_ARTIFACT_SUFFIX_ALLOWLIST` (for example `.safetensors,.gguf,.pt`). `ALIS_TRAINER_EXECUTABLE` must be an absolute path inside the image; `ALIS_TRAINER_ARGUMENTS_JSON` remains a JSON string array. The image must carry the matching dependency-lock label. The worker exposes authenticated `GET /v1/training/health`; the Quantum gateway checks its sandbox readiness attestation before create/get/cancel operations. If any setting or image label is missing/mismatched, the worker health route returns 503 and the training API refuses the request.

Only after the worker health attestation returns `sandboxReady=true` should operators set `VIBES_TRAINING_ENABLED=true` and configure `VIBES_TRAINING_WORKER_URL`, `VIBES_TRAINING_WORKER_API_KEY`, `VIBES_TRAINING_ARTIFACT_ALLOWED_HOSTS` and `VIBES_TRAINING_JOBS_FILE` on Quantum. Calling apps require an app-bound key with `training:execute`; dataset URLs must use a configured HTTPS host; redirects are refused; repeated submissions require a stable `Idempotency-Key`. The `VIBES_TRAINING_WORKER_API_KEY` is a downstream credential and must never be copied into an APK.


## Quantum Voice (Realtime TTS + streaming STT)

Quantum Voice now uses the Realtime WebSocket API for live transcription and text-to-speech. The legacy `/api/v1/audio/speech` HTTP route remains as a compatibility facade for existing Vyron X clients, but it no longer calls `POST /v1/audio/speech`: it opens a server-side Realtime session with `gpt-realtime-2.1-mini`, collects 24 kHz mono PCM audio, and returns WAV (default) or raw PCM. MP3/AAC/Opus output is not supported by this Realtime adapter; clients should request `format=wav` or `format=pcm` and respect `Content-Type`, `X-Vibes-Audio-Format`, and `X-Vibes-Audio-Sample-Rate`.

OpenAI announced that `tts-1`, `tts-1-hd`, and `gpt-4o-mini-tts` on `/v1/audio/speech` shut down on **January 6, 2027**. The recommended replacement is `gpt-realtime-2.1-mini` through Realtime; that model is not supported by `/v1/audio/speech`. This source has migrated the TTS adapter to the Realtime WebSocket flow. See the [official deprecation schedule](https://developers.openai.com/api/docs/deprecations) and [Realtime API guide](https://developers.openai.com/api/docs/guides/realtime).

Configure server-only credentials with `VIBES_REALTIME_API_KEY` (or compatibility aliases `VIBES_TTS_API_KEY` / `VIBES_UPSTREAM_API_KEY`). `VIBES_REALTIME_BASE_URL` defaults to `wss://api.openai.com/v1/realtime`; optional per-modality overrides are `VIBES_TTS_REALTIME_URL` and `VIBES_STT_REALTIME_URL`. Realtime defaults are `VIBES_REALTIME_TTS_MODEL=gpt-realtime-2.1-mini` and `VIBES_REALTIME_STT_MODEL=gpt-live-transcribe`. File-based transcription remains available at `POST /api/v1/audio/transcriptions` with `VIBES_STT_BASE_URL`, `VIBES_STT_API_KEY`, and `VIBES_STT_MODEL=gpt-transcribe`. Never put provider credentials in Android configuration.

All voice routes require an official Vyron X account session. `GET /api/v1/audio/capabilities` reports `streamingSttEnabled`, and `GET /api/v1/audio/voices` returns Realtime-supported built-in voices (`alloy`, `ash`, `ballad`, `coral`, `echo`, `sage`, `shimmer`, `verse`, `marin`, `cedar`). `POST /api/v1/audio/transcriptions` remains the completed-recording path. `POST /api/v1/audio/speech` accepts JSON (`input`, `voice`, `language`, `speed`, `format`) and returns WAV or PCM generated through Realtime.

### Streaming speech-to-text WebSocket

Connect to `wss://<host>/api/v1/audio/transcriptions/stream` using the existing authenticated Vyron X account access token in the `Authorization: Bearer <access-token>` header. The server connects to OpenAI with its own API key and configures a Realtime transcription session (`gpt-live-transcribe`, PCM16 mono 24 kHz, low delay, no server VAD). The Android client must convert microphone audio to **mono PCM16 at 24 kHz** and send small base64 chunks; encoded AAC/MP3 frames are not accepted on the streaming socket.

Client events:

```json
{"type":"input_audio_buffer.append","audio":"<base64-pcm16-chunk>"}
{"type":"input_audio_buffer.commit"}
{"type":"input_audio_buffer.clear"}
```

The server first sends OpenAI's `session.updated` event, then `session.ready` with the sample rate and encoding. It relays `conversation.item.input_audio_transcription.delta` events as partial text arrives and `conversation.item.input_audio_transcription.completed` when the committed speech turn is finalized. Match turn events using `item_id`; deltas may be revised by the final transcript. Audio chunks are bounded (72 KB decoded per frame), and each live session is capped at 10 minutes / 24 MB. Sessions are authenticated, rate-limited, entitlement-reserved, and not persisted. Send `input_audio_buffer.commit` at the end of a speech turn and `input_audio_buffer.clear` to discard the current uncommitted buffer.

The server side exposes the live protocol. The Android client must still be updated to use this WebSocket and send PCM16/24 kHz chunks; the Android project itself is not included in this server archive. Until the mobile client is updated, completed-recording transcription remains available as the compatibility path. The socket sends `session.ready` only after the upstream `session.updated` acknowledgement; preceding Realtime lifecycle events are forwarded unchanged. The client should append audio in short frames (for example, 100–200 ms), render each `conversation.item.input_audio_transcription.delta` as provisional text, and replace that provisional text with the matching `...completed` transcript when received.

The old TTS HTTP shape remains for compatibility, but output encodings are now WAV or PCM only. The old TTS model configuration is intentionally not accepted by the Realtime route. Set `VIBES_REALTIME_API_KEY` on every server instance; do not expose it through `/capabilities`, app manifests, or mobile resources. Configure `VIBES_REALTIME_TTS_MODEL=gpt-realtime-2.1-mini` and `VIBES_REALTIME_STT_MODEL=gpt-live-transcribe` unless the provider's current account/model access has been explicitly validated.
