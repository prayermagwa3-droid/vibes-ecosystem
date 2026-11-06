# Vibes Ecosystem — public endpoints and hosting

## Endpoint order used by Vyron X

Vyron X now uses an HTTPS-only endpoint pool in this order:

1. Render: `https://vibes-quantum-api.onrender.com` (primary; Render service name is `vibes-quantum-api`).
2. Cloudflare-proxied custom host: `https://api.vibesquantum.tech`.
3. Legacy DuckDNS host: `https://vibesquantum.duckdns.org`.
4. Custom domain roots/API aliases: `https://vibesquantum.tech`, `https://vibesquantum.com`, and `https://api.vibesquantum.com`.
5. Additional hosts supplied through `VIBES_EXTRA_GATEWAY_URLS` at Android build time.

The app first tries the saved primary. GET/HEAD requests may retry on selected gateway/edge failure responses. Write requests are retried only when DNS or the socket connection fails before an HTTP connection is established, or when Cloudflare returns an explicit 521–524 origin-unavailable response; other write failures are never replayed. This avoids duplicating operations that may already have reached the server. The configured endpoint list is a trust list because authenticated requests can be retried to its members. Do not add a domain unless you control it and it serves the same Vibes API.

For rate limiting, the server does not trust `X-Forwarded-For` by default. On the Render Blueprint it prefers a valid `CF-Connecting-IP` because the Render public ingress is Cloudflare-fronted. For other hosts, set `VIBES_TRUSTED_PROXIES` to the *direct* proxy peer IPs/CIDRs and let the server walk the forwarded chain from right to left. Do not solve proxy-IP detection by trusting every incoming `X-Forwarded-For` value; that lets clients spoof their own rate-limit bucket.

All entries must be API root URLs, without `/api`, `/api/v1`, query strings, fragments, user credentials, or non-HTTPS schemes. The client automatically keeps `/api/v1/...` paths when switching hosts.

## What is actually configured versus what still needs DNS

The source defaults to the Render service URL derived from the service name and the Cloudflare custom API host above. Those hosts become live only after the Render service is deployed and the Cloudflare DNS/custom-domain configuration is completed. The ZIP does not create cloud accounts, deploy services, register domains, edit DNS, or verify domain ownership. Only retain custom domains in the app's endpoint pool and server origin allowlist if you own them and have configured them to route to this same Vibes backend.

`vibesquantum.duckdns.org` is included as the legacy fallback, but it works only while its DuckDNS record points at a reachable running server. A DuckDNS name is not automatically connected to Render by this app.

## Render deployment

1. Push the `VibesEcosystem` directory to a Git repository.
2. In Render, create a Blueprint from that repository and select the root `render.yaml`. The API Dockerfile is `VibesQuantumServer/Dockerfile`.
3. Keep the service name `vibes-quantum-api` if you want the generated address to match the Android default. If Render assigns/uses another service subdomain, update `VIBES_RENDER_URL` for the Android build.
4. The Blueprint binds to `0.0.0.0:$PORT`, checks `/api/v1/health`, and mounts `/var/data` for the file-backed state, signing key, backups and audit trail.
5. The Blueprint places the SQLite database at `/var/data/vibes-quantum.db` and sends all configured data, auth, audit and backup file paths to `/var/data`. Enter `VIBES_GATEWAY_KEY`, `VIBES_ADMIN_EMAILS`, and `GOOGLE_CLIENT_ID` in the Render Environment settings. Generate a strong emergency key; never put it in the APK.
6. `VIBES_TRUST_CF_CONNECTING_IP=true` is enabled in this Render Blueprint because Render documents that public traffic passes through Cloudflare and its load balancers. The server accepts `CF-Connecting-IP` only when this explicit flag is enabled and only if the value parses as a numeric IP. Cloudflare documents that this header carries the connecting visitor's IP on edge-to-origin requests. Verify the header is present in a real Render request before depending on it. Do not copy this setting into a directly exposed Termux/VPS deployment that does not sit behind Cloudflare.
7. `VIBES_TRUSTED_PROXIES` is an optional exact-IP/CIDR allowlist for any other reverse-proxy chain. Only add verified addresses of the direct proxy peers; the application will then use `X-Forwarded-For` only when the socket peer matches one of those addresses/ranges. Never use `0.0.0.0/0`, `::/0`, or trust arbitrary forwarded headers. Do not use Render's *outbound* IP ranges as inbound proxy addresses; those are a different direction.
8. Preserve the persistent disk. Render services otherwise have an ephemeral filesystem. This Blueprint intentionally uses one instance with a persistent disk; Render disks are single-instance and prevent zero-downtime deploys. The admin session / refresh / login-throttle store is persisted by the auth store; use a shared PostgreSQL configuration before enabling multiple app instances.

