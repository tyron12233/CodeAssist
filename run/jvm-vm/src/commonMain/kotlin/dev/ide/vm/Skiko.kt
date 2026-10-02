package dev.ide.vm

/**
 * What every host does the same way for skiko, Compose's skia binding, whatever it binds the natives to.
 *
 * skiko's JVM build finds, extracts and `System.load`s its native library from a jar, behind a file lock.
 * None of that means anything inside the VM: the host has already linked (iOS) or loaded (JVM) skia, so the
 * loader object is created bare and its `load()` does nothing.
 */
object Skiko {
    private const val LIBRARY = "org/jetbrains/skiko/Library"

    /** Replacement bodies, keyed `owner.name(descriptor)`. */
    fun override(vm: Vm, owner: String, name: String, descriptor: String): Native? = when ("$owner.$name$descriptor") {
        "$LIBRARY.<clinit>()V" -> Native {
            val cls = vm.loadClass(LIBRARY)
            cls.setStaticRef("INSTANCE", "L$LIBRARY;", VmObject(cls))
        }
        "$LIBRARY.load()V" -> Native { }
        // The JVM build's post-load JNI setup; the library is already set up wherever the VM runs.
        "org/jetbrains/skia/impl/Library._nAfterLoad()V" -> Native { }
        else -> null
    }
}
