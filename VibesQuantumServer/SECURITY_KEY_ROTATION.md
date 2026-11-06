# Vibes Quantum authentication and key rotation runbook

This runbook applies to the Ktor server's admin console, emergency gateway key, and ordinary VYRON X user sessions.

## Persistent auth state and instance layout

Set the following paths to a persistent data volume, not an ephemeral container filesystem:

```sh
VIBES_AUTH_SECURITY_FILE=/var/data/vibes-auth-security.json
VIBES_JWT_SIGNING_KEY_FILE=/var/data/vibes-jwt-signing.key
VIBES_SECURITY_AUDIT_FILE=/var/data/security-audit.jsonl
```

Every concurrently running server instance must use the **same** `VIBES_AUTH_SECURITY_FILE` and the same JWT signing key (either the same `VIBES_JWT_SECRET` value or the same `VIBES_JWT_SIGNING_KEY_FILE`). The auth state uses an OS file lock and atomic replacement so separate JVM processes sharing a POSIX volume coordinate admin sessions, login throttles, refresh-token consumption and revocation. The shared volume must support advisory file locks and same-directory atomic rename. Do not assume an arbitrary object store, weakly consistent network mount, or per-container local disk provides this guarantee; test the actual hosting volume before scaling out.

The JWT signing key file is generated as a random 32-byte key on first startup and restricted to owner read/write where POSIX permissions are available. Creation is protected by a file lock to prevent startup races across instances. Back it up securely with the auth state file. Losing it invalidates active access JWTs; users can reauthenticate or obtain a new access JWT by presenting a still-valid refresh token if the auth state is preserved. If you set `VIBES_JWT_SECRET`, use the same 32+ byte value on every instance and keep it in the secret manager; a blank value falls back to the file-backed key.

Admin browser sessions are random opaque cookies. Only their SHA-256 digests and allowlisted email are stored. They survive restarts and expire eight hours after issue. Failed admin sign-ins are counted per resolved client address and remain blocked for the remainder of the 15-minute window across restarts and instances. User login endpoint throttles use the same durable state store.

## Emergency gateway key rotation without downtime

This overlap procedure is for a planned rotation where the current key is not known to be compromised. `VIBES_GATEWAY_KEY` is the current key; `VIBES_GATEWAY_KEY_PREVIOUS` is a temporary second accepted key. The server never logs either key or any part of it.

1. Generate the replacement key on a trusted machine: `openssl rand -hex 32`. Keep it in your secret manager.
2. Deploy a server configuration to every instance with the old value still in `VIBES_GATEWAY_KEY` and the new value in `VIBES_GATEWAY_KEY_PREVIOUS`. Roll instances one at a time. During this phase old clients continue to work; instances updated early accept both values.
3. Update every trusted caller that sends `X-Vibes-Gateway-Key` to use the new key. Verify successful authenticated requests against every instance.
4. Swap the server variables on all instances: set `VIBES_GATEWAY_KEY` to the new key and `VIBES_GATEWAY_KEY_PREVIOUS` to the old key. Roll instances one at a time. Both keys remain accepted throughout the mixed rollout.
5. After confirming no caller uses the old key, remove `VIBES_GATEWAY_KEY_PREVIOUS` from every instance and roll the final configuration. The old key is now rejected. Check the privileged audit log for expected emergency-key events and investigate unexpected use.
6. Remove the old value from the secret manager and deployment history according to your secret-retention policy. Never put either value in an APK, browser code, Git, a ticket, or the audit log.

For a suspected compromise, prioritize revocation over compatibility: deploy a newly generated key and remove the compromised value immediately, then update callers. A no-downtime overlap necessarily accepts the old credential while it is configured as previous.

## VIBES_ADMIN_EMAILS rotation without downtime

`VIBES_ADMIN_EMAILS` is an exact comma-separated list of verified Google email addresses. To transfer administrator access:

1. Add the new administrator address while retaining the current address, for example `old-admin@example.com,new-admin@example.com`, and deploy that allowlist to every instance.
2. Verify the new address can complete Google sign-in and load `/console/` on the deployed HTTPS origin. Keep the current administrator session active during the check.
3. Remove the old address and deploy the final allowlist. New sign-ins from the old address are rejected, and the server re-checks the current allowlist for every admin-cookie authorization, so the old address's existing cookie stops working as soon as each instance receives the new configuration.
4. For a departing or compromised administrator, also invalidate all their stored cookies so they cannot become active if the address is later re-added. Run this maintenance call from the server module against the shared auth-state file and record the returned count in the change ticket:

   ```kotlin
   val revoked = AuthSecurityStore("/var/data/vibes-auth-security.json")
       .revokeAdminSessions("old-admin@example.com")
   check(revoked >= 0)
   ```

   The operation removes only that administrator's browser sessions; it does not revoke ordinary user refresh-token families. It is covered by `AuthSecurityTest` and uses the same inter-process file lock as login/session writes.

