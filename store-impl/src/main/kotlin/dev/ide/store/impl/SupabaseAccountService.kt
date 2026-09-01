package dev.ide.store.impl

import dev.ide.platform.JsonReader
import dev.ide.store.StoreAccount
import dev.ide.store.StoreAccountService
import dev.ide.store.StoreAuthChallenge
import dev.ide.store.StoreProvider
import dev.ide.store.StoreResult
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

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
 */
class SupabaseAccountService(
    url: String,
    private val apiKey: String,
    /** The URL the provider redirects back to. Must be registered in the Supabase dashboard. */
    private val redirectUrl: String,
    private val tokens: StoreTokenStore = StoreTokenStore.inMemory(),
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 20_000,
) : StoreAccountService {

    private val base = url.trimEnd('/')
    private val configured = url.isNotBlank() && apiKey.isNotBlank() && redirectUrl.isNotBlank()

    /** The live session. Access tokens are memory-only by design. */
    private var accessToken: String? = null
    private var account: StoreAccount? = null

    override fun authAvailable(): Boolean = configured

    override fun current(): StoreAccount? {
        account?.let { return it }
        // Cold start with a stored refresh token: exchange it for a session without user interaction.
        val refresh = tokens.read()?.takeIf { it.isNotBlank() } ?: return null
        return (refreshSession(refresh) as? StoreResult.Ok)?.value
    }

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
        params["error_description"]?.let { return StoreResult.Failed(it) }
        params["error"]?.let { return StoreResult.Failed(it) }

        // Implicit flow: the tokens are already in the fragment, so there is nothing to exchange.
        val direct = params["access_token"]
        if (direct != null) {
            return adopt(direct, params["refresh_token"])
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
        val token = accessToken
        accessToken = null
        account = null
        tokens.write(null)
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
                // A rejected refresh token is dead; drop it so we stop retrying on every launch.
                tokens.write(null)
                StoreResult.Failed(r.message, r.status)
            }
        }

    private fun adoptFromSession(body: String): StoreResult<StoreAccount> {
        val json = JsonReader.parseOrNull(body) ?: return StoreResult.Failed("Auth response was not valid JSON")
        val access = JsonReader.str(json, "access_token")
            ?: return StoreResult.Failed(JsonReader.str(json, "msg") ?: "Auth response carried no access token")
        return adopt(access, JsonReader.str(json, "refresh_token"), JsonReader.obj(json)?.get("user"))
    }

    private fun adopt(access: String, refresh: String?, userJson: Any? = null): StoreResult<StoreAccount> {
        accessToken = access
        if (refresh != null) tokens.write(refresh)
        val user = userJson ?: fetchUser(access)
        val userId = JsonReader.str(user, "id")
            ?: return StoreResult.Failed("Signed in but the account has no id")
        // The publisher row is created on first submit, so handle/displayName are usually absent here and
        // get filled in by the submission flow rather than invented from the OAuth profile.
        val resolved = StoreAccount(
            userId = userId,
            email = JsonReader.str(user, "email"),
            avatarUrl = JsonReader.obj(user)?.get("user_metadata")
                ?.let { JsonReader.str(it, "avatar_url") },
        )
        account = resolved
        return StoreResult.Ok(resolved)
    }

    private fun fetchUser(access: String): Any? =
        (get("/auth/v1/user", access) as? StoreResult.Ok)?.value?.let { JsonReader.parseOrNull(it) }

    /**
     * The access token for an authenticated call, refreshing first if the session is cold.
     *
     * Exposed for the submission service, which needs to POST as the signed-in user.
     */
    internal fun bearer(): String? {
        accessToken?.let { return it }
        current()
        return accessToken
    }

    // ---- HTTP ----

    private fun post(path: String, body: String, token: String? = null): StoreResult<String> =
        request("POST", path, body, token)

    private fun get(path: String, token: String? = null): StoreResult<String> =
        request("GET", path, null, token)

    private fun request(method: String, path: String, body: String?, token: String?): StoreResult<String> {
        if (!configured) return StoreResult.Unavailable("Sign-in is not configured in this build")
        return try {
            val conn = (URL("$base$path").openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                setRequestProperty("apikey", apiKey)
                // The anon key authorizes the endpoint; a user token, when present, identifies the caller.
                setRequestProperty("Authorization", "Bearer ${token ?: apiKey}")
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                }
            }
            if (body != null) conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            when {
                code in 200..299 -> StoreResult.Ok(text)
                code == 429 || code >= 500 -> StoreResult.Unavailable("Sign-in service unavailable (HTTP $code)")
                else -> StoreResult.Failed(authError(text) ?: "Sign-in failed", code)
            }
        } catch (e: Exception) {
            StoreResult.Unavailable(e.message ?: "Network unavailable")
        }
    }

    /** GoTrue reports errors as `msg`, `error_description` or `message` depending on the endpoint. */
    private fun authError(body: String): String? {
        val j = JsonReader.parseOrNull(body) ?: return null
        return listOf("error_description", "msg", "message")
            .firstNotNullOfOrNull { JsonReader.str(j, it)?.takeIf { s -> s.isNotBlank() } }
    }

    companion object {
        private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

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

        private fun decode(s: String): String =
            runCatching { java.net.URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)
    }
}
