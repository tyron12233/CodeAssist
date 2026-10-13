package dev.ide.desktop.store

import dev.ide.core.DAILY_CHALLENGE_SERVICE
import dev.ide.core.STORE_ACCOUNT_SERVICE
import dev.ide.core.STORE_CATALOG_SOURCE
import dev.ide.core.STORE_MODERATION_SERVICE
import dev.ide.core.STORE_REVIEW_SERVICE
import dev.ide.core.STORE_SUBMISSION_SERVICE
import dev.ide.core.project.ProjectManager
import dev.ide.store.StoreAuth
import dev.ide.store.StoreProvider
import dev.ide.store.impl.SupabaseAccountService
import dev.ide.store.impl.SupabaseDailyService
import dev.ide.store.impl.SupabaseModerationService
import dev.ide.store.impl.SupabaseReviewService
import dev.ide.store.impl.SupabaseStoreSource
import dev.ide.store.impl.SupabaseSubmissionService
import java.nio.file.Path

/**
 * The Projects Store's online half for the desktop launcher: the same Supabase catalog, sign-in, publishing,
 * reviews, moderation and daily challenges `AndroidIde` registers, from the generated [StoreConfig]. Must run
 * before the backend is built, which resolves these ports once.
 *
 * Returns the sign-in, whose [LoopbackSignIn.onRedirect] the launcher points at the backend, or null when the
 * build has no endpoint (a fork): the store then serves the bundled catalog and offers no sign-in.
 */
internal fun registerDesktopStore(manager: ProjectManager, sessionFile: Path): LoopbackSignIn? {
    val url = StoreConfig.SUPABASE_URL
    val key = StoreConfig.SUPABASE_KEY
    if (url.isBlank() || key.isBlank()) return null
    val services = manager.applicationContainer

    services.registerServiceIfAbsent(STORE_CATALOG_SOURCE) {
        // Hides catalog items that need a newer IDE than this one.
        SupabaseStoreSource(
            url = url, apiKey = key, appBuild = StoreConfig.APP_BUILD, mediaBaseUrl = StoreConfig.STORE_MEDIA_URL,
        )
    }
    val accounts = SupabaseAccountService(
        url = url,
        apiKey = key,
        // Must also be allow-listed in the Supabase project's auth settings (supabase/config.toml lists it).
        redirectUrl = StoreAuth.DESKTOP_REDIRECT,
        tokens = DesktopTokenStore(sessionFile),
        // Which of these are actually offered is the backend's call (`store_auth_providers`), as on Android.
        enabledProviders = listOf(StoreProvider.GITHUB, StoreProvider.GOOGLE),
    )
    val signIn = LoopbackSignIn(accounts, StoreAuth.DESKTOP_REDIRECT)
    services.registerServiceIfAbsent(STORE_ACCOUNT_SERVICE) { signIn }
    // These take the concrete account service: they need its live session, not just the port.
    services.registerServiceIfAbsent(STORE_SUBMISSION_SERVICE) { SupabaseSubmissionService(url, key, accounts) }
    services.registerServiceIfAbsent(STORE_REVIEW_SERVICE) { SupabaseReviewService(url, key, accounts) }
    // Registered for everyone: whether this account may moderate is `store_admins`' answer, not the build's.
    services.registerServiceIfAbsent(STORE_MODERATION_SERVICE) { SupabaseModerationService(url, key, accounts) }
    services.registerServiceIfAbsent(DAILY_CHALLENGE_SERVICE) {
        SupabaseDailyService(url = url, apiKey = key, judgeUrl = StoreConfig.JUDGE_URL, accounts = accounts)
    }
    return signIn
}
