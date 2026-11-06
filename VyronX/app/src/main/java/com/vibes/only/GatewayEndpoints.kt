package com.vibes.only

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicReference

/** Validated public API roots. Only HTTPS endpoints are allowed in the production pool. */
internal object GatewayEndpointPool {
    const val LEGACY_DUCKDNS = "https://vibesquantum.duckdns.org"
    private const val DEFAULT_RENDER = "https://vibes-quantum-api.onrender.com"

    fun parsePublicEndpoint(raw: String): HttpUrl? {
        val value = raw.trim().trimEnd('/')
        if (value.isBlank() || value.length > 300) return null
        val url = value.toHttpUrlOrNull() ?: return null
        if (url.scheme != "https") return null
        if (url.username.isNotEmpty() || url.password.isNotEmpty() || url.query != null || url.fragment != null) return null
        if (url.encodedPath.isNotEmpty() && url.encodedPath != "/") return null
        return url.newBuilder().encodedPath("/").build()
    }

    fun defaultPrimary(): String = parsePublicEndpoint(BuildConfig.VIBES_RENDER_URL)?.toString()?.trimEnd('/')
        ?: DEFAULT_RENDER

    fun candidates(settings: SettingsStore): List<HttpUrl> {
        val savedPrimary = settings.data.optString("vibes_gateway_url", "")
        val values = buildList {
            add(savedPrimary)
            add(BuildConfig.VIBES_RENDER_URL)
            add(BuildConfig.VIBES_CLOUDFLARE_URL)
            add(LEGACY_DUCKDNS)
            add("https://api.vibesquantum.tech")
            add("https://vibesquantum.tech")
            add("https://api.vibesquantum.com")
            add("https://vibesquantum.com")
            addAll(BuildConfig.VIBES_EXTRA_GATEWAY_URLS.split(','))
        }
        return values.mapNotNull(::parsePublicEndpoint)
            .distinctBy { "${it.scheme}://${it.host}:${it.port}" }
    }
}

/**
 * Tries known Vibes ingress hosts in order when a request cannot reach its host.
 * GET/HEAD may retry selected gateway errors. Writes retry only for DNS/socket connection
 * establishment failures or explicit Cloudflare 521-524 origin-unavailable responses, where
 * the origin did not accept the request. Other write failures are never replayed.
 * Authorization is forwarded only across the configured Vibes endpoint pool.
 */
internal class GatewayFailoverInterceptor(private val settings: SettingsStore) : Interceptor {
    private val lastGoodHost = AtomicReference<String?>(null)
    private val retryableStatuses = setOf(502, 503, 504, 521, 522, 523, 524)

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val safeRead = request.method == "GET" || request.method == "HEAD"
        val original = request.url
        if (original.host in setOf("localhost", "127.0.0.1", "10.0.2.2")) return chain.proceed(request)

        val candidates = GatewayEndpointPool.candidates(settings)
        if (candidates.none { it.host.equals(original.host, ignoreCase = true) && it.port == original.port }) {
            return chain.proceed(request)
        }

        val ordered = mutableListOf(original)
        val remembered = lastGoodHost.get()
        if (!remembered.isNullOrBlank()) {
            candidates.firstOrNull { it.host == remembered && it.host != original.host }?.let(ordered::add)
        }
        candidates.forEach { candidate ->
            if (candidate.host != original.host && ordered.none { existing -> existing.host == candidate.host }) {
                ordered.add(candidate)
            }
        }

        var lastFailure: IOException? = null
        ordered.forEachIndexed { index, endpoint ->
            val routedUrl = original.newBuilder()
                .scheme(endpoint.scheme)
                .host(endpoint.host)
                .port(endpoint.port)
                .build()
            try {
                val response = chain.proceed(request.newBuilder().url(routedUrl).build())
                val retryResponse = index < ordered.lastIndex &&
                    ((safeRead && response.code in retryableStatuses) || response.code in 521..524)
                if (retryResponse) {
                    response.close()
                } else {
                    lastGoodHost.set(endpoint.host)
                    return response
                }
            } catch (failure: IOException) {
                lastFailure = failure
                val connectionWasNeverEstablished = generateSequence<Throwable>(failure) { it.cause }
                    .any { it is UnknownHostException || it is ConnectException || it is NoRouteToHostException }
                if (index == ordered.lastIndex || (!safeRead && !connectionWasNeverEstablished)) throw failure
            }
        }
        throw lastFailure ?: IOException("No Vibes gateway endpoint was reachable.")
    }
}
