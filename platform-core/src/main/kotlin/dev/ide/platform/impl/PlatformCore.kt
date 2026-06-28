package dev.ide.platform.impl

import dev.ide.platform.Disposable
import dev.ide.platform.ExtensionRegistry
import dev.ide.platform.MessageBus

/**
 * The assembled platform-core substrate: one [ExtensionRegistry], one [MessageBus], and the single model
 * read/write lock. This is the root object the downstream modules (vfs, project-model, build, language)
 * are handed. [dispose] tears down anything registered via [register].
 */
class PlatformCore : Disposable {

    private val disposer = CompositeDisposable()

    val extensions: ExtensionRegistry = ExtensionRegistryImpl()
    val messageBus: MessageBus = MessageBusImpl()
    val modelLock: ModelReadWriteLock = ModelReadWriteLock()

    /** Tie an external [Disposable] to this platform's lifecycle (disposed when the platform is). */
    fun register(child: Disposable): Disposable = disposer.add(child)

    override fun dispose() {
        disposer.dispose()
    }
}
