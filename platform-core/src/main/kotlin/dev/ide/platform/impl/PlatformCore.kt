package dev.ide.platform.impl

import dev.ide.platform.Disposable
import dev.ide.platform.ExtensionRegistry
import dev.ide.platform.MessageBus

/**
 * The assembled platform-core substrate: one [ExtensionRegistry], one [MessageBus], and the single model
 * read/write lock. This is the root object the downstream modules (vfs, project-model, build, language)
 * are handed. [dispose] tears down anything registered via [register].
 *
 * A platform can be the application root (no args → its own registry/bus/lock) or a **child** of an
 * application substrate: pass the app's [parent] registry (this platform's registry then delegates to it,
 * so app-global extensions are visible) and the **shared** [bus]/[lock] (so model events + locking are
 * application-wide). A child shares the app bus/lock, so its [dispose] only tears down its own [register]ed
 * children, never the shared substrate.
 */
class PlatformCore(
    parent: ExtensionRegistry? = null,
    bus: MessageBus? = null,
    lock: ModelReadWriteLock? = null,
) : Disposable {

    private val disposer = CompositeDisposable()

    val extensions: ExtensionRegistry = ExtensionRegistryImpl(parent)
    val messageBus: MessageBus = bus ?: MessageBusImpl()
    val modelLock: ModelReadWriteLock = lock ?: ModelReadWriteLock()

    /** Tie an external [Disposable] to this platform's lifecycle (disposed when the platform is). */
    fun register(child: Disposable): Disposable = disposer.add(child)

    override fun dispose() {
        disposer.dispose()
    }
}
