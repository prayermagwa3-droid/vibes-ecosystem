# Phase 2 — Vyron X live integration

## Implemented contract

- Google Credential Manager sign-in exchanges a Google ID token with the Vibes Quantum server.
- The server response supplies the verified account name/email and a short-lived user bearer session.
- The session is stored using the Android Keystore-backed token vault. No provider key, administrator key, or shared permanent app key is embedded in Vyron.
- The server scopes the bearer session to the registered `vibes-vyron-x` installation and its user-facing AI permissions.
- `/api/v1/models`, `/api/v1/chat/completions`, `/api/v1/auth/me`, logout, and approved-knowledge search use the account session.
- The embedded `app/src/main/assets/vyron/index.html` is the live WebView UI. Its `VyronNative` bridge provides the signed-in profile, chat history, message streaming, uploads, voice, settings, memory, theme persistence, and source links. The standalone uploaded `index.html` is a visual reference/demo and is not the Android runtime asset.
- The sidebar name and greeting are populated from the verified account profile. The demo identity `James` is not used by the Android UI.

## Attachment handling change

- Plain text, Markdown, CSV, JSON, XML, HTML, source-code, properties, and PDF attachments are text-extracted locally.
- At most 4 files, 4 MB per file, and 10 MB total are accepted. Extracted content is capped at 8,000 characters per file and 24,000 characters combined.
- Only extracted text plus filename/MIME metadata is sent to the text gateway; original binary bytes are not uploaded by this chat path.
- Unsupported binary formats remain selectable but are identified as having no readable text extraction.
- Attachment contents are explicitly labelled untrusted in the user message context.

## Known limitations

- Image OCR and Office document extraction are not implemented by this change.
- Ordinary cloud AI conversations are not end-to-end encrypted.
- The current server issues signed 15-minute access JWTs and rotating 30-day refresh tokens. The Android client refreshes proactively and stores refresh-token rotations in encrypted storage; the server stores refresh/session state durably when its configured shared or persistent store is available.
- Full Android compilation and device validation must be run in the user's Android/Gradle environment; this workspace does not contain the Gradle wrapper JAR or a cached Gradle distribution.
