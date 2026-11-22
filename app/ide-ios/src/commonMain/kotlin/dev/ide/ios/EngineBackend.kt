package dev.ide.ios

import dev.ide.ui.backend.EditorService
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.StoreService
import dev.ide.ui.backend.remote.RemoteCalls
import dev.ide.ui.backend.remote.remoteIdeBackend
import dev.ide.ui.ext.UiPlugin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.newFixedThreadPoolContext

/**
 * The backend of an app whose engine answers for everything: the one the Android and desktop apps run,
 * reached across the embedded channel ([remoteIdeBackend]), so a project is created, indexed, edited, searched,
 * resolved and built here by the code that does it there.
 *
 * Three things stay this host's own. The Projects Store, whose sign-in is the system's own sheet and whose
 * session is in this app's keychain. The plugin UI, which is Compose and cannot cross a byte channel. And the
 * Compose preview, which is drawn in this process from [local]'s analysis of the source: [local] is told
 * which project the engine has open and what each buffer holds, and opens that project for itself (its own
 * index, its own copy of the libraries) only when a preview is first asked for.
 */
internal class EngineBackend private constructor(
    private val remote: IdeBackend,
    private val local: IosBackend,
    scope: CoroutineScope,
    /** The channel's calls, for a self-check to measure. */
    internal val calls: RemoteCalls,
) : IdeBackend by remote {

    override val store: StoreService get() = local.store

    // The AI agent's chat is a Compose plugin, so it is registered here, while the agent it drives runs in the
    // engine (`agent` reaches it across the channel like every other service). On the JVM hosts it arrives
    // with ide-core's built-in plugins, which on iOS live inside the engine.
    override fun uiPlugins(): List<UiPlugin> = local.uiPlugins() + dev.ide.agent.ui.AgentUiPlugin

    override val editor: EditorService = object : EditorService by remote.editor {
        override fun updateDocument(path: String, text: String) {
            local.updateDocument(path, text)
            remote.editor.updateDocument(path, text)
        }

        override fun onFileClosed(path: String) {
            local.onFileClosed(path)
            remote.editor.onFileClosed(path)
        }
    }

    init {
        scope.launch {
            remote.projects.projectEpoch.collect {
                // Asking which project is open fails while none is, which is how the app starts.
                local.followedRoot = runCatching { remote.project.rootPath }.getOrNull()?.takeIf { it.isNotEmpty() }
            }
        }
    }

    companion object {
        /**
         * The engine's backend when [jvm] has an engine that can host one, or null for the app to go on with
         * [local] alone (a build with no engine compiled in, or one that would not start).
         */
        fun start(jvm: EmbeddedJvm, local: IosBackend, projectsRoot: String): IdeBackend? {
            val session = StoreEngineSession((local.store as? dev.ide.ios.store.IosStoreService)?.aiCredits)
            val failure = runCatching { jvm.startBackend(projectsRoot, session) }.getOrElse { it.message ?: it.toString() }
            if (failure.isNotEmpty()) {
                dev.ide.platform.log.Log.logger("ide.embedded").warn("the engine's backend did not start: $failure")
                return null
            }
            // Calls wait for their answers, and one of them is always waiting for news of the flows: a pool of
            // their own, so a slow answer never holds up the app's other background work.
            @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
            val waiting = newFixedThreadPoolContext(12, "engine-calls")
            val scope = CoroutineScope(SupervisorJob() + waiting)
            val calls = RemoteCalls(
                { request -> jvm.call(request.toNSData()).toByteArray() }, scope, waiting,
                onUiThread = { platform.Foundation.NSThread.isMainThread },
            )
            return EngineBackend(remoteIdeBackend(calls, local), local, scope, calls)
        }
    }
}

/**
 * [EngineSession] over the store's own credits service, whose session is the store's: the engine's free tier
 * then signs in, out and refreshes with the store. Called from the engine's threads through the app's native
 * code, so nothing here may throw: an exception crossing into Objective-C would end the process.
 */
private class StoreEngineSession(private val credits: dev.ide.store.AiCreditsService?) : EngineSession {
    override fun supabaseUrl(): String = dev.ide.ios.store.StoreConfig.SUPABASE_URL.takeIf { credits != null }.orEmpty()
    override fun supabaseKey(): String = dev.ide.ios.store.StoreConfig.SUPABASE_KEY.takeIf { credits != null }.orEmpty()
    override fun aiProxyUrl(): String = dev.ide.ios.store.StoreConfig.AI_PROXY_URL
    override fun signedIn(): Boolean = runCatching { credits?.signedIn() == true }.getOrDefault(false)
    override fun accessToken(): String? = runCatching { credits?.accessToken() }.getOrNull()
    override fun refreshAccessToken(stale: String?): String? = runCatching { credits?.refreshAccessToken(stale) }.getOrNull()
}
