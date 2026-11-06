package com.vibes.quantum.server

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Instant
import java.util.Base64

internal data class VerifiedPlaySubscription(val productId: String, val tier: String, val expiryMillis: Long)

/** Verifies purchases against Google Play Developer API using a server-side service account. */
internal class PlayBillingVerifier(
    private val packageName: String = System.getenv("VIBES_PLAY_PACKAGE_NAME")?.trim().orEmpty(),
    private val serviceAccountJson: String = System.getenv("VIBES_PLAY_SERVICE_ACCOUNT_JSON") ?: "",
    private val productTiers: Map<String, String> = parseTierMap(System.getenv("VIBES_PLAY_PRODUCT_TIERS")),
) {
    private val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()
    @Volatile private var cachedAccessToken: String? = null
    @Volatile private var cachedAccessTokenExpiry: Long = 0

    fun verify(purchaseToken: String, requestedProductId: String, requestedPackageName: String): VerifiedPlaySubscription {
        require(packageName.isNotBlank() && requestedPackageName == packageName) { "The Play package name is not configured or does not match this app" }
        require(purchaseToken.length in 20..4096 && purchaseToken.none(Char::isWhitespace)) { "Invalid purchase token" }
        require(requestedProductId.matches(Regex("[A-Za-z0-9._-]{1,160}"))) { "Invalid product identifier" }
        val tier = productTiers[requestedProductId] ?: error("This Google Play product is not mapped to a server entitlement")
        val token = accessToken()
        val url = "https://androidpublisher.googleapis.com/androidpublisher/v3/applications/${enc(packageName)}/purchases/subscriptionsv2/tokens/${enc(purchaseToken)}"
        val request = HttpRequest.newBuilder(URI(url)).timeout(java.time.Duration.ofSeconds(25))
            .header("Authorization", "Bearer $token").header("Accept", "application/json").GET().build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) throw IllegalStateException("Google Play purchase verification failed (HTTP ${response.statusCode()})")
        val root = Json.parseToJsonElement(response.body()).jsonObject
        val state = root["subscriptionState"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (state !in setOf("SUBSCRIPTION_STATE_ACTIVE", "SUBSCRIPTION_STATE_IN_GRACE_PERIOD")) {
            throw IllegalStateException("Google Play subscription is not active")
        }
        val matching = root["lineItems"]?.let { runCatching { it.jsonArray }.getOrNull() }.orEmpty()
            .mapNotNull { runCatching { it.jsonObject }.getOrNull() }
            .firstOrNull { it["productId"]?.jsonPrimitive?.contentOrNull == requestedProductId }
            ?: throw IllegalStateException("Google Play response does not contain the requested product")
        val expiryText = matching["expiryTime"]?.jsonPrimitive?.contentOrNull
            ?: throw IllegalStateException("Google Play did not provide a subscription expiry")
        val expiry = Instant.parse(expiryText).toEpochMilli()
        if (expiry <= System.currentTimeMillis()) throw IllegalStateException("Google Play subscription has expired")
        return VerifiedPlaySubscription(requestedProductId, tier, expiry)
    }

    private fun accessToken(): String {
        val now = System.currentTimeMillis()
        cachedAccessToken?.takeIf { cachedAccessTokenExpiry > now + 60_000 }?.let { return it }
        if (serviceAccountJson.isBlank()) throw IllegalStateException("Play Billing verification is not configured: set VIBES_PLAY_SERVICE_ACCOUNT_JSON")
        val account = Json.parseToJsonElement(serviceAccountJson).jsonObject
        val email = account["client_email"]?.jsonPrimitive?.contentOrNull ?: error("Service account client_email is missing")
        val keyPem = account["private_key"]?.jsonPrimitive?.contentOrNull ?: error("Service account private_key is missing")
        val tokenUri = account["token_uri"]?.jsonPrimitive?.contentOrNull ?: "https://oauth2.googleapis.com/token"
        require(URI(tokenUri).scheme == "https" && URI(tokenUri).host == "oauth2.googleapis.com") { "Unsupported service-account token URI" }
        val iat = now / 1000
        val header = b64("{\"alg\":\"RS256\",\"typ\":\"JWT\"}")
        val claims = "{\"iss\":\"${jsonEscape(email)}\",\"scope\":\"https://www.googleapis.com/auth/androidpublisher\",\"aud\":\"${jsonEscape(tokenUri)}\",\"iat\":$iat,\"exp\":${iat + 3600}}"
        val signingInput = "$header.${b64(claims)}"
        val pem = keyPem.replace("-----BEGIN PRIVATE KEY-----", "").replace("-----END PRIVATE KEY-----", "").replace(Regex("\\s"), "")
        val privateKey = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)))
        val signer = Signature.getInstance("SHA256withRSA")
        signer.initSign(privateKey); signer.update(signingInput.toByteArray(StandardCharsets.UTF_8))
        val assertion = "$signingInput.${Base64.getUrlEncoder().withoutPadding().encodeToString(signer.sign())}"
        val form = "grant_type=${enc("urn:ietf:params:oauth:grant-type:jwt-bearer")}&assertion=${enc(assertion)}"
        val request = HttpRequest.newBuilder(URI(tokenUri)).timeout(java.time.Duration.ofSeconds(20))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(form)).build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) throw IllegalStateException("Could not obtain Google Play API access token (HTTP ${response.statusCode()})")
        val token = Json.parseToJsonElement(response.body()).jsonObject["access_token"]?.jsonPrimitive?.contentOrNull
            ?: error("Google OAuth response did not contain an access token")
        cachedAccessToken = token
        cachedAccessTokenExpiry = now + 3_300_000
        return token
    }

    private fun enc(value: String) = URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")
    private fun b64(value: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(StandardCharsets.UTF_8))
    private fun jsonEscape(value: String) = value.replace("\\", "\\\\").replace("\"", "\\\"")

    companion object {
        private fun parseTierMap(raw: String?): Map<String, String> {
            if (raw.isNullOrBlank()) return emptyMap()
            val source = raw.trim()
            val pairs = if (source.startsWith("{")) runCatching {
                Json.parseToJsonElement(source).jsonObject.mapNotNull { (key, value) ->
                    val tier = value.jsonPrimitive.content.uppercase()
                    if (tier in setOf("PRO_LITE", "PRO", "PRO_PLUS")) key to tier else null
                }
            }.getOrDefault(emptyList()) else source.split(',').mapNotNull { part ->
                val bits = part.split('=', limit = 2)
                if (bits.size != 2) null else bits[0].trim() to bits[1].trim().uppercase()
            }.filter { it.second in setOf("PRO_LITE", "PRO", "PRO_PLUS") }
            return pairs.toMap()
        }
    }
}
