package dev.ide.vcs.impl

import dev.ide.vcs.DeviceAuthPoll
import dev.ide.vcs.DeviceAuthorization
import dev.ide.vcs.ForgeClient
import dev.ide.vcs.ForgePullRequest
import dev.ide.vcs.ForgeRepo
import dev.ide.vcs.VcsAccount
import dev.ide.vcs.VcsAuthException
import dev.ide.vcs.VcsException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.URLDecoder
import java.net.URLEncoder
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * GitHub, as the IDE's [ForgeClient]. Sign-in runs the OAuth device-authorization grant, which is the only
 * browser flow that works without a client secret or a redirect the app can receive, and it degrades to a
 * pasted personal access token when this build carries no client id.
 *
 * [apiBase] and [webBase] are separate so an enterprise server works: github.com serves the OAuth pages from
 * the web host and the REST API from `api.github.com`, while GitHub Enterprise serves both from one host.
 */
class GitHubClient(
    private val clientId: String = "",
    private val apiBase: String = DEFAULT_API_BASE,
    private val webBase: String = DEFAULT_WEB_BASE,
    private val http: OkHttpClient = defaultHttpClient(),
) : ForgeClient {

    override val id: String = VcsAccount.FORGE_GITHUB

    override val displayName: String = "GitHub"

    override val host: String = apiBase.substringAfter("://").substringBefore('/')

    override val deviceAuthSupported: Boolean get() = clientId.isNotBlank()

    // ---- sign-in -------------------------------------------------------------------------------

    override fun startDeviceAuth(): DeviceAuthorization {
        validateClientId(clientId)
        val body = form("client_id" to clientId, "scope" to SCOPES)
        val json = postToOAuth("$webBase/login/device/code", body)
        json.errorMessage()?.let { throw VcsException(it) }
        return DeviceAuthorization(
            deviceCode = json.str("device_code") ?: throw VcsException("GitHub did not return a device code"),
            userCode = json.str("user_code").orEmpty(),
            verificationUri = json.str("verification_uri") ?: "$webBase/login/device",
            intervalSeconds = json.int("interval") ?: 5,
            expiresInSeconds = json.int("expires_in") ?: 900,
        )
    }

    override fun pollDeviceAuth(deviceCode: String): DeviceAuthPoll {
        val body = form(
            "client_id" to clientId,
            "device_code" to deviceCode,
            "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
        )
        val json = postToOAuth("$webBase/login/oauth/access_token", body)
        json.str("access_token")?.takeIf { it.isNotBlank() }?.let { return DeviceAuthPoll.Authorized(it) }
        return when (val error = json.str("error")) {
            "authorization_pending" -> DeviceAuthPoll.Pending
            "slow_down" -> DeviceAuthPoll.SlowDown(json.int("interval") ?: 10)
            "expired_token" -> DeviceAuthPoll.Failed("The sign-in code expired. Start again.")
            "access_denied" -> DeviceAuthPoll.Failed("Sign-in was cancelled on GitHub.")
            null -> DeviceAuthPoll.Failed("GitHub returned an unexpected response.")
            else -> DeviceAuthPoll.Failed(json.str("error_description") ?: error)
        }
    }

    override fun verifyToken(token: String): VcsAccount {
        val json = getForJson("$apiBase/user", token) as? JsonObject
            ?: throw VcsException("GitHub returned an unexpected response for the signed-in user")
        val login = json.str("login") ?: throw VcsAuthException("GitHub did not accept this token")
        return VcsAccount(
            id = VcsAccount.idOf(VcsAccount.FORGE_GITHUB, host, login),
            forgeId = VcsAccount.FORGE_GITHUB,
            host = host,
            login = login,
            name = json.str("name")?.takeIf { it.isNotBlank() } ?: login,
            email = json.str("email").orEmpty(),
            avatarUrl = json.str("avatar_url").orEmpty(),
        )
    }

    // ---- repositories --------------------------------------------------------------------------

    override fun repositories(token: String, query: String, page: Int, perPage: Int): List<ForgeRepo> {
        val url = if (query.isBlank()) {
            "$apiBase/user/repos?sort=pushed&per_page=$perPage&page=$page&affiliation=owner,collaborator,organization_member"
        } else {
            // The search endpoint scopes to what the token can see; `fork:true` keeps forks in the results.
            "$apiBase/search/repositories?q=${encode("$query fork:true")}&per_page=$perPage&page=$page"
        }
        val json = getForJson(url, token)
        val items = when (json) {
            is JsonArray -> json
            is JsonObject -> json["items"] as? JsonArray ?: JsonArray(emptyList())
            else -> JsonArray(emptyList())
        }
        return items.filterIsInstance<JsonObject>().map { it.toRepo() }
    }

    override fun createRepository(token: String, name: String, description: String, private: Boolean): ForgeRepo {
        if (name.isBlank()) throw VcsException("Enter a repository name")
        val payload = buildString {
            append("{")
            append("\"name\":").append(quote(name))
            append(",\"description\":").append(quote(description))
            append(",\"private\":").append(private)
            // The IDE pushes an existing history, so the remote must start empty.
            append(",\"auto_init\":false")
            append("}")
        }
        val json = postForJson("$apiBase/user/repos", payload.toRequestBody(JSON_MEDIA), token)
        json.errorMessage()?.let { throw VcsException(it) }
        return json.toRepo()
    }

    // ---- pull requests -------------------------------------------------------------------------

    override fun pullRequests(token: String, owner: String, name: String): List<ForgePullRequest> {
        val json = getForJson("$apiBase/repos/$owner/$name/pulls?state=open&sort=updated&direction=desc", token)
        val items = json as? JsonArray ?: return emptyList()
        return items.filterIsInstance<JsonObject>().map { it.toPullRequest() }
    }

    override fun createPullRequest(
        token: String,
        owner: String,
        name: String,
        title: String,
        body: String,
        head: String,
        base: String,
    ): ForgePullRequest {
        val payload = buildString {
            append("{")
            append("\"title\":").append(quote(title))
            append(",\"body\":").append(quote(body))
            append(",\"head\":").append(quote(head))
            append(",\"base\":").append(quote(base))
            append("}")
        }
        val json = postForJson("$apiBase/repos/$owner/$name/pulls", payload.toRequestBody(JSON_MEDIA), token)
        json.errorMessage()?.let { throw VcsException(it) }
        return json.toPullRequest()
    }

    // ---- wire ----------------------------------------------------------------------------------

    private fun getForJson(url: String, token: String?): JsonElement {
        val request = Request.Builder().url(url).get().apply { apiHeaders(token) }.build()
        return execute(request)
    }

    private fun postForJson(url: String, body: okhttp3.RequestBody, token: String?): JsonObject {
        val request = Request.Builder().url(url).post(body).apply { apiHeaders(token) }.build()
        return execute(request) as? JsonObject ?: JsonObject(emptyMap())
    }

    /**
     * POST to one of the OAuth endpoints on the web host. Those are not the REST API: they answer
     * `application/x-www-form-urlencoded` unless the request asks for `application/json` by that exact name,
     * so they get their own Accept header rather than the API media type.
     */
    private fun postToOAuth(url: String, body: okhttp3.RequestBody): JsonObject {
        val request = Request.Builder()
            .url(url)
            .post(body)
            .header("Accept", "application/json")
            .header("User-Agent", USER_AGENT)
            .build()
        return execute(request) as? JsonObject ?: JsonObject(emptyMap())
    }

    private fun Request.Builder.apiHeaders(token: String?) {
        header("Accept", "application/vnd.github+json")
        header("X-GitHub-Api-Version", "2022-11-28")
        header("User-Agent", USER_AGENT)
        if (!token.isNullOrBlank()) header("Authorization", "Bearer $token")
    }

    private fun execute(request: Request): JsonElement {
        val response: Response = try {
            http.newCall(request).execute()
        } catch (e: IOException) {
            // A DNS miss or a dead connection is the user's to fix, so say so rather than passing along the
            // platform's wording (Android's is `android_getaddrinfo failed: EAI_NODATA`).
            throw VcsException(
                networkFailureMessage(e, request.url.host) ?: "Could not reach GitHub: ${e.reason()}",
                e,
            )
        }
        response.use {
            val text = it.body?.string().orEmpty()
            if (it.code == 401 || it.code == 403) {
                throw VcsAuthException(
                    parseBody(text)?.errorMessage() ?: "GitHub rejected the credentials (HTTP ${it.code})",
                )
            }
            if (!it.isSuccessful) {
                throw VcsException(parseBody(text)?.errorMessage() ?: "GitHub returned HTTP ${it.code}")
            }
            if (text.isBlank()) return JsonObject(emptyMap())
            return parseBody(text)
                ?: throw VcsException("GitHub returned a response that could not be read")
        }
    }

    // ---- mapping -------------------------------------------------------------------------------

    private fun JsonObject.toRepo(): ForgeRepo {
        val owner = (this["owner"] as? JsonObject)?.str("login")
            ?: str("full_name")?.substringBefore('/').orEmpty()
        return ForgeRepo(
            owner = owner,
            name = str("name").orEmpty(),
            description = str("description").orEmpty(),
            private = bool("private") ?: false,
            fork = bool("fork") ?: false,
            defaultBranch = str("default_branch") ?: "main",
            cloneUrl = str("clone_url").orEmpty(),
            webUrl = str("html_url").orEmpty(),
            stars = int("stargazers_count") ?: 0,
            language = str("language").orEmpty(),
            updatedMs = timestamp(str("pushed_at") ?: str("updated_at")),
        )
    }

    private fun JsonObject.toPullRequest(): ForgePullRequest = ForgePullRequest(
        number = int("number") ?: 0,
        title = str("title").orEmpty(),
        author = (this["user"] as? JsonObject)?.str("login").orEmpty(),
        headBranch = (this["head"] as? JsonObject)?.str("ref").orEmpty(),
        baseBranch = (this["base"] as? JsonObject)?.str("ref").orEmpty(),
        webUrl = str("html_url").orEmpty(),
        draft = bool("draft") ?: false,
        updatedMs = timestamp(str("updated_at")),
    )

    companion object {
        const val DEFAULT_API_BASE: String = "https://api.github.com"
        const val DEFAULT_WEB_BASE: String = "https://github.com"

        /**
         * The OAuth App client id this build signs in with, or empty to offer only token sign-in. A client id
         * is public by design (every distributed client carries one), and the device grant uses no secret.
         *
         * It must come from an **OAuth App** with device flow enabled, not a GitHub App: a GitHub App ignores
         * the requested scopes, issues an eight-hour token that needs refreshing, only sees repositories it
         * is installed on, and cannot create one for a user account. [validateClientId] rejects that case.
         */
        const val DEFAULT_CLIENT_ID: String = "Ov23liPbVwkJD3CoAwOo"

        /**
         * What the device grant asks for: `repo` covers clone, push, and pull requests over private
         * repositories; `read:user` identifies the signed-in account for the UI.
         */
        const val SCOPES: String = "repo read:user"

        private const val USER_AGENT = "CodeAssist"

        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        private val FORM_MEDIA = "application/x-www-form-urlencoded".toMediaType()

        private val JSON = Json { ignoreUnknownKeys = true; isLenient = true }

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        private fun form(vararg fields: Pair<String, String>) =
            fields.joinToString("&") { (k, v) -> "${encode(k)}=${encode(v)}" }.toRequestBody(FORM_MEDIA)

        private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

        private fun quote(value: String): String = buildString {
            append('"')
            for (c in value) {
                when (c) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
                }
            }
            append('"')
        }

        /**
         * Fail before the first request when the configured id cannot work. A GitHub App id passes the device
         * grant and then degrades silently (an empty repository list, a failed publish, a session that ends
         * after eight hours), so it is worth naming the mistake here rather than letting the user discover it
         * one broken feature at a time.
         */
        internal fun validateClientId(clientId: String) {
            if (clientId.isBlank()) {
                throw VcsException(
                    "This build has no GitHub OAuth client id. Sign in with a personal access token instead.",
                )
            }
            if (GITHUB_APP_PREFIXES.any { clientId.startsWith(it, ignoreCase = false) }) {
                throw VcsException(
                    "That is a GitHub App client id. Register an OAuth App instead (its id starts with \"Ov\"), " +
                        "enable device flow on it, and use that id.",
                )
            }
        }

        /** Client-id prefixes GitHub issues to GitHub Apps rather than OAuth Apps. */
        private val GITHUB_APP_PREFIXES = listOf("Iv1.", "Iv23li")

        /**
         * Read a response body as JSON, falling back to `application/x-www-form-urlencoded`. The OAuth
         * endpoints answer in that form when the Accept header is not honoured, and a silent parse failure
         * there would surface to the user as an error they cannot act on. Returns null when it is neither.
         */
        internal fun parseBody(text: String): JsonElement? {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) return null
            if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                return runCatching { JSON.parseToJsonElement(trimmed) }.getOrNull()
            }
            if ('=' !in trimmed) return null
            val fields = trimmed.split('&').mapNotNull { pair ->
                val name = pair.substringBefore('=', "")
                if (name.isEmpty()) return@mapNotNull null
                val value = pair.substringAfter('=', "")
                runCatching { decode(name) to JsonPrimitive(decode(value)) }.getOrNull()
            }
            return if (fields.isEmpty()) null else JsonObject(fields.toMap())
        }

        private fun decode(value: String): String = URLDecoder.decode(value, "UTF-8")

        private fun timestamp(iso: String?): Long =
            iso?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: 0L
    }
}

// ---- JSON tree helpers -------------------------------------------------------------------------

private fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString || it.content != "null" }?.content?.takeIf { it != "null" }

private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

/** The human-readable failure GitHub puts in an error body, if there is one. */
private fun JsonElement.errorMessage(): String? {
    val obj = this as? JsonObject ?: return null
    val message = obj.str("message") ?: obj.str("error_description") ?: obj.str("error") ?: return null
    val errors = (obj["errors"] as? JsonArray)
        ?.filterIsInstance<JsonObject>()
        ?.mapNotNull { it.str("message") ?: it.str("field")?.let { field -> "$field ${it.str("code").orEmpty()}".trim() } }
        ?.filter { it.isNotBlank() }
        .orEmpty()
    return if (errors.isEmpty()) message else "$message: ${errors.joinToString("; ")}"
}
