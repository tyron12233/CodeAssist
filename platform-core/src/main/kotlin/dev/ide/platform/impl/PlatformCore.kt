package dev.ide.platform.impl

import dev.ide.platform.ActivityManager
import dev.ide.platform.ActivitySpec
import dev.ide.platform.Disposable
import dev.ide.platform.ExtensionRegistry
import dev.ide.platform.MessageBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * The assembled platform-core substrate: one [ExtensionRegistry], one [MessageBus], the single model
 * read/write lock, and an [ActivityManager] bound to a supervised coroutine scope. This is the root
 * object the downstream modules (vfs, project-model, build, language) are handed.
 *
 * A [SupervisorJob] roots all activities, so one failing activity does not cancel the others, and
 * [dispose] cancels every in-flight activity and tears down anything registered via [register].
 */
class PlatformCore(
    dispatchers: PlatformDispatchers = PlatformDispatchers(),
    onProgress: (ActivitySpec, Double, String?) -> Unit = { _, _, _ -> },
) : Disposable {

    private val rootJob = SupervisorJob()
    private val scope = CoroutineScope(rootJob)
    private val disposer = CompositeDisposable()

    val extensions: ExtensionRegistry = ExtensionRegistryImpl()
    val messageBus: MessageBus = MessageBusImpl()
    val modelLock: ModelReadWriteLock = ModelReadWriteLock()
    val activityManager: ActivityManager = ActivityManagerImpl(scope, modelLock, dispatchers, onProgress)

    /** Tie an external [Disposable] to this platform's lifecycle (disposed when the platform is). */
    fun register(child: Disposable): Disposable = disposer.add(child)

    override fun dispose() {
        disposer.dispose()
        rootJob.cancel()
    }
}
