package dev.ide.core

import dev.ide.build.engine.DexRunner
import dev.ide.lang.kotlin.compile.KotlinPluginLoader
import dev.ide.platform.ServiceKey
import dev.ide.preview.impl.CustomViewRuntime

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
 * Migrated so far: the four self-contained ports below. `androidTools` and `realViewRuntime` remain
 * constructor-threaded for now — they participate in the on-device settings cycle (they read
 * `ProjectManager` preferences at build/render time) and move once an application-scoped preference service
 * exists.
 */
internal val DEX_RUNNER = ServiceKey<DexRunner>("platform.dexRunner")
internal val APK_INSTALLER = ServiceKey<ApkInstaller>("platform.apkInstaller")
internal val CUSTOM_VIEW_RUNTIME = ServiceKey<CustomViewRuntime>("platform.customViewRuntime")
internal val KOTLIN_PLUGIN_LOADER = ServiceKey<KotlinPluginLoader>("platform.kotlinPluginLoader")
