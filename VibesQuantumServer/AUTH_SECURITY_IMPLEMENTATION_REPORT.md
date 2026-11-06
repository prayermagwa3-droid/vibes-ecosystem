# Auth and security upgrade report

## Implemented

1. **Persistent admin console sessions** — opaque eight-hour cookies are stored as SHA-256 token digests plus the verified admin email. Expired sessions are pruned, logout revokes one cookie, and allowlist changes are checked at request time.
2. **Signed user tokens and refresh rotation** — user sign-in returns an HS256 JWT access token (15 minutes) and a random opaque refresh token (30 days). Only refresh-token hashes are persisted. Rotation is atomic under the auth-state lock; replaying a consumed token revokes its session family. Logout and logout-all revoke stored sessions.
3. **Shared durable login throttling and proxy resolution** — endpoint buckets are persisted in the auth-state file and coordinated by JVM plus OS file locks. Email, phone, and Google sign-in share the user-login host bucket; admin sign-in has its stricter failed-attempt threshold. `X-Forwarded-For` is considered only when the direct peer matches `VIBES_TRUSTED_PROXIES`.
4. **Privileged audit trail** — admin sign-in/out, protected console reads, admin-gated actions, and every supplied emergency gateway key on protected routes are recorded to JSONL with UTC time, actor, action, route and outcome. Supplied credentials, authorization headers, request bodies and token values are not recorded. Appends are coordinated across processes and flushed to disk.
5. **Rotation runbook** — `SECURITY_KEY_ROTATION.md` documents staged overlap and removal for `VIBES_GATEWAY_KEY` / `VIBES_GATEWAY_KEY_PREVIOUS`, plus a rolling `VIBES_ADMIN_EMAILS` transfer and targeted session revocation.
6. **Kotlin regression tests** — unit and Ktor integration tests cover admin session persistence/expiry, Google ID-token signature/audience/verified-email checks, allowlisting, cookie Origin enforcement, durable throttling, JWT expiry/tampering, refresh rotation/reuse revocation, emergency-key audit redaction, trusted-proxy resolution and staged gateway-key rotation.

## Required deployment configuration

- Mount `VIBES_AUTH_SECURITY_FILE`, `VIBES_JWT_SIGNING_KEY_FILE`, and `VIBES_SECURITY_AUDIT_FILE` on persistent protected storage.
- Every concurrently running server instance must share the same auth-state file and JWT signing key. The volume must support OS advisory file locks and same-directory atomic rename; validate that behavior on the actual host before scaling out.
- Keep `VIBES_JWT_SECRET` (if used), both emergency-key values during the overlap window, Google client configuration, and `VIBES_ADMIN_EMAILS` in the server's secret/environment manager only.
- Set `VIBES_TRUSTED_PROXIES` to the actual direct peer IPs of the reverse proxy/tunnel. Leave it empty when requests reach Ktor directly.
- Follow `SECURITY_KEY_ROTATION.md` before changing either the emergency key or administrator allowlist.

## Verification status

The Kotlin tests have been added but **were not executable in the editing environment**. The repository wrapper requires Gradle 9.6.1; that distribution was not installed or cached, and the environment could not resolve the Gradle distribution host to download it. Run the complete suite in a Gradle-enabled environment before deployment:

```sh
./gradlew test
```

Source-level inspection and packaging checks were performed here, but they are not a substitute for a successful Gradle compilation and test run. Also verify the shared-volume locking/rename behavior and reverse-proxy address chain on the actual deployment platform.