Render references: [Docker deploys](https://render.com/docs/docker), [health checks](https://render.com/docs/health-checks), [persistent disks](https://render.com/docs/disks), [Blueprint reference](https://render.com/docs/blueprint-spec), and [how Render handles DDoS / forwarded client IPs](https://render.com/articles/how-render-handles-ddos-attacks). Cloudflare header behavior: [HTTP headers](https://developers.cloudflare.com/fundamentals/reference/http-headers/).

## Cloudflare Worker fallback

A ready-to-deploy reverse-proxy Worker is included in `cloudflare/worker.js` with `cloudflare/wrangler.toml`. Deploy it in your Cloudflare account (for example with Wrangler from the `cloudflare/` directory). Cloudflare assigns an account-specific address similar to `https://vibes-quantum-edge.<your-account-subdomain>.workers.dev`; the exact host cannot be known until that account deploys it. The Worker forwards API paths, methods, request bodies, authorization headers and WebSocket upgrades to the Render origin, with redirects left manual.

After deployment, set `VIBES_CLOUDFLARE_URL` to the generated `workers.dev` URL when building Vyron X, or add it to `VIBES_EXTRA_GATEWAY_URLS`. Add the exact origin to server `VIBES_PUBLIC_ORIGINS` if the browser console will be opened there, and add it to Google's authorized JavaScript origins for browser Google sign-in. You can also attach `api.vibesquantum.tech` as a Cloudflare Worker custom domain; in that case use it as the Cloudflare fallback value.

Important: this Worker is an edge ingress and security/routing layer, not a second Ktor compute deployment. It still forwards to Render, so it cannot serve the app when the Render origin itself is offline. Independent compute failover requires a second Ktor deployment and a health-checked routing/load-balancing setup.

## Cloudflare custom host

Use `api.vibesquantum.tech` as the Cloudflare Worker custom domain or proxied ingress and, once validated, also add `vibesquantum.tech`, `vibesquantum.com`, and `api.vibesquantum.com` as custom domains in Render if you intend to serve the API from each. For each hostname:

1. Add the hostname as a custom domain to the Render web service and complete Render's domain verification.
2. In Cloudflare DNS, create the requested CNAME record pointing to `vibes-quantum-api.onrender.com`. For the `api` host, use `api` as the record name. For apex/root domains, use Cloudflare's supported CNAME flattening and follow the exact target/verification values shown by Render.
3. Enable Cloudflare proxy only after Render domain verification and TLS are healthy. Use Full (strict) SSL/TLS mode.
4. Verify `https://HOST/api/v1/health` for every hostname before shipping an APK with the endpoint in its pool. Add the same exact origin to `VIBES_PUBLIC_ORIGINS` only after verification; Google browser sign-in and the admin console accept only these listed origins.

The Cloudflare hostname is an alternate ingress path to the Render service, not an independent copy of the Ktor server. If Render's origin is completely unavailable, a proxied CNAME alone cannot run the application. True compute failover needs a second independently deployed Ktor origin and a Cloudflare Load Balancer/health-monitor configuration.

Cloudflare reference: [Worker custom domains](https://developers.cloudflare.com/workers/configuration/routing/custom-domains/).

## Build the standalone Vyron X client

From `VyronX/`, set public URLs at build time (or pass equivalent `-P` Gradle properties):

```bash
export VIBES_RENDER_URL="https://vibes-quantum-api.onrender.com"
export VIBES_CLOUDFLARE_URL="https://api.vibesquantum.tech"
export VIBES_EXTRA_GATEWAY_URLS="https://vibesquantum.duckdns.org,https://vibesquantum.tech,https://vibesquantum.com,https://api.vibesquantum.com"
./gradlew :app:assembleRelease
```

The Render and Cloudflare values are public base URLs, not secrets. API/provider keys, JWT signing secrets, Google web client secrets, and `VIBES_GATEWAY_KEY` must remain in server-side environment configuration. Add each browser origin used for Google/admin sign-in to Google's OAuth authorized JavaScript origins as well as `VIBES_PUBLIC_ORIGINS` on Render.

## Scaling and persistence note

The current file-backed stores live under `/var/data` on Render's persistent disk, and SQLite is also stored there. A Render disk can be attached to only one instance, so leave this service at one instance. The auth-security store supports a shared SQL database when `VIBES_DATABASE_URL` is configured. For a multi-instance deployment, provision paid Render Postgres (or another managed PostgreSQL service), set `VIBES_DATABASE_URL` to its JDBC or `postgresql://` connection string, and migrate the remaining file-backed registries/audit/backup data to shared services before removing the disk or increasing instance count.
