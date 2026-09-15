package dev.ide.store.impl

import dev.ide.platform.JsonReader
import dev.ide.store.StoreAccount
import dev.ide.store.StoreAccountService
import dev.ide.store.StoreAuthChallenge
import dev.ide.store.StoreProvider
import dev.ide.store.StoreResult
import dev.ide.store.impl.platform.StoreHttp
import dev.ide.store.impl.platform.StoreLock
import dev.ide.store.impl.platform.base64UrlDecode
import dev.ide.store.impl.platform.nowMillis
import dev.ide.store.impl.platform.urlDecode
import dev.ide.store.impl.platform.urlEncode

/**
 * Where the session lives between launches.
 *
 * Deliberately an interface rather than a file path: the tokens are credentials, and on Android the host
 * can put them somewhere better than a properties file (EncryptedSharedPreferences / the keystore) without
 * this module knowing. The default implementation is in-memory only, which means a desktop build that
 * wires nothing forgets the sign-in on exit — safer than silently writing a refresh token to disk.
 */
interface StoreTokenStore {
    fun read(): String?
    fun write(refreshToken: String?)

    companion object {
        fun inMemory(): StoreTokenStore = object : StoreTokenStore {
            private var token: String? = null
            override fun read(): String? = token
            override fun write(refreshToken: String?) { token = refreshToken }
        }
    }
}

/**
 * Sign-in against Supabase Auth, GitHub and Google only.
 *
 * The flow is the standard OAuth redirect dance, which cannot complete in one call:
 *
 *  1. [begin] builds `/auth/v1/authorize?provider=…&redirect_to=…`; the host opens it in a browser or
 *     custom tab. Nothing is stored yet.
 *  2. The provider sends the user to Supabase, which redirects to [redirectUrl] carrying either a `code`
 *     (PKCE) or the tokens directly in the URL **fragment** (implicit).
 *  3. [complete] is handed that whole URL and picks it apart.
 *
 * Both shapes are handled because which one arrives depends on the project's auth settings, and getting
 * that wrong would be a sign-in that works in testing and fails in production.
 *
 * The access token is kept in memory and never persisted; only the refresh token goes to
 * [StoreTokenStore], because an access token is short-lived and a leaked one on disk buys an attacker
 * nothing it could not get by reading the refresh token anyway.
 *
 * Short-lived is the operative word: Supabase issues an access token good for an hour, so a session has
 * to be kept alive rather than merely restored. [bearer] trades the refresh token in before the one in
 * hand expires, and [reauthorize] does it again for a call that was refused anyway, which is what a
 * clock a minute out of step or a token revoked mid-session looks like from here.
 */
