# Vibes Quantum Server 2.7.0 — Platform Upgrade Report

## Scope

This release implements the next platform tranche for database persistence, chat transport/history, provider fallback, subscription checks, cloud budgets, and maintainability. It preserves the existing REST/SSE gateway and legacy JSON-file import paths.

## Implemented

### 7. Transactional database and migrations

- Added a JDBC database boundary supporting SQLite (default for one Termux/server process) and PostgreSQL.
- Added checksum-verified, versioned forward/down migrations V0001–V0003.
- Added explicit `databaseStatus` and `rollbackDatabase` Gradle tasks. Inspection/rollback opens the DB without applying pending migrations, so a rollback command cannot accidentally apply a new version immediately before undoing it.
- Added SQL-backed conversation messages and device cursors, subscriptions and verified purchases, cloud spend reservations/events, daily gateway usage, and a JSON document store.
- Moved accounts/auth state, app registry, hashed app API keys, user-app installations, encrypted-vault ciphertext, learning, and curriculum documents to database persistence when `VIBES_DATABASE_URL` is set. Existing JSON files are imported once when a database key is absent.
- Remaining file stores include source-candidate state, workspace artifacts/brain files, training-job records, security audit JSONL, council review queue, and backup catalog. Some JSON-document stores retain in-process caches; this release is intended for one server process, not fully horizontally scaled multi-instance writes.

### 8. Authenticated WebSocket chat

- Added `wss://<host>/api/v1/ws/chat` with bearer-session authentication.
- Added `ready`, `send`, `resume`, and `ack` envelopes, durable sequence numbers, bounded replay batches, client-message idempotency, and monotone device acknowledgements.
- Messages and completed assistant results are stored before delivery so reconnect/replay can recover committed conversation history.
- Important limitation: the existing provider router returns buffered completions. WebSocket `delta` frames are transport chunks of a completed response, not live upstream token streaming.

### 9. Provider fallback

- Added explicit failure classes for timeouts, 429, 5xx, auth, client, network, and mid-stream failures.
- Retry/fallback is allowed only before response commitment for configured retryable classes. Client errors and mid-stream failures do not trigger a second provider request.
- Added tests for the fallback classification and no-fallback cases.
- The legacy direct SSE path remains pinned to its configured upstream; it cannot fail over after the stream starts.

### 10. Server-side conversation sync

- Added account-owned conversation list, message replay, and acknowledgement endpoints.
- WebSocket conversations are durable and shared between devices using the same account.
- The legacy `/api/v1/chat/completions` path now accepts optional `conversation_id`, returns `X-Vibes-Conversation-ID`, and persists authenticated account user/assistant text. API-key-only app requests are intentionally not linked to a personal user history.

### 11. Server-side subscription entitlements

- Added Free, Pro Lite, Pro, and Pro+ server-owned tier policies.
- Added Google Play Developer API purchase-token verification using a configured service account, product-to-tier allowlist, restore, persisted purchase records, and server-side expiry checks.
- Client-provided tier and expiry values are never trusted.
- Rollout still requires a configured Play Console service account and a real Play test purchase. Real-time Developer Notifications/automatic renewal webhooks are not implemented; clients must verify/restore on app resume and after purchase.

### 12. Per-user daily cloud budgets

- Added transactional daily call-count and USD-reservation limits by tier.
- Reservation happens before cloud provider invocation; settlement is idempotent and cannot double-charge a reservation.
- Wired authenticated account chat, WebSocket chat, opt-in Theory of Mind cloud analysis, and speech STT/TTS to the user budget.
- Cost reservations use configured estimates/heuristics unless the caller has provider usage pricing available. This is an enforcement ceiling on server-side estimates, not a replacement for provider account spending limits. App-key-only council/agent/workspace routes do not have end-user identity and are not yet charged to a personal subscription budget.

### 13. Source decomposition

- Extracted gateway DTOs and registry stores, platform routes, workspace DTOs, and workspace persistence helpers into focused source files.
- `Routing.kt` and `WorkspaceEngineeringService.kt` remain large and still need a deeper route/service decomposition.
- The supplied archive is the server project and does not include the Android application's `MainActivity.kt`; that split must be applied in the separate Android project archive.

## Verification performed in this environment

- Python support/contract suite: **73 passed, 4 skipped**.
- Python script bytecode compilation: passed.
- SQLite migration SQL: forward apply and reverse rollback sequence **passed** using Python's SQLite engine.
- Kotlin/Gradle suite: **not run**. `./gradlew test --no-daemon --offline` stopped before compilation because Gradle 9.6.1 is not installed or cached, and this environment cannot fetch it. Therefore this report does not claim Kotlin compilation or Ktor integration tests passed.
- A live Google Play verification/restore test and a PostgreSQL multi-instance test remain required before enabling paid subscriptions or horizontal scaling.

## Deployment sequence

1. Back up the current JSON data and database volume.
2. Configure `VIBES_DATABASE_URL`; use SQLite on a durable local volume for one process, or PostgreSQL for a later multi-instance deployment.
3. Keep legacy file paths configured during the first database startup so their contents can be imported. Preserve the source files until a database restore has been tested.
4. Configure the Play package, service-account JSON secret, and exact product-to-tier map before enabling subscriptions.
5. Run `./gradlew databaseStatus`, then `./gradlew test --no-daemon` under JDK 21 with Gradle 9.6.1 and dependency access.
6. Verify Google Play purchase/restore on a test track and verify auth, WebSocket replay, conversation sync, and budget rejection in staging before production rollout.

## Important environment configuration

See `.env.example` for the complete list. Key settings include `VIBES_DATABASE_URL`, database credentials for PostgreSQL, `VIBES_PLAY_PACKAGE_NAME`, `VIBES_PLAY_SERVICE_ACCOUNT_JSON`, `VIBES_PLAY_PRODUCT_TIERS`, the per-tier `VIBES_*_DAILY_*` limits, and `VIBES_LLM_CALL_RESERVE_USD`.
