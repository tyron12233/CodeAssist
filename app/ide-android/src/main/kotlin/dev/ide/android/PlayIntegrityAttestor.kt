package dev.ide.android

import android.content.Context
import com.google.android.gms.tasks.Tasks
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.StandardIntegrityException
import com.google.android.play.core.integrity.StandardIntegrityManager.PrepareIntegrityTokenRequest
import com.google.android.play.core.integrity.StandardIntegrityManager.StandardIntegrityTokenProvider
import com.google.android.play.core.integrity.StandardIntegrityManager.StandardIntegrityTokenRequest
import dev.ide.core.BuildAttestor
import dev.ide.platform.log.Log
import dev.ide.store.BuildAttestation
import dev.ide.store.StoreAccountService
import dev.ide.store.StoreResult
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Shows the store backend which build this is, with a Play Integrity token.
 *
 * The backend is shared by every build of this open-source app. Anything the app says about itself a modified
 * build can say too, so the app asks Google Play to say it instead: the token names the package and the
 * certificate that signed the installed APK, and only Google's servers can read it. The backend's `attest`
 * function has it decoded and records the outcome (supabase/functions/attest).
 *
 * Nothing in the app waits on this or changes because of it. It runs on its own thread, once per account per
 * [REATTEST_AFTER_MS], and every failure is silent: a device with no Play services, or no network, is simply
 * reported as having no token (or not reported at all).
 *
 * The token is made for one subject, the signed-in account's id or [BuildAttestation.ANONYMOUS], by way of its
 * request hash. That is what stops one official install from vouching for any account.
 */
internal class PlayIntegrityAttestor(
    context: Context,
    private val accounts: StoreAccountService,
    private val cloudProjectNumber: Long,
    private val signer: String,
    private val preference: (String) -> String?,
    private val setPreference: (String, String) -> Unit,
    private val nowMs: () -> Long = System::currentTimeMillis,
) : BuildAttestor {
    private val appContext = context.applicationContext
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "build-attest").apply { isDaemon = true }
    }

    // Warmed once and reused: Google asks for one provider per process, and preparing it is the slow part.
    private var provider: StandardIntegrityTokenProvider? = null

    override fun attest(userId: String?) {
        executor.execute {
            runCatching { attestNow(userId ?: BuildAttestation.ANONYMOUS) }
                .onFailure { log.warn("attestation failed: ${it.javaClass.simpleName}") }
        }
    }

    private fun attestNow(subject: String) {
        if (!due(preference(STAMP_PREF), subject, nowMs())) return
        var token: String? = null
        var error: String? = null
        try {
            token = requestToken(requestHash(subject))
        } catch (e: Exception) {
            val cause = (e.cause ?: e)
            error = (cause as? StandardIntegrityException)?.let { "integrity_${it.errorCode}" }
                ?: cause.javaClass.simpleName
            // A provider that went stale is rebuilt on the next attempt.
            provider = null
        }
        val sent = accounts.attestBuild(
            BuildAttestation(
                integrityToken = token,
                error = error,
                appId = appContext.packageName,
                signer = signer,
                appBuild = BuildConfig.VERSION_CODE,
            )
        )
        // Stamped once the backend has heard, whatever it made of it: a device that cannot produce a token
        // will not produce one a minute later either. A backend that could not be reached is retried next time.
        if (sent is StoreResult.Ok) setPreference(STAMP_PREF, stamp(subject, nowMs()))
    }

    private fun requestToken(requestHash: String): String {
        val live = provider ?: Tasks.await(
            IntegrityManagerFactory.createStandard(appContext).prepareIntegrityToken(
                PrepareIntegrityTokenRequest.builder().setCloudProjectNumber(cloudProjectNumber).build()
            ),
            TIMEOUT_S, TimeUnit.SECONDS,
        ).also { provider = it }
        return Tasks.await(
            live.request(StandardIntegrityTokenRequest.builder().setRequestHash(requestHash).build()),
            TIMEOUT_S, TimeUnit.SECONDS,
        ).token()
    }

    companion object {
        private val log = Log.logger("ide.attest")
        private const val TIMEOUT_S = 30L

        /** `<subject>|<epoch ms>` of the last attestation the backend acknowledged. */
        const val STAMP_PREF = "attest.last"

        /** Under a day, so an account used daily never falls out of the backend's window. */
        const val REATTEST_AFTER_MS = 20L * 60 * 60 * 1000

        fun stamp(subject: String, atMs: Long): String = "$subject|$atMs"

        /**
         * Whether [subject] should attest now, given the last [stamp]. A different subject (someone signed in,
         * or out) always does. A stamp from the future (the clock was set back) counts as none.
         */
        fun due(stamp: String?, subject: String, nowMs: Long): Boolean {
            val (who, at) = stamp?.split('|')?.takeIf { it.size == 2 } ?: return true
            val atMs = at.toLongOrNull() ?: return true
            return who != subject || atMs > nowMs || nowMs - atMs >= REATTEST_AFTER_MS
        }

        /** The request hash of a token for [subject]: what the backend recomputes and compares. */
        fun requestHash(subject: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(BuildAttestation.requestText(subject).toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }
}
