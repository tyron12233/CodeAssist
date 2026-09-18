package dev.ide.platform

actual class ThreadLocalValue<T : Any> actual constructor(initial: () -> T) {
    private val slot: ThreadLocal<T> = ThreadLocal.withInitial(initial)

    actual fun get(): T = slot.get()
}