Environment variables are read at process startup. Use a rolling deployment with at least one healthy instance and the shared auth state mounted on every instance; do not expect an environment-variable edit to live-reload an already running Ktor process. Keep the overlap allowlist while testing the replacement address; only remove the outgoing address after the replacement admin is verified.

## User access and refresh tokens

Successful Google, email, or phone sign-in returns:

- an HS256 signed JWT access token with a 15-minute lifetime;
- an opaque refresh token with a 30-day lifetime.

Send the access token as `Authorization: Bearer <accessToken>`. When it expires, POST JSON to `/api/v1/auth/refresh` with `{"refreshToken":"..."}`. The response contains a new access token and a new refresh token. Replace both tokens atomically in the client. Refresh tokens are stored as hashes, are single-use, and rotate on every successful refresh. Reusing an already consumed refresh token revokes that session family; the user must sign in again. `/api/v1/auth/logout` revokes the current session and `/api/v1/auth/logout-all` revokes all sessions for the account.

Do not retry a refresh request with the previous refresh token after an ambiguous timeout. Because rotation is single-use, if the server committed the rotation but the response was lost, retrying the old token triggers reuse detection and revokes the family. Vyron X therefore retires the old refresh token after an ambiguous network/5xx/malformed-success outcome, keeps the current access JWT only until its signed expiry, and then requires a fresh sign-in. HTTP 429 is different: it is rejected before rotation, so the client may retry the same refresh token after the Retry-After window.

## Proxy and client IP configuration

For a Render deployment, the Blueprint enables `VIBES_TRUST_CF_CONNECTING_IP=true`. Render documents that its public ingress passes traffic through Cloudflare and Render load balancers, and Cloudflare documents `CF-Connecting-IP` as the visitor IP header sent from its edge to an origin. The server accepts that header only when this explicit setting is enabled and only when it parses as a numeric IP. Keep this setting for the Cloudflare-fronted Render origin; do not copy it to a directly exposed VPS/Termux host.

For any other proxy or tunnel, set `VIBES_TRUSTED_PROXIES` to a comma-separated list of the **actual direct peer IP addresses or CIDR ranges** of that proxy (not client IPs and not broad public ranges). The server ignores `X-Forwarded-For` unless the TCP peer matches this list. For a trusted peer, it walks the forwarded chain from right to left and selects the right-most address not itself in the trusted proxy list. This prevents a client from choosing its own throttle bucket by supplying a forged header when the proxy correctly appends/overwrites the forwarding chain. Never use `0.0.0.0/0` or `::/0`.

To confirm the deployment, check the proxy access log and deliberately send a request with a known client IP through the tunnel. Confirm the server resolves it to that IP. Do not use Render's outbound IP list as inbound proxy addresses. If the Ktor process is reached directly, leave `VIBES_TRUSTED_PROXIES` empty and `VIBES_TRUST_CF_CONNECTING_IP=false`.

## Regression tests before deployment

Run the complete Kotlin test suite before promoting the change:

```sh
./gradlew test
```

The auth-specific coverage is in `AuthSecurityTest`, `AdminConsoleAuthIntegrationTest`, and `ApplicationTest`. It includes session persistence and expiry, Google signature/audience/`email_verified` validation, the active email allowlist, the cookie-write Origin check, durable login throttling, trusted-proxy address selection, audit redaction, and the staged emergency-key overlap sequence. A successful local test run does not replace verifying file-lock and atomic-rename behavior on the production persistent volume.

## Audit review

`VIBES_SECURITY_AUDIT_FILE` is append-only JSONL. It records privileged route/method, timestamp, actor (`admin email`, `emergency-key`, or `anonymous`), action and outcome. Emergency-key events never include the credential, prefix, digest, request body or authorization header. Protect the audit file and ship copies to storage controlled separately from the application host; a local hash-chain or append-only file cannot protect against an administrator with write access to the host replacing the complete file.
