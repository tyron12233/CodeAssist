package dev.ide.core

import dev.ide.analytics.AnalyticsService
import dev.ide.build.ProgramInterpreter
import dev.ide.core.applog.AppLogChannel
import dev.ide.lang.kotlin.compile.KotlinCompilerBackend
import dev.ide.lang.kotlin.compile.KotlinPluginLoader
import dev.ide.platform.ServiceKey
import dev.ide.preview.impl.CustomViewRuntime
import dev.ide.preview.impl.RealViewRuntime

/**
 * APPLICATION-scoped service keys for the desktop-vs-android platform ports (host capabilities the engine
 * needs but doesn't own). The host ([ProjectManager]) registers the launcher-supplied implementations on the
 * application service container, and the engine resolves them there instead of receiving them by constructor
 * injection — so a port is a container service like anything else, and (later) a plugin can resolve one too.
 *
 * A port is registered only when the launcher supplied it (on-device); on the desktop, or in a standalone
 * test with no host, it is absent, so `getServiceOrNull` returns null and the consumer falls back to its
 * in-process default. `getServiceOrNull` is therefore always the resolution call.
 *
 * Every launcher-supplied port is a fully-built object the host registers as-is; a port's own internal
 * settings-reading (e.g. `androidTools`'s forked R8/D8 shrinkers, or `realViewRuntime`'s separate-process
 * toggle, both read via an `AtomicReference` back to `ProjectManager`) is unaffected — only where the object
 * is *stored* moved. The remaining constructor params of [IdeServices] (`sharedCachesRoot`, `buildOnly`,
 * `env`) are configuration/identity, not host capabilities, so they stay. (The `IdeServicesBackend` layer's
 * analytics / build-runner / notifications ports are a separate concern and are not modelled here.)
 */
internal val PROGRAM_INTERPRETER = ServiceKey<ProgramInterpreter>("platform.programInterpreter")
internal val APK_INSTALLER = ServiceKey<ApkInstaller>("platform.apkInstaller")
internal val APP_LOG_CHANNEL = ServiceKey<AppLogChannel>("platform.appLogChannel")
internal val CUSTOM_VIEW_RUNTIME = ServiceKey<CustomViewRuntime>("platform.customViewRuntime")
internal val KOTLIN_PLUGIN_LOADER = ServiceKey<KotlinPluginLoader>("platform.kotlinPluginLoader")
internal val KOTLIN_COMPILER_BACKEND = ServiceKey<KotlinCompilerBackend>("platform.kotlinCompilerBackend")
internal val ANDROID_DEVICE_TOOLS = ServiceKey<AndroidDeviceTools>("platform.androidDeviceTools")
internal val REAL_VIEW_RUNTIME = ServiceKey<RealViewRuntime>("platform.realViewRuntime")

/** Opt-in usage analytics. Registered by the launcher (`:ide-android`) rather than [ProjectManager], because
 *  it is built from the baked-in transport config *after* the manager exists — hence public, unlike the ports
 *  above. Absent (desktop / tests) resolves to the no-op service. Resolved on `IdeServicesBackend`. */
val ANALYTICS_SERVICE = ServiceKey<AnalyticsService>("platform.analytics")

/** The remote Projects Store catalog. Registered by the launcher (`:ide-android`) from the baked-in
 *  Supabase config, for the same reason as [ANALYTICS_SERVICE]. Absent (desktop / tests) resolves to
 *  [dev.ide.store.StoreCatalogSource.Unconfigured], and the store falls back to the bundled catalog. */
val STORE_CATALOG_SOURCE = ServiceKey<dev.ide.store.StoreCatalogSource>("platform.storeCatalog")

/** The store's OAuth account service. Registered by the launcher, which is the only place that knows both
 *  the Supabase config and the redirect this host can actually receive: a deep link on Android, a loopback
 *  URL on desktop. Absent resolves to a service that reports sign-in unavailable, so browsing still works
 *  on a build with no auth configured. */
val STORE_ACCOUNT_SERVICE = ServiceKey<dev.ide.store.StoreAccountService>("platform.storeAccounts")

/** Submissions. Separate from [STORE_ACCOUNT_SERVICE] because submitting needs a signed-in session and
 *  browsing needs neither, so a host can wire the catalog without wiring publishing at all. */
val STORE_SUBMISSION_SERVICE = ServiceKey<dev.ide.store.StoreSubmissionService>("platform.storeSubmissions")

/** Ratings and reviews. Reads are anonymous; writes need the account service, so the launcher builds both. */
val STORE_REVIEW_SERVICE = ServiceKey<dev.ide.store.StoreReviewService>("platform.storeReviews")

/** Moderation: the review queue and the decisions on it. Needs a signed-in session, and the database only
 *  answers an account listed in `store_admins`, so on almost every install this resolves to a service whose
 *  every surface is hidden. Absent (desktop / tests) is the same as not being a moderator. */
