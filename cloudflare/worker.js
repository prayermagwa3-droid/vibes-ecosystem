/**
 * Vibes Quantum Cloudflare edge proxy.
 *
 * This keeps the Ktor application on Render and exposes an account-specific
 * <worker>.<account-subdomain>.workers.dev ingress. It is an edge ingress,
 * not independent compute: the Render origin must still be online.
 */
const DEFAULT_ORIGIN = "https://vibes-quantum-api.onrender.com";

function resolveOrigin(env) {
  const value = String(env.VIBES_ORIGIN || DEFAULT_ORIGIN).trim();
  const origin = new URL(value);
  if (
    origin.protocol !== "https:" ||
    !origin.hostname ||
    origin.username ||
    origin.password ||
    origin.search ||
    origin.hash ||
    (origin.pathname !== "/" && origin.pathname !== "")
  ) {
    throw new Error("VIBES_ORIGIN must be an HTTPS origin without credentials or a path.");
  }
  return origin;
}

export default {
  async fetch(request, env) {
    let origin;
    try {
      origin = resolveOrigin(env);
    } catch {
      return new Response("Cloudflare Vibes origin is not configured safely.", {
        status: 503,
        headers: { "Cache-Control": "no-store", "Content-Type": "text/plain; charset=utf-8" },
      });
    }

    const incoming = new URL(request.url);
    const target = new URL(`${incoming.pathname}${incoming.search}`, origin);

    // Preserve method, body, authorization and WebSocket upgrade headers while
    // replacing only the destination host. Redirects remain manual so a bearer
    // credential is never forwarded to an untrusted redirect destination.
    const routed = new Request(target.toString(), request);
    const headers = new Headers(routed.headers);
    headers.set("X-Forwarded-Host", incoming.host);
    headers.set("X-Forwarded-Proto", "https");
    headers.set("X-Vibes-Edge", "cloudflare-worker");

    return fetch(new Request(routed, { headers, redirect: "manual" }));
  },
};
