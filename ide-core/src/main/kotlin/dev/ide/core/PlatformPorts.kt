package dev.ide.core

import dev.ide.analytics.AnalyticsService
import dev.ide.build.engine.ProgramInterpreter
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
internal val ANDROID_DEVICE_TOOLS = ServiceKey<AndroidDeviceTools>("platform.androidDeviceTools")
internal val REAL_VIEW_RUNTIME = ServiceKey<RealViewRuntime>("platform.realViewRuntime")

/** Opt-in usage analytics. Registered by the launcher (`:ide-android`) rather than [ProjectManager], because
 *  it is built from the baked-in transport config *after* the manager exists — hence public, unlike the ports
 *  above. Absent (desktop / tests) resolves to the no-op service. Resolved on `IdeServicesBackend`. */
val ANALYTICS_SERVICE = ServiceKey<AnalyticsService>("platform.analytics")
