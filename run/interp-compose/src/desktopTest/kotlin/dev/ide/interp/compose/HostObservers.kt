package dev.ide.interp.compose

/**
 * The androidx.lifecycle registry shape as REAL (host, bridged) code, for VmLibraryComposableTest: observers are
 * registered through a marker super-interface, dispatched only when the registered object is the single-method
 * sub-interface, and removed by equality: `Lifecycle.addObserver(LifecycleObserver)` receiving a
 * `LifecycleEventObserver { _, event -> }`.
 */
interface HostObserver

fun interface HostEventObserver : HostObserver {
    fun onEvent(event: String)
}

class HostRegistry {
    private val observers = ArrayList<HostObserver>()

    /** Observers added that were not a [HostEventObserver], so were never dispatched. */
    var ignored = 0

    val size: Int get() = observers.size

    fun addObserver(observer: HostObserver) {
        if (observer is HostEventObserver) observers.add(observer) else ignored++
    }

    fun removeObserver(observer: HostObserver) {
        observers.remove(observer)
    }

    fun dispatch(event: String) {
        ArrayList(observers).forEach { (it as HostEventObserver).onEvent(event) }
    }
}