val STORE_MODERATION_SERVICE = ServiceKey<dev.ide.store.StoreModerationService>("platform.storeModeration")

/** Daily coding challenges. Registered by the launcher, which is the only place that knows both the
 *  Supabase config and where the judge is deployed. Absent (desktop / tests) resolves to a service that
 *  reports challenges unavailable, and the tab explains itself rather than failing. */
val DAILY_CHALLENGE_SERVICE = ServiceKey<dev.ide.store.DailyChallengeService>("platform.dailyChallenges")

/** The "Get plugins" page's list of downloadable plugins (the backend's `plugin_catalog`). Absent (tests,
 *  unconfigured builds) leaves the page saying the list is unavailable. */
val PLUGIN_DOWNLOAD_SERVICE = ServiceKey<dev.ide.store.PluginDownloadService>("platform.pluginDownloads")

/** What the host can tell about, and do with, an installed plugin app: its install facts, its system page,
 *  uninstalling it. Android only; absent elsewhere, where the detail page leaves those rows out. */
val PLUGIN_PACKAGES = ServiceKey<dev.ide.core.plugins.PluginPackages>("platform.pluginPackages")

/** Learn's online half: the published lesson catalog (later account progress and lesson discussions).
 *  Registered by the launcher. Absent (tests, unconfigured builds) leaves Learn on its bundled catalog. */
val LEARN_REMOTE_SERVICE = ServiceKey<dev.ide.store.LearnRemoteService>("platform.learnRemote")

/** CodeAssist Free AI: the points wallet and the proxy's address. Registered by the launcher next to the store
 *  account service, whose session it borrows. Absent resolves to a service that reports the tier unavailable,
 *  and the agent offers bring-your-own-key providers only. */
val AI_CREDITS_SERVICE = ServiceKey<dev.ide.store.AiCreditsService>("platform.aiCredits")

/**
 * A host that can raise an OS-level notification.
 *
 * Optional: the in-app notification center works without it, and on a host with no notification surface
 * (desktop today, tests) there is nothing to register. The center posts to it as well as to its own list,
 * so the host decides what deserves interrupting the user and what only belongs in the list.
 */
val NOTIFICATION_PRESENTER = ServiceKey<NotificationPresenter>("platform.notificationPresenter")

/** Implemented by the launcher; see [NOTIFICATION_PRESENTER]. */
interface NotificationPresenter {
    fun present(notification: dev.ide.ui.backend.UiNotification)
}

/**
 * A host that can restart the whole application.
 *
 * Plugins are loaded once per process, so a plugin the user installs, updates, uninstalls or toggles takes
 * effect by starting again (see [dev.ide.core.plugins.PluginChanges]). Optional: where no launcher registered
 * one (desktop, tests) the Plugins screen states the restart as something for the user to do, rather than
 * offering a button that cannot work.
 */
val APP_RESTARTER = ServiceKey<AppRestarter>("platform.appRestarter")

/**
 * A host that can run a second, throwaway copy of the IDE with a plugin under development loaded straight from
 * its build output: the plugin's "Run". On Android it is the same installed app in its own process, with its own
 * home (projects, preferences, caches), so the plugin can crash or freeze it without touching the IDE the
 * developer is working in, and nothing is installed or restarted to try a change. Absent (desktop, tests) means
 * a plugin project offers only "Install plugin".
 */
val PLUGIN_SANDBOX = ServiceKey<PluginSandbox>("platform.pluginSandbox")

/** Implemented by the launcher; see [PLUGIN_SANDBOX]. */
interface PluginSandbox {
    /**
     * Stop any sandbox already running and start a fresh one that loads the plugin in [apk], writing progress
     * to [log]. Returns false when it could not be started, having said why through [log].
     */
    fun launch(apk: java.nio.file.Path, log: (String) -> Unit): Boolean

    companion object {
        /**
         * Begins the [dev.ide.platform.log.LogRecord.source] of a record the sandbox's plugin logged, relayed into
         * the real IDE's log so its developer reads it where they work (`sandbox:<plugin id>`). The real IDE shows
         * those in its Logs viewer and nothing more: the sandbox already put up its own error dialog, and its
         * errors are not the real IDE's to report.
         */
        const val LOG_SOURCE_PREFIX = "sandbox:"

        /** True for a record relayed from the sandbox; see [LOG_SOURCE_PREFIX]. */
        fun isRelayed(record: dev.ide.platform.log.LogRecord): Boolean = record.source?.startsWith(LOG_SOURCE_PREFIX) == true
    }
}

/** Implemented by the launcher; see [APP_RESTARTER]. */
interface AppRestarter {
    /** Take the app down, every process of it, and bring its UI back up. Does not return on a host that
     *  really restarts, so the caller must have finished whatever has to survive (saving open files). */
    fun restart()
}
