# Phase 2 Report: Account Sign-In

## Added
- Email and password accounts (`AccountAuth.kt`). Passwords use PBKDF2-HMAC-SHA256 from the JDK with 310,000 iterations and a random salt. Policy: 12 to 128 characters, cannot equal the email.
- Lockout after 5 failed logins for 15 minutes, per account. Unknown emails take the same time to reject as wrong passwords, and both return the same message.
- Google sign-in is kept. Accounts are keyed by the existing stable Google id, so existing users keep their encrypted vault. Google sign-in never merges into an existing email account; if the address matches, the user is told to sign in with their password and link Google.
- Explicit account linking: `POST /api/v1/auth/link/google` (signed in) and phone linking through the phone verify endpoint (signed in).
- Phone sign-in through Twilio Verify. Codes: 5 sends per phone per hour. Checks: 5 attempts per code window. Numbers must be E.164.
- Sign out (`/logout`), sign out everywhere (`/logout-all`, revokes every session for the account), and `GET /api/v1/auth/methods`.
- Every method issues the same session token format, accepted by the existing chat and API bearer check.

## Endpoints
- POST /api/v1/auth/email/register
- POST /api/v1/auth/email/login
- POST /api/v1/auth/logout
- POST /api/v1/auth/logout-all
- GET  /api/v1/auth/methods
- POST /api/v1/auth/link/google
- POST /api/v1/auth/phone/start
- POST /api/v1/auth/phone/verify

## Configuration (server environment only)
- VIBES_ACCOUNTS_FILE (default data/vibes-accounts.json)
- GOOGLE_CLIENT_ID (existing)
- TWILIO_ACCOUNT_SID, TWILIO_AUTH_TOKEN, TWILIO_VERIFY_SERVICE_SID (phone sign-in is off until all three are set)

## Not done in this phase
- Password reset and email verification need an email provider. Not built until one is chosen.
- Android sign-in screens for email and phone are not built yet. The server endpoints are ready.
- This report describes the earlier phase. The current auth implementation in this package persists refresh-token/session metadata in `VIBES_AUTH_SECURITY_FILE` (and supports shared database-backed auth state where configured), issues 15-minute signed access JWTs and rotates 30-day refresh tokens. The Render Blueprint mounts `/var/data` for durable state.
- Accounts use a JSON file. Persistent storage on Render's free tier needs the database decision first.
- Rate limits use the connecting address, so behind a proxy they are shared until trusted-proxy handling is added.

## Verification status
- Bracket balance checked on every new and edited Kotlin file.
- Unit tests written in src/test/.../AccountAuthTest.kt. NOT run. Run `./gradlew test`.
- NOT compiled. Run `./gradlew build` before deploying.

## Update: email and phone sign-in return the app identity
- Email register, email login, and phone verify (sign-in) now accept an optional `appRegistration` field, the same as Google sign-in.
- They register the Vyron X installation with the same rules as the Google route, through the shared helper `registerVyronInstallation` in Routing.kt.
- Responses include `appInstallation`, so the Vyron X client can save the session without changes to its parser.
- Sessions use the same store and format as Google. Chat and the bearer check accept them.
- Invalid registrations are rejected with 400 before a session is issued. Storage failures return 503.
- The Google route still has its own copy of this logic. It was left unchanged to avoid regressions in working sign-in. Moving it to the shared helper is a safe follow-up.
- The Vyron X app still needs client calls for email and phone sign-in. The server is ready for them.
