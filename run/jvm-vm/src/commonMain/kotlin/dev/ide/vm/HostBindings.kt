package dev.ide.vm

/**
 * What a host supplies below the class path: bodies for `native` methods (skia's `_nDrawRect`), and
 * replacements for the few library methods that cannot run interpreted (a native-library loader that
 * extracts a `.dylib` from a jar). Both are keyed by the method's owner, name and descriptor.
 *
 * The JDK floor is not here: it is the VM's own, the same on every host. This is what differs per host:
 * on the JVM a native resolves to the real JNI method, on iOS to the C symbol of the same function.
 */
interface HostBindings {
    /** The body of the `native` method [owner].[name][descriptor], or null if this host has none. */
    fun native(vm: Vm, owner: String, name: String, descriptor: String): Native?

    /** A body that replaces the interpreted one, or null to interpret it. Asked once per method, at link. */
    fun override(vm: Vm, owner: String, name: String, descriptor: String): Native? = null

    companion object {
        val NONE: HostBindings = object : HostBindings {
            override fun native(vm: Vm, owner: String, name: String, descriptor: String): Native? = null
        }
    }
}
