# Vyron X Phase 4 — Bridge security, memory revision check, and offline recovery

## Step 1 — JavaScript-to-native bridge

- Audited the actual bridge registration and every `@JavascriptInterface` method in `MainActivity.kt` (the requested `WebEngine.kt` is a native research/network client, not the registered bridge).
- `SecureTokenVault` is Kotlin `internal`; it is never exposed to WebView JavaScript and no token/refresh-token/PKCE data is returned by bridge methods.
- Added an active-document gate, local bundled-document URL validation, strict CSP with a hash-locked inline script, no frames, no JS network access, blocked remote navigation inside the privileged WebView, and input bounds/allow-lists on bridge arguments.
- Native confirmation is required before the separate text-analysis bridge operation transmits selected text. Authenticated native session validation remains inside `VibesEngineClient`.
- Removed unnecessary WebView password saving and kept file/content/file-URL access disabled.

## Step 2 — Memory revision heartbeat

- Added deterministic `sha256-canonical-json-v1` fingerprints over the local Store memory, vector index, and knowledge graph.
- Snapshot operations are synchronized with the Store/VectorStore/KnowledgeGraph mutations to avoid hashing partially-updated state.
- Added an opt-in setting (off by default), a foreground heartbeat after resume and every five minutes, a 10-second request timeout, and explicit `in_sync`, `out_of_sync`, `unsupported`, `auth_required`, and `unavailable` states.
- Only the 64-character digest, algorithm identifier, and client metadata are sent. The digest is still metadata and may be correlatable; the user must opt in.
- **Server dependency:** this ZIP does not contain the separate Ktor server source. The Android client calls `POST /api/v1/me/memory-index/revision-check`. The exact request/response and auth requirements are in `docs/UNIFIED_MEMORY_REVISION_SYNC_CONTRACT.md`. Until the Ktor route is deployed, the app correctly reports `unsupported`; it does not claim that data is synchronized. A revision check detects divergence but does not itself reconcile records.

## Step 3 — Offline and tunnel recovery

- Added classification of socket timeouts, DNS failures, connect failures, TLS errors, EOF, interrupted I/O, and common tunnel/reset messages.
- Connection failures show a specific explanation and actionable `Retry` / `Use cached local mode` actions in the WebView.
- Cached local mode consults only on-device saved memory and safe local calculations; it does not call the web researcher, model gateway, or remote provider.
- Retry resends the user's prompt through the normal authenticated flow.

## Verification

- JavaScript inline script syntax, CSP SHA-256 hash, HTML IDs, Android XML resources, and bridge-method guard coverage are statically checked.
- Full Gradle/Android compilation was not available in this environment. The project uses its configured Gradle wrapper properties, but no `gradle` executable or wrapper JAR is present here.