class SupabaseAccountService(
    url: String,
    private val apiKey: String,
    /** The URL the provider redirects back to. Must be registered in the Supabase dashboard. */
    private val redirectUrl: String,
    private val tokens: StoreTokenStore = StoreTokenStore.inMemory(),
    /**
     * Which providers have an OAuth app on the Supabase project. Only these are offered, because a
     * provider that is not configured there answers the authorize call with an error page.
     */
    private val enabledProviders: List<dev.ide.store.StoreProvider> =
        listOf(dev.ide.store.StoreProvider.GITHUB),
    connectTimeoutMs: Int = 10_000,
    readTimeoutMs: Int = 20_000,
) : StoreAccountService {

    private val http = StoreHttp(connectTimeoutMs, readTimeoutMs)

    override fun providers(): List<dev.ide.store.StoreProvider> =
        if (configured) enabledProviders else emptyList()

    private val base = url.trimEnd('/')
    private val configured = url.isNotBlank() && apiKey.isNotBlank() && redirectUrl.isNotBlank()

    /** The live session. Access tokens are memory-only by design. */
    private var accessToken: String? = null
    private var account: StoreAccount? = null

    /**
     * When [accessToken] stops being accepted, as epoch milliseconds, or 0 when it carried no readable
     * expiry.
     *
     * Without it the first token a session was given was held for as long as the process lived, and every
     * authenticated call an hour in came back 401: the screens behind sign-in then read as unreachable
     * until the app was restarted, which is the one thing that did restore a session.
     */
    private var accessExpiresAt: Long = 0L

    /**
     * Serialises refreshes.
     *
     * Refresh tokens rotate, so two calls that both notice the session is spent must not each spend it.
     * The loser of that race is handed "Invalid Refresh Token: Already Used" and would otherwise sign the
     * user out over timing alone.
     */
    private val sessionLock = StoreLock()

    override fun authAvailable(): Boolean = configured

    // Cold start with a stored refresh token: the exchange for a session happens here, without user
    // interaction. This is the network call the port's doc warns about; the caller keeps it off the
    // startup path.
    override fun current(): StoreAccount? = sessionLock.withLock {
        account ?: tokens.read()?.takeIf { it.isNotBlank() }
            ?.let { (refreshSession(it) as? StoreResult.Ok)?.value }
    }

    /** Live session, or a refresh token to trade for one. Reads the token store; never the network. */
    override fun hasStoredSession(): Boolean =
        account != null || !tokens.read().isNullOrBlank()

    override fun begin(provider: StoreProvider): StoreResult<StoreAuthChallenge> {
        if (!configured) return StoreResult.Unavailable("Sign-in is not configured in this build")
        val authorize = "$base/auth/v1/authorize" +
            "?provider=${enc(provider.wire)}" +
            "&redirect_to=${enc(redirectUrl)}"
        return StoreResult.Ok(StoreAuthChallenge(authorizeUrl = authorize, redirectUrl = redirectUrl))
    }

    override fun complete(redirect: String): StoreResult<StoreAccount> {
        if (!configured) return StoreResult.Unavailable("Sign-in is not configured in this build")
        val params = redirectParams(redirect)
        params["error_description"]?.let { return StoreResult.Failed(explain(it)) }
        params["error"]?.let { return StoreResult.Failed(explain(it)) }

        // Implicit flow: the tokens are already in the fragment, so there is nothing to exchange.
        val direct = params["access_token"]
        if (direct != null) {
            return adopt(
                direct,
                params["refresh_token"],
                expiresInSeconds = params["expires_in"]?.toLongOrNull() ?: 0L,
            )
        }
        // PKCE flow: swap the one-time code for a session.
        val code = params["code"]
            ?: return StoreResult.Failed("Sign-in response carried neither a code nor a token")
        return when (val r = post("/auth/v1/token?grant_type=pkce", """{"auth_code":${SupabaseStoreSource.jsonStr(code)}}""")) {
            is StoreResult.Ok -> adoptFromSession(r.value)
            is StoreResult.Unavailable -> StoreResult.Unavailable(r.reason)
            is StoreResult.Failed -> StoreResult.Failed(r.message, r.status)
        }
    }

    override fun signOut() {
        val token = sessionLock.withLock {
            val live = accessToken
            forgetSession()
            live
        }
        // Best effort: revoke server-side too, but a failure must not leave the client thinking it is
        // still signed in — the local state is already cleared above.
        if (configured && token != null) runCatching { post("/auth/v1/logout", "{}", token) }
    }

    /** Exchange a stored refresh token for a live session. */
    private fun refreshSession(refresh: String): StoreResult<StoreAccount> =
        when (val r = post("/auth/v1/token?grant_type=refresh_token", """{"refresh_token":${SupabaseStoreSource.jsonStr(refresh)}}""")) {
            is StoreResult.Ok -> adoptFromSession(r.value)
            is StoreResult.Unavailable -> StoreResult.Unavailable(r.reason)
            is StoreResult.Failed -> {
                // Only a refusal of the token itself is permanent, and then it is dropped so nothing
                // retries it on every launch. A malformed request, a proxy in the way or a provider
                // having a bad minute leaves a session that still works, and dropping the token for one
                // of those signs the user out with no way back but the browser.
                if (refusedTheToken(r.message, r.status)) forgetSession()
                StoreResult.Failed(r.message, r.status)
            }
        }

    /**
     * Whether a refused refresh means the stored token is dead, rather than the exchange having gone
     * wrong around it.
     *
     * GoTrue answers an unusable refresh token with `invalid_grant` and an `error_description` that names
     * it ("Invalid Refresh Token: Refresh Token Not Found", "Invalid Refresh Token: Already Used"), and a
     * 401 from this endpoint can mean nothing else.
     */
    private fun refusedTheToken(message: String, status: Int): Boolean {
        if (status == 401) return true
        val text = message.lowercase()
        return "invalid_grant" in text || "refresh token" in text || "refresh_token" in text
    }

    /** Drop the session, live and stored, so nothing keeps presenting a credential the server refuses. */
    private fun forgetSession() {
        accessToken = null
        accessExpiresAt = 0L
        account = null
        hints = null
        tokens.write(null)
    }

    private fun adoptFromSession(body: String): StoreResult<StoreAccount> {
        val json = JsonReader.parseOrNull(body) ?: return StoreResult.Failed("Auth response was not valid JSON")
        val access = JsonReader.str(json, "access_token")
            ?: return StoreResult.Failed(JsonReader.str(json, "msg") ?: "Auth response carried no access token")
        return adopt(
            access,
            JsonReader.str(json, "refresh_token"),
            JsonReader.obj(json)?.get("user"),
            JsonReader.long(json, "expires_in"),
        )
    }

    private fun adopt(
        access: String,
        refresh: String?,
        userJson: Any? = null,
        expiresInSeconds: Long = 0L,
    ): StoreResult<StoreAccount> {
        accessToken = access
        accessExpiresAt = expiryOf(access, expiresInSeconds)
        if (refresh != null) tokens.write(refresh)
        val user = userJson ?: fetchUser(access)
        val userId = JsonReader.str(user, "id")
            ?: return StoreResult.Failed("Signed in but the account has no id")
        val metadata = JsonReader.obj(user)?.get("user_metadata")
        // The publisher row is created on first sight of the account, from these, so the identity the
        // provider already knows is not thrown away and asked for again.
        hints = ProviderIdentity(
            handle = listOf("user_name", "preferred_username", "nickname")
                .firstNotNullOfOrNull { JsonReader.str(metadata, it) }
                // An email local part is a reasonable handle and is all Google offers.
                ?: JsonReader.str(user, "email")?.substringBefore('@'),
            name = listOf("full_name", "name")
                .firstNotNullOfOrNull { JsonReader.str(metadata, it) },
            avatarUrl = JsonReader.str(metadata, "avatar_url")
                ?: JsonReader.str(metadata, "picture"),
        )
        val resolved = StoreAccount(
            userId = userId,
            email = JsonReader.str(user, "email"),
            avatarUrl = hints?.avatarUrl,
        )
        account = resolved
        return StoreResult.Ok(resolved)
    }

    /**
     * Bind this device's push token to the session.
     *
     * The definer RPC takes the account from the JWT, so the client never asserts whose device this is,
     * and the token is unguessable, which is what makes updating a row by it safe with no select
     * privilege on the table. Signed out there is nothing to bind to, which is not an error: this is
     * called on every launch and most launches are anonymous.
     */
    override fun bindPushDevice(pushToken: String): StoreResult<Boolean> {
        val token = bearer() ?: return StoreResult.Ok(false)
        val body = """{"p_token":${SupabaseStoreSource.jsonStr(pushToken)}}"""
        return when (val r = post("/rest/v1/rpc/store_bind_device", body, token)) {
            // The RPC answers with a bare `true`/`false`: false means no device carries this token yet.
            is StoreResult.Ok -> StoreResult.Ok(r.value.trim().equals("true", ignoreCase = true))
            is StoreResult.Unavailable -> StoreResult.Unavailable(r.reason)
            is StoreResult.Failed -> StoreResult.Failed(r.message, r.status)
        }
    }

    /**
     * What the identity provider says about the signed-in user.
     *
     * Read by the submission service to seed a publisher row. Held here because this is the only thing
     * that sees the provider's profile: it arrives with the session and is not fetched again.
     */
    internal data class ProviderIdentity(
        val handle: String? = null,
        val name: String? = null,
        val avatarUrl: String? = null,
    )

    private var hints: ProviderIdentity? = null

    internal fun providerIdentity(): ProviderIdentity? = hints

    private fun fetchUser(access: String): Any? =
        (get("/auth/v1/user", access) as? StoreResult.Ok)?.value?.let { JsonReader.parseOrNull(it) }

    /**
     * The access token for an authenticated call, refreshing first if the session is cold or spent.
     *
     * Exposed for the submission service, which needs to POST as the signed-in user.
     *
     * What comes back is whatever the exchange left behind: a fresh token, the one already in hand if the
     * exchange could not be made at all, or null if the refresh token itself was refused. The middle case
     * is deliberate. A token that may still be accepted is worth sending, and being refused by the server
     * reads better than being refused locally over a refresh that never reached it.
     */
    internal fun bearer(): String? = sessionLock.withLock {
        val live = accessToken
        if (live != null && !spent()) return@withLock live
        val refresh = tokens.read()?.takeIf { it.isNotBlank() } ?: return@withLock live
        refreshSession(refresh)
        accessToken
    }

    /**
     * Trade the refresh token in after a call was refused with 401, and hand back what to retry with.
     *
     * [stale] is the token that was refused. When it is no longer the live one another call has already
     * refreshed, and that token has not been tried yet, so it is handed back rather than spending a
     * second refresh on it. Null means there is nothing left to retry with.
     */
    internal fun reauthorize(stale: String?): String? = sessionLock.withLock {
        val live = accessToken
        if (live != null && stale != null && live != stale) return@withLock live
        val refresh = tokens.read()?.takeIf { it.isNotBlank() } ?: return@withLock null
        if (refreshSession(refresh) is StoreResult.Ok) accessToken else null
    }

    /**
     * Whether the access token is too close to expiry to start a call with.
     *
     * [EXPIRY_SKEW_MS] early, because the call still has to travel and the server compares against its
     * own clock. A token whose expiry could not be read is never spent, and the retry after a 401 is what
     * covers it.
     */
    private fun spent(): Boolean {
        val at = accessExpiresAt
        return at != 0L && nowMillis() >= at - EXPIRY_SKEW_MS
    }

    /**
     * When [access] stops being accepted, as epoch milliseconds, or 0 when that cannot be established.
     *
     * The token's own `exp` claim is preferred over the session's `expires_in`: it is what the server
     * compares against, and it is present on every path a token arrives by, including the implicit
     * redirect whose whole session document is a handful of URL parameters.
     */
    private fun expiryOf(access: String, expiresInSeconds: Long): Long =
        jwtExpiry(access)
            ?: if (expiresInSeconds > 0) nowMillis() + expiresInSeconds * 1000 else 0L

    // ---- HTTP ----

    private fun post(path: String, body: String, token: String? = null): StoreResult<String> =
        request("POST", path, body, token)

    private fun get(path: String, token: String? = null): StoreResult<String> =
        request("GET", path, null, token)

    private fun request(method: String, path: String, body: String?, token: String?): StoreResult<String> {
        if (!configured) return StoreResult.Unavailable("Sign-in is not configured in this build")
        val headers = buildMap {
            put("apikey", apiKey)
            // The anon key authorizes the endpoint; a user token, when present, identifies the caller.
            put("Authorization", "Bearer ${token ?: apiKey}")
            if (body != null) put("Content-Type", "application/json")
        }
        val r = http.request(method, "$base$path", headers, body)
        return when {
            r.error != null -> StoreResult.Unavailable(r.error)
            r.status in 200..299 -> StoreResult.Ok(r.body)
            r.status == 429 || r.status >= 500 ->
                StoreResult.Unavailable("Sign-in service unavailable (HTTP ${r.status})")
            else -> StoreResult.Failed(authError(r.body) ?: "Sign-in failed", r.status)
        }
    }

    /**
     * Turn a provider failure into something the person reading it can act on.
     *
     * The raw text is kept where it is already clear. The one case worth translating is the provider
     * refusing to hand over an email: GoTrue reports it as "Error getting user profile from external
     * provider", which reads like the user did something wrong when it is entirely a configuration
     * problem — a GitHub App that has not been granted the "Email addresses" account permission, whose
     * token then gets a 403 "Resource not accessible by integration" on `/user/emails`. Sign-in cannot
     * succeed without an email, so there is nothing for the user to retry.
     */
    private fun explain(raw: String): String {
        val lower = raw.lowercase()
        val providerRefusedIdentity = "external provider" in lower &&
            ("profile" in lower || "email" in lower)
        return if (providerRefusedIdentity) {
            "GitHub did not share an email address, so sign-in could not complete. " +
                "This is a configuration problem with the app, not with your account."
        } else {
            raw
        }
    }

    /** GoTrue reports errors as `msg`, `error_description` or `message` depending on the endpoint. */
    private fun authError(body: String): String? {
        val j = JsonReader.parseOrNull(body) ?: return null
        return listOf("error_description", "msg", "message")
            .firstNotNullOfOrNull { JsonReader.str(j, it)?.takeIf { s -> s.isNotBlank() } }
    }

    companion object {
        /**
         * How early a token is treated as spent, covering the call's own flight time and a client clock
         * that does not agree with the server's.
         */
        private const val EXPIRY_SKEW_MS = 60_000L

        /**
         * The `exp` claim of a JWT, as epoch milliseconds, or null for anything that is not a readable
         * JWT.
         *
         * The payload is base64url and unpadded, which the decoder accepts; anything else about the token
         * is none of this client's business, and in particular the signature is not checked here because
         * the only thing being decided is when to ask for a new one.
         */
        internal fun jwtExpiry(token: String): Long? {
            val payload = token.split('.').getOrNull(1)?.takeIf { it.isNotBlank() } ?: return null
            val json = base64UrlDecode(payload)?.decodeToString() ?: return null
            val seconds = JsonReader.parseOrNull(json)?.let { JsonReader.long(it, "exp") } ?: return null
            return if (seconds > 0) seconds * 1000L else null
        }

        private fun enc(s: String): String = urlEncode(s)

        /**
         * Flatten a redirect URL's query AND fragment into one map.
         *
         * Both halves matter: the PKCE flow puts `code` in the query, the implicit flow puts
         * `access_token` in the fragment. A reader that only looked at one would work against one
         * project's settings and fail against another's.
         */
        internal fun redirectParams(redirect: String): Map<String, String> {
            val out = LinkedHashMap<String, String>()
            val afterScheme = redirect.substringAfter("://", redirect)
            val query = afterScheme.substringAfter('?', "").substringBefore('#')
            val fragment = afterScheme.substringAfter('#', "")
            for (part in listOf(query, fragment)) {
                if (part.isBlank()) continue
                for (pair in part.split('&')) {
                    if (pair.isBlank()) continue
                    val k = pair.substringBefore('=')
                    val v = pair.substringAfter('=', "")
                    if (k.isNotBlank()) out[decode(k)] = decode(v)
                }
            }
            return out
        }

        private fun decode(s: String): String = urlDecode(s)
    }
}
