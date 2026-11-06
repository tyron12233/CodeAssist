package dev.ide.agent.mcp.client

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit

/** A sign-in's result, kept so later requests carry the token and an expired one can be refreshed. */
data class McpTokens(
    val accessToken: String,
    val refreshToken: String? = null,
    /** Epoch millis; null when the server did not say. */
    val expiresAtMs: Long? = null,
    val tokenEndpoint: String,
    val clientId: String,
    val clientSecret: String? = null,
    /** The resource the token was issued for (the server's URL), sent again on a refresh. */
    val resource: String? = null,
) {
    fun expired(nowMs: Long = System.currentTimeMillis()): Boolean = expiresAtMs != null && nowMs >= expiresAtMs - 60_000

    fun toJson(): JsonObject = buildJsonObject {
        put("accessToken", accessToken)
        refreshToken?.let { put("refreshToken", it) }
        expiresAtMs?.let { put("expiresAtMs", it) }
        put("tokenEndpoint", tokenEndpoint)
        put("clientId", clientId)
        clientSecret?.let { put("clientSecret", it) }
        resource?.let { put("resource", it) }
    }

    companion object {
        fun fromJson(o: JsonObject): McpTokens? {
            val access = o.str("accessToken") ?: return null
            return McpTokens(
                accessToken = access,
                refreshToken = o.str("refreshToken"),
                expiresAtMs = (o["expiresAtMs"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull(),
                tokenEndpoint = o.str("tokenEndpoint") ?: return null,
                clientId = o.str("clientId") ?: return null,
                clientSecret = o.str("clientSecret"),
                resource = o.str("resource"),
            )
        }
    }
}

/**
 * MCP's authorization flow (OAuth 2.1, per the protocol's authorization spec):
 *
 * 1. The server's 401 names its protected-resource metadata (RFC 9728), which names its authorization server.
 *    Without it (older servers) the authorization server is assumed to be the MCP server's own origin.
 * 2. The authorization server's metadata (RFC 8414, or OpenID discovery) gives the endpoints.
 * 3. The app registers itself as a public client (dynamic client registration, RFC 7591) for this sign-in's
 *    redirect URI: a loopback port is different every time, and servers match the redirect URI exactly.
 * 4. The browser does an authorization-code grant with PKCE (S256) and the RFC 8707 `resource` parameter.
 * 5. The code is exchanged for tokens; [refresh] renews them later.
 *
 * A server whose authorization server cannot register clients dynamically cannot be signed into this way; the
 * user can still add a token as a header.
 */
object McpOAuth {
    private val JSON = Json { ignoreUnknownKeys = true; isLenient = true }
    private val JSON_TYPE = "application/json".toMediaType()
    private val random = SecureRandom()

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    }

    /** Everything one sign-in needs between opening the browser and the redirect coming back. */
    data class Attempt(
        val authorizeUrl: String,
        val state: String,
        val verifier: String,
        val redirectUri: String,
        val tokenEndpoint: String,
        val clientId: String,
        val clientSecret: String?,
        val resource: String,
    )

    class OAuthException(message: String) : IOException(message)

    /**
     * Discovers the server's authorization setup, registers a client for [redirectUri], and builds the URL to open
     * in the browser. [wwwAuthenticate] is the challenge from the server's 401, when there was one.
     */
    suspend fun begin(serverUrl: String, redirectUri: String, wwwAuthenticate: String?, clientName: String = "CodeAssist"): Attempt =
        withContext(Dispatchers.IO) {
            val resource = canonicalResource(serverUrl)
            val challenge = parseChallenge(wwwAuthenticate)
            val prm = protectedResourceMetadata(serverUrl, challenge["resource_metadata"])
            val issuer = (prm?.get("authorization_servers") as? JsonArray)?.firstOrNull()?.let { (it as? JsonPrimitive)?.contentOrNull }
                ?: origin(serverUrl)
            val asMeta = authorizationServerMetadata(issuer)
            val authorize = asMeta?.str("authorization_endpoint") ?: "${origin(issuer)}/authorize"
            val token = asMeta?.str("token_endpoint") ?: "${origin(issuer)}/token"
            val register = asMeta?.str("registration_endpoint") ?: if (asMeta == null) "${origin(issuer)}/register" else null
            if (register == null) {
                throw OAuthException(
                    "This server's sign-in does not let apps register themselves. Add a token in the server's headers instead " +
                        "(for example Authorization: Bearer <token>).",
                )
            }
            val methods = (asMeta?.get("code_challenge_methods_supported") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            if (methods != null && "S256" !in methods) throw OAuthException("This server's sign-in does not support PKCE, which CodeAssist requires.")

            val (clientId, clientSecret) = registerClient(register, redirectUri, clientName)
            val scope = challenge["scope"]
                ?: (prm?.get("scopes_supported") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.joinToString(" ")?.takeIf { it.isNotBlank() }
            val verifier = randomToken(48)
            val state = randomToken(24)
            val url = authorize.toHttpUrlOrNull()?.newBuilder()
                ?.addQueryParameter("response_type", "code")
                ?.addQueryParameter("client_id", clientId)
                ?.addQueryParameter("redirect_uri", redirectUri)
                ?.addQueryParameter("code_challenge", challengeFor(verifier))
                ?.addQueryParameter("code_challenge_method", "S256")
                ?.addQueryParameter("state", state)
                ?.addQueryParameter("resource", resource)
                ?.apply { if (scope != null) addQueryParameter("scope", scope) }
                ?.build()?.toString()
                ?: throw OAuthException("The server named an invalid sign-in address: $authorize")
            Attempt(url, state, verifier, redirectUri, token, clientId, clientSecret, resource)
        }

    /** Trades the redirect's [code] for tokens. */
    suspend fun exchange(attempt: Attempt, code: String): McpTokens = withContext(Dispatchers.IO) {
        val form = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("redirect_uri", attempt.redirectUri)
            .add("client_id", attempt.clientId)
            .add("code_verifier", attempt.verifier)
            .add("resource", attempt.resource)
            .apply { attempt.clientSecret?.let { add("client_secret", it) } }
            .build()
        tokenRequest(attempt.tokenEndpoint, form, attempt.clientId, attempt.clientSecret, attempt.resource, previousRefresh = null)
    }

    /** Renews [tokens] with their refresh token; throws when there is none or the server refuses. */
    suspend fun refresh(tokens: McpTokens): McpTokens = withContext(Dispatchers.IO) {
        val refreshToken = tokens.refreshToken ?: throw OAuthException("The sign-in expired. Sign in again.")
        val form = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", refreshToken)
            .add("client_id", tokens.clientId)
            .apply {
                tokens.resource?.let { add("resource", it) }
                tokens.clientSecret?.let { add("client_secret", it) }
            }
            .build()
        tokenRequest(tokens.tokenEndpoint, form, tokens.clientId, tokens.clientSecret, tokens.resource, previousRefresh = refreshToken)
    }

    private fun tokenRequest(
        endpoint: String,
        form: FormBody,
        clientId: String,
        clientSecret: String?,
        resource: String?,
        previousRefresh: String?,
    ): McpTokens {
        val request = Request.Builder().url(endpoint).post(form).header("Accept", "application/json").build()
        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            val obj = runCatching { JSON.parseToJsonElement(text) as? JsonObject }.getOrNull()
            if (!response.isSuccessful || obj == null) {
                val reason = obj?.str("error_description") ?: obj?.str("error") ?: "HTTP ${response.code}"
                throw OAuthException("The server did not issue a token: $reason")
            }
            val access = obj.str("access_token") ?: throw OAuthException("The server did not issue a token.")
            val expiresIn = (obj["expires_in"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
            return McpTokens(
                accessToken = access,
                // A refresh may or may not rotate the refresh token; the old one stands when no new one comes.
                refreshToken = obj.str("refresh_token") ?: previousRefresh,
                expiresAtMs = expiresIn?.let { System.currentTimeMillis() + it * 1000 },
                tokenEndpoint = endpoint,
                clientId = clientId,
                clientSecret = clientSecret,
                resource = resource,
            )
        }
    }

    private fun registerClient(endpoint: String, redirectUri: String, clientName: String): Pair<String, String?> {
        val body = buildJsonObject {
            put("client_name", clientName)
            putJsonArray("redirect_uris") { add(JsonPrimitive(redirectUri)) }
            putJsonArray("grant_types") { add(JsonPrimitive("authorization_code")); add(JsonPrimitive("refresh_token")) }
            putJsonArray("response_types") { add(JsonPrimitive("code")) }
            put("token_endpoint_auth_method", "none")
        }
        val request = Request.Builder().url(endpoint).post(body.toString().toRequestBody(JSON_TYPE)).header("Accept", "application/json").build()
        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            val obj = runCatching { JSON.parseToJsonElement(text) as? JsonObject }.getOrNull()
            if (!response.isSuccessful || obj == null) {
                val reason = obj?.str("error_description") ?: obj?.str("error") ?: "HTTP ${response.code}"
                throw OAuthException("The server would not register CodeAssist for sign-in: $reason")
            }
            val id = obj.str("client_id") ?: throw OAuthException("The server registered CodeAssist without a client id.")
            return id to obj.str("client_secret")
        }
    }

    /** RFC 9728: the URL from the challenge, else the well-known locations (path-specific first). */
    private fun protectedResourceMetadata(serverUrl: String, fromChallenge: String?): JsonObject? {
        val url = serverUrl.toHttpUrl()
        val path = url.encodedPath.trimEnd('/')
        val candidates = listOfNotNull(
            fromChallenge,
            if (path.isNotEmpty()) "${origin(serverUrl)}/.well-known/oauth-protected-resource$path" else null,
            "${origin(serverUrl)}/.well-known/oauth-protected-resource",
        )
        return candidates.firstNotNullOfOrNull { getJson(it) }
    }

    /** RFC 8414 then OpenID discovery, each in the path-inserted and path-appended forms. */
    private fun authorizationServerMetadata(issuer: String): JsonObject? {
        val url = issuer.toHttpUrlOrNull() ?: return null
        val path = url.encodedPath.trimEnd('/')
        val base = origin(issuer)
        val candidates = if (path.isEmpty()) {
            listOf("$base/.well-known/oauth-authorization-server", "$base/.well-known/openid-configuration")
        } else {
            listOf(
                "$base/.well-known/oauth-authorization-server$path",
                "$base/.well-known/openid-configuration$path",
                "$base$path/.well-known/openid-configuration",
            )
        }
        return candidates.firstNotNullOfOrNull { getJson(it) }
    }

    private fun getJson(url: String): JsonObject? = runCatching {
        val request = Request.Builder().url(url).get().header("Accept", "application/json")
            .header("MCP-Protocol-Version", McpClient.LATEST_PROTOCOL_VERSION).build()
        http.newCall(request).execute().use { r ->
            if (!r.isSuccessful) null else JSON.parseToJsonElement(r.body?.string().orEmpty()) as? JsonObject
        }
    }.getOrNull()

    /** `Bearer realm="x", resource_metadata="https://…", scope="a b"` to its parameters. */
    internal fun parseChallenge(header: String?): Map<String, String> {
        if (header.isNullOrBlank()) return emptyMap()
        val params = header.substringAfter(' ', "")
        return Regex("""([A-Za-z_][A-Za-z0-9_-]*)\s*=\s*(?:"((?:[^"\\]|\\.)*)"|([^\s,]+))""").findAll(params)
            .associate { m -> m.groupValues[1].lowercase() to (m.groupValues[2].ifEmpty { m.groupValues[3] }).replace("\\\"", "\"") }
    }

    /** The server URL as the token's audience: lowercase scheme and host, no fragment, no trailing slash. */
    internal fun canonicalResource(serverUrl: String): String {
        val url: HttpUrl = serverUrl.toHttpUrl()
        return url.newBuilder().fragment(null).build().toString().trimEnd('/')
    }

    private fun origin(url: String): String {
        val u = url.toHttpUrl()
        val defaultPort = HttpUrl.defaultPort(u.scheme)
        return "${u.scheme}://${u.host}" + if (u.port != defaultPort) ":${u.port}" else ""
    }

    private fun randomToken(bytes: Int): String {
        val b = ByteArray(bytes)
        random.nextBytes(b)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b)
    }

    internal fun challengeFor(verifier: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
}
