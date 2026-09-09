package dev.ide.android.support.tools

import java.nio.file.Path

/**
 * The injectable "app-log bridge" runtime the Android build system weaves into DEBUG builds (never release).
 * Supplied by the host that can ship it (`:ide-android` bundles the precompiled `:applog-runtime` jar as an
 * asset and points at it); absent (null in [AndroidBuildSystem]) on hosts that do not, in which case no
 * instrumentation happens and the build is byte-identical to before.
 *
 * When present and the target variant is debuggable + non-minified, the build:
 *  1. adds [runtimeJar] to the app's external dex scope (so the bridge classes land in the APK),
 *  2. registers a `<provider android:name=[providerClassName]>` in the merged manifest, whose `onCreate`
 *     boots the bridge early in app startup (the androidx-startup / Firebase auto-init pattern), and
 *  3. adds a `<queries><intent>` for [sinkAction] so the bridge can see + bind the IDE's exported log-sink
 *     service under Android 11+ package visibility.
 *
 * The bridge then forwards the app's logs to the IDE over Binder (a `LocalSocket` can't cross the untrusted-app
 * boundary — SELinux denies it), binding the IDE service resolved by [sinkAction]. See docs/app-log-forwarding.md
 * and the `:applog-runtime` module.
 */
data class AndroidAppLogRuntime(
    /** Jar of the bridge's `.class` files (compiled against a stub `android.jar`); dexed into the debug app. */
    val runtimeJar: Path,
    /** Fully-qualified name of the [android.content.ContentProvider] to register (its `onCreate` boots the bridge). */
    val providerClassName: String,
    /** Provider authority suffix, appended to the app's applicationId to form a device-unique authority. */
    val authoritySuffix: String = DEFAULT_AUTHORITY_SUFFIX,
    /** Intent action the IDE's exported log-sink service is resolvable by; injected as a `<queries><intent>` so
     *  the bridge can discover + bind it on API 30+. Keep in sync with `AppLogWire.SINK_ACTION`. */
    val sinkAction: String = DEFAULT_SINK_ACTION,
) {
    companion object {
        const val DEFAULT_PROVIDER_CLASS = "dev.ide.applog.IdeLogBridgeProvider"
        const val DEFAULT_AUTHORITY_SUFFIX = "dev.ide.applog"
        const val DEFAULT_SINK_ACTION = "dev.ide.applog.SINK"
    }
}
