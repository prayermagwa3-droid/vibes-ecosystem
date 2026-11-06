# Vibes Ecosystem

This is the consolidated source workspace for Vibes Quantum Server and the standalone Vyron X Android application.

## Project layout

- `VibesQuantumServer/` — canonical Kotlin/Ktor API, admin console, identity, AI gateway, voice, operations and persistence services.
- `VyronX/` — the standalone Vyron X Android application supplied separately. This is the only Vyron X copy in this workspace.
- `render.yaml` — Render Blueprint for the Ktor server.
- `PUBLIC_ENDPOINTS_AND_HOSTING.md` — endpoint order, DNS setup and deployment steps.
- `cloudflare/` — optional Cloudflare Worker edge proxy source and Wrangler configuration for an account-specific `workers.dev` URL.
- `ANDROID_BUILD_ENDPOINTS.example` — public endpoint build-time environment sample.

The old `vyron x/` directory from the Family archive was deliberately removed; the standalone VyronX project replaces it. No Quantum Android application is introduced.

Start with `PUBLIC_ENDPOINTS_AND_HOSTING.md`. The Render and Cloudflare hostnames are configured as candidates in the client, but cloud deployment, domain verification and DNS changes still require actions in your own accounts. The Render Blueprint opts into Cloudflare's validated `CF-Connecting-IP` header for rate-limit identity; other hosting environments keep proxy headers disabled until their trusted ingress is configured.
