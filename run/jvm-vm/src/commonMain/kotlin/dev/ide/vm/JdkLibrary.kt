package dev.ide.vm

import dev.ide.vm.jdk.registerCollections
import dev.ide.vm.jdk.registerConcurrent
import dev.ide.vm.jdk.registerEventLoop
import dev.ide.vm.jdk.registerGenerics
import dev.ide.vm.jdk.registerIo
import dev.ide.vm.jdk.registerLang
import dev.ide.vm.jdk.registerMaps
import dev.ide.vm.jdk.registerMisc
import dev.ide.vm.jdk.registerNumbers
import dev.ide.vm.jdk.registerReflection
import dev.ide.vm.jdk.registerRegex
import dev.ide.vm.jdk.registerStrings
import dev.ide.vm.jdk.registerThrowables

internal class NativeMethodSpec(val name: String, val descriptor: String, val access: Int, val impl: Native)

/**
 * How the JDK floor describes one class: its supertypes, its fields, and Kotlin bodies for its methods.
 * A method the floor does not define is a gap, reported as [VmUnsupportedException] when something calls it.
 */
class NativeClassBuilder internal constructor(val name: String) {
    var superName: String? = "java/lang/Object"
    internal var access = ACC_PUBLIC
    internal var valueBacked = false
    internal val interfaces = ArrayList<String>()
    internal val methods = ArrayList<NativeMethodSpec>()
    internal val fields = ArrayList<Triple<String, String, Int>>()
    internal var staticInit: (Call.(VmClass) -> Unit)? = null

    fun implements(vararg names: String) { interfaces.addAll(names) }

    /** Marks this an interface: its methods are defaults, its super is `Object` as the class file format has it. */
    fun asInterface() { access = ACC_PUBLIC or ACC_INTERFACE or ACC_ABSTRACT }

    fun asAbstract() { access = access or ACC_ABSTRACT }

    /** Instances are Kotlin values; `new` + `<init>` produce one through the constructor's [Call.retRef]. */
    fun valueBacked() { valueBacked = true; access = access or ACC_FINAL }

    fun method(name: String, descriptor: String, body: Call.() -> Unit) {
        methods.add(NativeMethodSpec(name, descriptor, ACC_PUBLIC, Native { it.body() }))
    }

    fun static(name: String, descriptor: String, body: Call.() -> Unit) {
        methods.add(NativeMethodSpec(name, descriptor, ACC_PUBLIC or ACC_STATIC, Native { it.body() }))
    }

    fun ctor(descriptor: String, body: Call.() -> Unit) = method("<init>", descriptor, body)

    fun field(name: String, descriptor: String) { fields.add(Triple(name, descriptor, ACC_PUBLIC)) }
    fun staticField(name: String, descriptor: String) { fields.add(Triple(name, descriptor, ACC_PUBLIC or ACC_STATIC)) }

    /** Runs when the class initializes, with the class itself, to seed static fields. */
    fun onInit(block: Call.(VmClass) -> Unit) { staticInit = block }
}

internal typealias ClassDefs = MutableMap<String, NativeClassBuilder.() -> Unit>

internal fun ClassDefs.define(name: String, body: NativeClassBuilder.() -> Unit) {
    val previous = this[name]
    // A class may be described in more than one place (an interface's defaults next to its implementations);
    // the definitions compose in registration order.
    this[name] = if (previous == null) body else ({ previous(); body() })
}

/** The JDK, as the floor of classes and members this VM implements in Kotlin. */
class JdkLibrary internal constructor(private val defs: Map<String, NativeClassBuilder.() -> Unit>) {
    internal fun builder(name: String): NativeClassBuilder? = defs[name]?.let { NativeClassBuilder(name).apply(it) }

    /** Every class the floor defines, by internal name. */
    val classNames: Set<String> get() = defs.keys

    companion object {
        fun standard(): JdkLibrary {
            val defs = LinkedHashMap<String, NativeClassBuilder.() -> Unit>()
            defs.registerLang()
            defs.registerStrings()
            defs.registerNumbers()
            defs.registerThrowables()
            defs.registerCollections()
            defs.registerMaps()
            defs.registerConcurrent()
            defs.registerEventLoop()
            defs.registerReflection()
            defs.registerGenerics()
            defs.registerRegex()
            defs.registerIo()
            defs.registerMisc()
            return JdkLibrary(defs)
        }
    }
}
