package dev.ide.vm.jdk

import dev.ide.vm.*

/** A constructor or method handed to interpreted code by reflection. */
internal class ReflectedMember(override val vmClass: VmClass, val method: VmMethod) : HostObject {
    override fun equals(other: Any?): Boolean = other is ReflectedMember && other.method === method
    override fun hashCode(): Int = method.hashCode()
}

/** A field handed to interpreted code by reflection. */
internal class ReflectedField(override val vmClass: VmClass, val field: VmField) : HostObject {
    override fun equals(other: Any?): Boolean = other is ReflectedField && other.field === field
    override fun hashCode(): Int = field.hashCode()
}

/** A `java.lang.reflect.Parameter`. */
internal class ReflectedParameter(override val vmClass: VmClass, val owner: VmMethod, val index: Int) : HostObject

/** What a `Proxy` instance keeps: its handler. */
internal class ProxyState(val handler: Any?)

private const val ACC_VARARGS = 0x0080
private const val ACC_BRIDGE = 0x0040
private const val ACC_SYNTHETIC = 0x1000

/**
 * Reflection, answered from the VM's own class data: every class here is one the VM linked, so it knows
 * every member. This is the part of the JDK a framework that reflects on itself (the preview interpreter's
 * dispatcher, a service loader, a serializer) cannot run without.
 *
 * What is not modeled: annotations (none are reported), generic signatures (a generic type is its erasure),
 * and access control (everything is accessible).
 */
internal fun ClassDefs.registerReflection() {
    define("java/lang/Class") {
        static("forName", "(Ljava/lang/String;)Ljava/lang/Class;") { retRef(vm.mirror(vm.classForName(r(0) as String, initialize = true))) }
        static("forName", "(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;") {
            retRef(vm.mirror(vm.classForName(r(0) as String, initialize = z(1))))
        }
        method("getClassLoader", "()Ljava/lang/ClassLoader;") { retRef(if (cls(0).isPrimitive) null else vm.classLoaderObject()) }
        method("getDeclaredConstructor", "([Ljava/lang/Class;)Ljava/lang/reflect/Constructor;") { retRef(vm.constructorOf(cls(0), r(1) as VmRefArray?)) }
        method("getConstructor", "([Ljava/lang/Class;)Ljava/lang/reflect/Constructor;") { retRef(vm.constructorOf(cls(0), r(1) as VmRefArray?)) }
        method("getDeclaredConstructors", "()[Ljava/lang/reflect/Constructor;") {
            retRef(vm.memberArray("java/lang/reflect/Constructor", cls(0).methods.values.filter { it.name == "<init>" }))
        }
        method("getConstructors", "()[Ljava/lang/reflect/Constructor;") {
            retRef(vm.memberArray("java/lang/reflect/Constructor", cls(0).methods.values.filter { it.name == "<init>" && it.access and ACC_PUBLIC != 0 }))
        }
        method("newInstance", "()Ljava/lang/Object;") { retRef(vm.construct(vm.constructorOf(cls(0), null).method, emptyArray())) }
        method("getDeclaredMethods", "()[Ljava/lang/reflect/Method;") {
            retRef(vm.memberArray("java/lang/reflect/Method", cls(0).methods.values.filter { !it.name.startsWith("<") }))
        }
        method("getMethods", "()[Ljava/lang/reflect/Method;") { retRef(vm.memberArray("java/lang/reflect/Method", vm.publicMethods(cls(0)))) }
        method("getMethod", "(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;") {
            val name = r(1) as String
            val params = vm.descriptorsOf(r(2) as VmRefArray?)
            val m = vm.publicMethods(cls(0)).firstOrNull { it.name == name && parameterDescriptors(it.descriptor) == params }
                ?: vm.throwVm("java/lang/NoSuchMethodException", "${cls(0).javaName}.$name")
            retRef(vm.methodObject(m))
        }
        method("getDeclaredMethod", "(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;") {
            val name = r(1) as String
            val params = vm.descriptorsOf(r(2) as VmRefArray?)
            val m = cls(0).methods.values.firstOrNull { it.name == name && parameterDescriptors(it.descriptor) == params }
                ?: vm.throwVm("java/lang/NoSuchMethodException", "${cls(0).javaName}.$name")
            retRef(vm.methodObject(m))
        }
        method("getDeclaredFields", "()[Ljava/lang/reflect/Field;") { retRef(vm.fieldArray(cls(0).fields.values.toList())) }
        method("getFields", "()[Ljava/lang/reflect/Field;") { retRef(vm.fieldArray(vm.publicFields(cls(0)))) }
        method("getDeclaredField", "(Ljava/lang/String;)Ljava/lang/reflect/Field;") {
            val f = cls(0).fields.values.firstOrNull { it.name == r(1) } ?: vm.throwVm("java/lang/NoSuchFieldException", r(1) as String)
            retRef(vm.fieldObject(f))
        }
        method("getField", "(Ljava/lang/String;)Ljava/lang/reflect/Field;") {
            val f = vm.publicFields(cls(0)).firstOrNull { it.name == r(1) } ?: vm.throwVm("java/lang/NoSuchFieldException", r(1) as String)
            retRef(vm.fieldObject(f))
        }
        method("getAnnotations", "()[Ljava/lang/annotation/Annotation;") { retRef(vm.newRefArray("java/lang/annotation/Annotation", emptyArray())) }
        method("getDeclaredAnnotations", "()[Ljava/lang/annotation/Annotation;") { retRef(vm.newRefArray("java/lang/annotation/Annotation", emptyArray())) }
        method("getAnnotation", "(Ljava/lang/Class;)Ljava/lang/annotation/Annotation;") { retRef(null) }
        method("getDeclaredAnnotation", "(Ljava/lang/Class;)Ljava/lang/annotation/Annotation;") { retRef(null) }
        method("isAnnotation", "()Z") { ret(cls(0).access and 0x2000 != 0) }
        method("getGenericSuperclass", "()Ljava/lang/reflect/Type;") {
            val c = cls(0)
            retRef(if (c.isInterface) null else vm.genericSupertypes(c)?.firstOrNull() ?: c.superClass?.let { vm.mirror(it) })
        }
        method("getGenericInterfaces", "()[Ljava/lang/reflect/Type;") {
            val c = cls(0)
            val generic = vm.genericSupertypes(c)?.drop(if (c.isInterface) 1 else 1)
            retRef(vm.newRefArray("java/lang/reflect/Type", (generic ?: c.interfaces.map { vm.mirror(it) }).toTypedArray()))
        }
        method("getTypeParameters", "()[Ljava/lang/reflect/TypeVariable;") { retRef(vm.newRefArray("java/lang/reflect/TypeVariable", emptyArray())) }
        method("getDeclaredClasses", "()[Ljava/lang/Class;") { retRef(vm.newRefArray("java/lang/Class", emptyArray())) }
        method("getNestHost", "()Ljava/lang/Class;") { retRef(r(0)) }
        method("isRecord", "()Z") { ret(cls(0).superClass?.name == "java/lang/Record") }
        method("isSealed", "()Z") { ret(false) }
        method("isHidden", "()Z") { ret(false) }
        method("getResourceAsStream", "(Ljava/lang/String;)Ljava/io/InputStream;") {
            val name = r(1) as String
            val path = if (name.startsWith("/")) name.substring(1) else cls(0).name.substringBeforeLast('/', "").let { if (it.isEmpty()) name else "$it/$name" }
            retRef(vm.resourceStream(path))
        }
    }

    define("java/lang/reflect/Member") { asInterface() }
    define("java/lang/reflect/InvocationHandler") { asInterface() }
    define("java/lang/reflect/ParameterizedType") { asInterface(); implements("java/lang/reflect/Type") }
    define("java/lang/reflect/TypeVariable") { asInterface(); implements("java/lang/reflect/Type") }
    define("java/lang/reflect/WildcardType") { asInterface(); implements("java/lang/reflect/Type") }
    define("java/lang/reflect/GenericArrayType") { asInterface(); implements("java/lang/reflect/Type") }

    define("java/lang/reflect/AccessibleObject") {
        implements("java/lang/reflect/AnnotatedElement")
        method("setAccessible", "(Z)V") {}
        method("trySetAccessible", "()Z") { ret(true) }
        method("isAccessible", "()Z") { ret(true) }
        method("canAccess", "(Ljava/lang/Object;)Z") { ret(true) }
        method("getAnnotation", "(Ljava/lang/Class;)Ljava/lang/annotation/Annotation;") { retRef(null) }
        method("getDeclaredAnnotation", "(Ljava/lang/Class;)Ljava/lang/annotation/Annotation;") { retRef(null) }
        method("isAnnotationPresent", "(Ljava/lang/Class;)Z") { ret(false) }
        method("getAnnotations", "()[Ljava/lang/annotation/Annotation;") { retRef(vm.newRefArray("java/lang/annotation/Annotation", emptyArray())) }
        method("getDeclaredAnnotations", "()[Ljava/lang/annotation/Annotation;") { retRef(vm.newRefArray("java/lang/annotation/Annotation", emptyArray())) }
        static("setAccessible", "([Ljava/lang/reflect/AccessibleObject;Z)V") {}
    }

    define("java/lang/reflect/Executable") {
        superName = "java/lang/reflect/AccessibleObject"
        asAbstract()
        implements("java/lang/reflect/Member", "java/lang/reflect/GenericDeclaration")
        fun Call.m(): VmMethod = (r(0) as ReflectedMember).method
        method("getName", "()Ljava/lang/String;") { val m = m(); retRef(if (m.name == "<init>") m.owner.javaName else m.name) }
        method("getModifiers", "()I") { ret(m().access and 0xFFFF) }
        method("getDeclaringClass", "()Ljava/lang/Class;") { retRef(vm.mirror(m().owner)) }
        method("getParameterTypes", "()[Ljava/lang/Class;") { retRef(vm.parameterClasses(m())) }
        method("getGenericParameterTypes", "()[Ljava/lang/reflect/Type;") {
            retRef(vm.newRefArray("java/lang/reflect/Type", vm.genericParameterTypes(m()).toTypedArray()))
        }
        method("getParameterCount", "()I") { ret(parameterDescriptors(m().descriptor).size) }
        method("getParameters", "()[Ljava/lang/reflect/Parameter;") {
            val m = m()
            val cls = vm.loadClass("java/lang/reflect/Parameter")
            retRef(vm.newRefArray("java/lang/reflect/Parameter", Array(parameterDescriptors(m.descriptor).size) { ReflectedParameter(cls, m, it) }))
        }
        method("getExceptionTypes", "()[Ljava/lang/Class;") { retRef(vm.newRefArray("java/lang/Class", emptyArray())) }
        method("getParameterAnnotations", "()[[Ljava/lang/annotation/Annotation;") {
            val n = parameterDescriptors(m().descriptor).size
            retRef(VmRefArray(vm.loadClass("[[Ljava/lang/annotation/Annotation;"), Array(n) { vm.newRefArray("java/lang/annotation/Annotation", emptyArray()) }))
        }
        method("isVarArgs", "()Z") { ret(m().access and ACC_VARARGS != 0) }
        method("isSynthetic", "()Z") { ret(m().access and ACC_SYNTHETIC != 0) }
        method("getTypeParameters", "()[Ljava/lang/reflect/TypeVariable;") { retRef(vm.newRefArray("java/lang/reflect/TypeVariable", emptyArray())) }
        method("hashCode", "()I") { ret(m().owner.name.hashCode() xor m().name.hashCode()) }
        method("equals", "(Ljava/lang/Object;)Z") { ret(r(1) is ReflectedMember && (r(1) as ReflectedMember).method === m()) }
        method("toString", "()Ljava/lang/String;") { val m = m(); retRef("${m.owner.javaName}.${m.name}${m.descriptor}") }
    }
    define("java/lang/reflect/Method") {
        superName = "java/lang/reflect/Executable"
        fun Call.m(): VmMethod = (r(0) as ReflectedMember).method
        method("getReturnType", "()Ljava/lang/Class;") { retRef(vm.mirror(vm.classForDescriptor(returnDescriptor(m().descriptor)))) }
        method("getGenericReturnType", "()Ljava/lang/reflect/Type;") { retRef(vm.genericReturnType(m())) }
        method("isBridge", "()Z") { ret(m().access and ACC_BRIDGE != 0) }
        method("isDefault", "()Z") { val m = m(); ret(m.owner.isInterface && m.access and (ACC_ABSTRACT or ACC_STATIC) == 0) }
        method("getDefaultValue", "()Ljava/lang/Object;") { retRef(null) }
        method("invoke", "(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;") {
            retRef(vm.reflectInvoke(m(), r(1), (r(2) as VmRefArray?)?.data ?: emptyArray()))
        }
    }
    define("java/lang/reflect/Constructor") {
        superName = "java/lang/reflect/Executable"
        method("newInstance", "([Ljava/lang/Object;)Ljava/lang/Object;") {
            val ctor = r(0) as ReflectedMember
            val args = (r(1) as VmRefArray?)?.data ?: emptyArray()
            retRef(vm.wrapTargetException { vm.construct(ctor.method, vm.unboxForCall(ctor.method, args)) })
        }
    }
    define("java/lang/reflect/Parameter") {
        implements("java/lang/reflect/AnnotatedElement")
        fun Call.p(): ReflectedParameter = r(0) as ReflectedParameter
        method("getName", "()Ljava/lang/String;") { retRef("arg${p().index}") }
        method("getType", "()Ljava/lang/Class;") { retRef(vm.parameterClasses(p().owner).data[p().index]) }
        method("getParameterizedType", "()Ljava/lang/reflect/Type;") { retRef(vm.genericParameterTypes(p().owner)[p().index]) }
        method("isNamePresent", "()Z") { ret(false) }
        method("getAnnotations", "()[Ljava/lang/annotation/Annotation;") { retRef(vm.newRefArray("java/lang/annotation/Annotation", emptyArray())) }
    }
    define("java/lang/reflect/Field") {
        superName = "java/lang/reflect/AccessibleObject"
        implements("java/lang/reflect/Member")
        fun Call.f(): VmField = (r(0) as ReflectedField).field
        method("getName", "()Ljava/lang/String;") { retRef(f().name) }
        method("getModifiers", "()I") { ret(f().access and 0xFFFF) }
        method("getDeclaringClass", "()Ljava/lang/Class;") { retRef(vm.mirror(f().owner)) }
        method("getType", "()Ljava/lang/Class;") { retRef(vm.mirror(vm.classForDescriptor(f().descriptor))) }
        method("getGenericType", "()Ljava/lang/reflect/Type;") { retRef(vm.genericFieldType(f())) }
        method("isSynthetic", "()Z") { ret(f().access and ACC_SYNTHETIC != 0) }
        method("isEnumConstant", "()Z") { ret(f().access and ACC_ENUM != 0) }
        method("get", "(Ljava/lang/Object;)Ljava/lang/Object;") { retRef(vm.readField(f(), r(1))) }
        method("set", "(Ljava/lang/Object;Ljava/lang/Object;)V") { vm.writeField(f(), r(1), r(2)) }
        for ((t, name) in listOf("I" to "Int", "J" to "Long", "Z" to "Boolean", "F" to "Float", "D" to "Double", "B" to "Byte", "S" to "Short", "C" to "Char")) {
            method("get$name", "(Ljava/lang/Object;)$t") {
                when (val v = vm.readField(f(), r(1))) {
                    is Int -> ret(v); is Long -> ret(v); is Boolean -> ret(v); is Float -> ret(v); is Double -> ret(v)
                    is Byte -> ret(v.toInt()); is Short -> ret(v.toInt()); is Char -> ret(v)
                    else -> vm.throwVm("java/lang/IllegalArgumentException", "field ${f().name} is not a $name")
                }
            }
        }
        method("setInt", "(Ljava/lang/Object;I)V") { vm.writeField(f(), r(1), i(2)) }
        method("setLong", "(Ljava/lang/Object;J)V") { vm.writeField(f(), r(1), l(2)) }
        method("setBoolean", "(Ljava/lang/Object;Z)V") { vm.writeField(f(), r(1), z(2)) }
        method("setFloat", "(Ljava/lang/Object;F)V") { vm.writeField(f(), r(1), f(2)) }
        method("setDouble", "(Ljava/lang/Object;D)V") { vm.writeField(f(), r(1), d(2)) }
        method("hashCode", "()I") { ret(f().owner.name.hashCode() xor f().name.hashCode()) }
        method("equals", "(Ljava/lang/Object;)Z") { ret(r(1) is ReflectedField && (r(1) as ReflectedField).field === f()) }
        method("toString", "()Ljava/lang/String;") { retRef("${f().owner.javaName}.${f().name}") }
    }

    define("java/lang/reflect/Modifier") {
        val bits = listOf("isPublic" to ACC_PUBLIC, "isPrivate" to ACC_PRIVATE, "isProtected" to 0x0004, "isStatic" to ACC_STATIC,
            "isFinal" to ACC_FINAL, "isSynchronized" to ACC_SYNCHRONIZED, "isVolatile" to ACC_VOLATILE, "isTransient" to 0x0080,
            "isNative" to ACC_NATIVE, "isInterface" to ACC_INTERFACE, "isAbstract" to ACC_ABSTRACT, "isStrict" to 0x0800)
        for ((name, bit) in bits) static(name, "(I)Z") { ret(i(0) and bit != 0) }
        static("toString", "(I)Ljava/lang/String;") {
            retRef(bits.filter { i(0) and it.second != 0 }.joinToString(" ") { it.first.removePrefix("is").lowercase() })
        }
    }

    define("java/lang/reflect/Array") {
        static("newInstance", "(Ljava/lang/Class;I)Ljava/lang/Object;") {
            val c = cls(0)
            retRef(vm.newArray(if (c.isPrimitive) c.primitive.toString() else if (c.isArray) c.name else "L${c.name};", i(1)))
        }
        static("newInstance", "(Ljava/lang/Class;[I)Ljava/lang/Object;") {
            val c = cls(0)
            val dims = r(1) as IntArray
            fun make(level: Int, component: String): Any {
                val arr = vm.newArray(("[".repeat(dims.size - level - 1)) + component, dims[level])
                if (level + 1 < dims.size && arr is VmRefArray) for (k in arr.data.indices) arr.data[k] = make(level + 1, component)
                return arr
            }
            retRef(make(0, if (c.isPrimitive) c.primitive.toString() else if (c.isArray) c.name else "L${c.name};"))
        }
        static("getLength", "(Ljava/lang/Object;)I") { ret(arrayElementsAny(vm, r(0)).size) }
        static("get", "(Ljava/lang/Object;I)Ljava/lang/Object;") { retRef(arrayElementsAny(vm, r(0))[i(1)]) }
        static("set", "(Ljava/lang/Object;ILjava/lang/Object;)V") { arraySet(vm, r(0), i(1), r(2)) }
        static("getInt", "(Ljava/lang/Object;I)I") { ret((arrayElementsAny(vm, r(0))[i(1)] as Number).toInt()) }
        static("getLong", "(Ljava/lang/Object;I)J") { ret((arrayElementsAny(vm, r(0))[i(1)] as Number).toLong()) }
        static("getBoolean", "(Ljava/lang/Object;I)Z") { ret(arrayElementsAny(vm, r(0))[i(1)] as Boolean) }
    }

    define("java/lang/reflect/Proxy") {
        implements("java/io/Serializable")
        field("h", "Ljava/lang/reflect/InvocationHandler;")
        static("newProxyInstance", "(Ljava/lang/ClassLoader;[Ljava/lang/Class;Ljava/lang/reflect/InvocationHandler;)Ljava/lang/Object;") {
            val interfaces = (r(1) as VmRefArray).data.map { (it as VmObject).native as VmClass }
            retRef(vm.newProxy(interfaces, r(2)))
        }
        static("isProxyClass", "(Ljava/lang/Class;)Z") { ret(cls(0).superClass?.name == "java/lang/reflect/Proxy") }
        static("getInvocationHandler", "(Ljava/lang/Object;)Ljava/lang/reflect/InvocationHandler;") {
            val o = r(0) as? VmObject
            if (o == null || o.cls.superClass?.name != "java/lang/reflect/Proxy") vm.throwVm("java/lang/IllegalArgumentException", "not a proxy instance")
            retRef(o.refs[0])
        }
    }

    define("java/lang/reflect/InvocationTargetException") {
        method("getTargetException", "()Ljava/lang/Throwable;") { retRef((self().native as ThrowableState).cause) }
    }

    define("java/lang/ClassLoader") {
        asAbstract()
        ctor("()V") {}
        ctor("(Ljava/lang/ClassLoader;)V") {}
        ctor("(Ljava/lang/String;Ljava/lang/ClassLoader;)V") {}
        method("defineClass", "(Ljava/lang/String;[BII)Ljava/lang/Class;") {
            val b = (r(2) as ByteArray).copyOfRange(i(3), i(3) + i(4))
            retRef(vm.mirror(vm.defineClassBytes(b)))
        }
        method("defineClass", "(Ljava/lang/String;[BIILjava/security/ProtectionDomain;)Ljava/lang/Class;") {
            val b = (r(2) as ByteArray).copyOfRange(i(3), i(3) + i(4))
            retRef(vm.mirror(vm.defineClassBytes(b)))
        }
        method("findLoadedClass", "(Ljava/lang/String;)Ljava/lang/Class;") {
            retRef(runCatching { vm.mirror(vm.classForName(r(1) as String, initialize = false)) }.getOrNull())
        }
        method("findClass", "(Ljava/lang/String;)Ljava/lang/Class;") { retRef(vm.mirror(vm.classForName(r(1) as String, initialize = false))) }
        method("resolveClass", "(Ljava/lang/Class;)V") {}
        static("getSystemClassLoader", "()Ljava/lang/ClassLoader;") { retRef(vm.classLoaderObject()) }
        static("getPlatformClassLoader", "()Ljava/lang/ClassLoader;") { retRef(vm.classLoaderObject()) }
        method("loadClass", "(Ljava/lang/String;)Ljava/lang/Class;") { retRef(vm.mirror(vm.classForName(r(1) as String, initialize = false))) }
        method("loadClass", "(Ljava/lang/String;Z)Ljava/lang/Class;") { retRef(vm.mirror(vm.classForName(r(1) as String, initialize = false))) }
        method("getParent", "()Ljava/lang/ClassLoader;") { retRef(null) }
        method("getResourceAsStream", "(Ljava/lang/String;)Ljava/io/InputStream;") { retRef(vm.resourceStream(r(1) as String)) }
        // A URL into a jar is not something the VM can hand out; libraries that try fall back (coroutines'
        // service loading goes to ServiceLoader when this throws).
        method("getResources", "(Ljava/lang/String;)Ljava/util/Enumeration;") {
            vm.throwVm("java/lang/UnsupportedOperationException", "class-path resource URLs")
        }
        method("getResource", "(Ljava/lang/String;)Ljava/net/URL;") { retRef(null) }
    }
    define("java/lang/HostClassLoader") { superName = "java/lang/ClassLoader" }

    define("java/util/ServiceLoader") {
        implements("java/lang/Iterable")
        static("load", "(Ljava/lang/Class;)Ljava/util/ServiceLoader;") { retRef(vm.serviceLoader(cls(0))) }
        static("load", "(Ljava/lang/Class;Ljava/lang/ClassLoader;)Ljava/util/ServiceLoader;") { retRef(vm.serviceLoader(cls(0))) }
        method("iterator", "()Ljava/util/Iterator;") {
            val names = self().native as List<*>
            val vm = vm
            retRef(vm.hostIterator(names.asSequence().map { name ->
                val provider = vm.classForName(name as String, initialize = true)
                vm.construct(vm.constructorOf(provider, null).method, emptyArray())
            }.iterator()))
        }
        method("reload", "()V") {}
    }
}

/** `Class.forName`: a class the class path (or the floor) does not have is a `ClassNotFoundException`. */
internal fun Vm.classForName(name: String, initialize: Boolean): VmClass {
    val cls = try {
        loadClass(name.replace('.', '/'))
    } catch (e: VmUnsupportedException) {
        throwVm("java/lang/ClassNotFoundException", name)
    } catch (e: VmThrow) {
        throwVm("java/lang/ClassNotFoundException", name)
    }
    if (initialize) ensureInitialized(cls)
    return cls
}

internal fun Vm.classLoaderObject(): VmObject = singleton("java/lang/HostClassLoader")

internal fun Vm.resourceStream(path: String): VmObject? {
    val bytes = classPath.resources(path).firstOrNull() ?: return null
    val stream = newVmObject("java/io/ByteArrayInputStream")
    stream.native = ByteInput(bytes, 0, bytes.size)
    return stream
}

private fun Vm.constructorOf(cls: VmClass, parameterTypes: VmRefArray?): ReflectedMember {
    val descriptor = descriptorsOf(parameterTypes).joinToString("", "(", ")V")
    val ctor = cls.methods["<init>$descriptor"]
        ?: throwVm("java/lang/NoSuchMethodException", "${cls.javaName}.<init>$descriptor")
    return ReflectedMember(loadClass("java/lang/reflect/Constructor"), ctor)
}

private fun Vm.descriptorsOf(types: VmRefArray?): List<String> =
    types?.data?.map { ((it as VmObject).native as VmClass).descriptor() } ?: emptyList()

private fun VmClass.descriptor(): String = when {
    isPrimitive -> primitive.toString()
    isArray -> name
    else -> "L$name;"
}

internal fun Vm.methodObject(m: VmMethod): ReflectedMember =
    ReflectedMember(loadClass(if (m.name == "<init>") "java/lang/reflect/Constructor" else "java/lang/reflect/Method"), m)

private fun Vm.fieldObject(f: VmField): ReflectedField = ReflectedField(loadClass("java/lang/reflect/Field"), f)

private fun Vm.memberArray(type: String, methods: List<VmMethod>): VmRefArray =
    newRefArray(type, methods.map { methodObject(it) }.toTypedArray())

private fun Vm.fieldArray(fields: List<VmField>): VmRefArray =
    newRefArray("java/lang/reflect/Field", fields.map { fieldObject(it) }.toTypedArray())

/** `getMethods()`: public members of the class chain and every superinterface, most-derived first. */
internal fun Vm.publicMethods(cls: VmClass): List<VmMethod> {
    val seen = HashSet<String>()
    val out = ArrayList<VmMethod>()
    fun visit(c: VmClass, interfacesOnly: Boolean) {
        for (m in c.methods.values) {
            if (m.name.startsWith("<") || m.access and ACC_PUBLIC == 0) continue
            if (interfacesOnly && m.isStatic) continue
            if (seen.add(m.key)) out.add(m)
        }
    }
    var c: VmClass? = cls
    while (c != null) { visit(c, false); c = c.superClass }
    val queue = ArrayDeque<VmClass>()
    c = cls
    while (c != null) { queue.addAll(c.interfaces); c = c.superClass }
    val visited = HashSet<VmClass>()
    while (queue.isNotEmpty()) {
        val i = queue.removeFirst()
        if (!visited.add(i)) continue
        visit(i, true)
        queue.addAll(i.interfaces)
    }
    return out
}

private fun Vm.publicFields(cls: VmClass): List<VmField> {
    val out = ArrayList<VmField>()
    var c: VmClass? = cls
    while (c != null) {
        out.addAll(c.fields.values.filter { it.access and ACC_PUBLIC != 0 })
        for (i in c.interfaces) out.addAll(i.fields.values)
        c = c.superClass
    }
    return out
}

private fun Vm.parameterClasses(m: VmMethod): VmRefArray =
    newRefArray("java/lang/Class", parameterDescriptors(m.descriptor).map { mirror(classForDescriptor(it)) }.toTypedArray())

/** Boxed arguments as [callMethod] takes them: a primitive parameter accepts any box that widens to it. */
internal fun Vm.unboxForCall(m: VmMethod, args: Array<Any?>): Array<Any?> {
    val params = parameterDescriptors(m.descriptor)
    if (params.size != args.size) throwVm("java/lang/IllegalArgumentException", "wrong number of arguments: ${args.size} expected: ${params.size}")
    return Array(args.size) { k ->
        val a = args[k]
        when (params[k]) {
            "I" -> numberArg(a)?.toInt() ?: (a as? Char)?.code ?: illegal(a, "int")
            "J" -> numberArg(a)?.toLong() ?: (a as? Char)?.code?.toLong() ?: illegal(a, "long")
            "F" -> numberArg(a)?.toFloat() ?: illegal(a, "float")
            "D" -> numberArg(a)?.toDouble() ?: illegal(a, "double")
            "S" -> (a as? Short)?.toInt() ?: (a as? Byte)?.toInt() ?: illegal(a, "short")
            "B" -> (a as? Byte)?.toInt() ?: illegal(a, "byte")
            "C" -> a as? Char ?: illegal(a, "char")
            "Z" -> a as? Boolean ?: illegal(a, "boolean")
            else -> a
        }
    }
}

private fun numberArg(a: Any?): Number? = when (a) {
    is Int, is Short, is Byte -> (a as Number)
    is Long -> a
    is Float -> a
    is Double -> a
    else -> null
}

private fun Vm.illegal(a: Any?, type: String): Nothing =
    throwVm("java/lang/IllegalArgumentException", "argument type mismatch: ${a?.let { classOf(it).javaName }} for $type")

/** Runs [block], wrapping an interpreted exception in an `InvocationTargetException` as reflection must. */
internal inline fun Vm.wrapTargetException(block: () -> Any?): Any? = try {
    block()
} catch (e: VmThrow) {
    val wrapper = newThrowable("java/lang/reflect/InvocationTargetException", null)
    (wrapper.native as ThrowableState).apply { cause = e.obj; causeSet = true }
    throw VmThrow(wrapper).also { it.trace.addAll(e.trace) }
}

/** `Method.invoke`: virtual for an instance method, as on the JVM; `void` comes back as null. */
internal fun Vm.reflectInvoke(m: VmMethod, receiver: Any?, args: Array<Any?>): Any? {
    val unboxed = unboxForCall(m, args)
    val target = if (m.isStatic) {
        ensureInitialized(m.owner)
        m
    } else {
        if (receiver == null) throwVm("java/lang/NullPointerException", "invoke ${m.name} on null")
        if (!isInstance(receiver, m.owner)) throwVm("java/lang/IllegalArgumentException", "object is not an instance of declaring class")
        if (m.access and ACC_PRIVATE != 0) m else selectVirtual(classOf(receiver), m.key) ?: m
    }
    val all: Array<Any?> = if (target.isStatic) unboxed else arrayOfNulls<Any?>(unboxed.size + 1).also {
        it[0] = receiver
        unboxed.copyInto(it, 1)
    }
    val result = wrapTargetException { callMethod(target, *all) }
    return if (result === Unit && target.retKind == K_VOID) null else result
}

internal fun Vm.readField(f: VmField, receiver: Any?): Any? {
    val (prims, refs) = fieldStore(f, receiver)
    return if (f.isRef) refs[f.slot] else boxPrim(f.descriptor[0], prims[f.slot])
}

internal fun Vm.writeField(f: VmField, receiver: Any?, value: Any?) {
    val (prims, refs) = fieldStore(f, receiver)
    if (f.isRef) { refs[f.slot] = value; return }
    prims[f.slot] = when (f.descriptor[0]) {
        'F' -> ((value as Number).toFloat()).toRawBits().toLong()
        'D' -> ((value as Number).toDouble()).toRawBits()
        'Z' -> if (value as Boolean) 1L else 0L
        'C' -> (value as Char).code.toLong()
        'J' -> (value as Number).toLong()
        else -> (value as Number).toLong()
    }
}

private fun Vm.fieldStore(f: VmField, receiver: Any?): Pair<LongArray, Array<Any?>> = if (f.isStatic) {
    ensureInitialized(f.owner)
    f.owner.staticPrims to f.owner.staticRefs
} else {
    val o = receiver as? VmObject ?: throwVm(if (receiver == null) "java/lang/NullPointerException" else "java/lang/IllegalArgumentException", "field ${f.name}")
    o.prims to o.refs
}

internal fun boxPrim(kind: Char, v: Long): Any = when (kind) {
    'Z' -> v != 0L
    'C' -> v.toInt().toChar()
    'B' -> v.toInt().toByte()
    'S' -> v.toInt().toShort()
    'I' -> v.toInt()
    'J' -> v
    'F' -> Float.fromBits(v.toInt())
    'D' -> Double.fromBits(v)
    else -> error("not primitive: $kind")
}

private fun arrayElementsAny(vm: Vm, a: Any?): List<Any?> = when (a) {
    is VmRefArray -> a.data.asList()
    is IntArray -> a.toList()
    is LongArray -> a.toList()
    is CharArray -> a.toList()
    is ByteArray -> a.toList()
    is ShortArray -> a.toList()
    is FloatArray -> a.toList()
    is DoubleArray -> a.toList()
    is BooleanArray -> a.toList()
    null -> vm.throwVm("java/lang/NullPointerException", "array")
    else -> vm.throwVm("java/lang/IllegalArgumentException", "Argument is not an array")
}

private fun arraySet(vm: Vm, a: Any?, i: Int, v: Any?) {
    when (a) {
        is VmRefArray -> a.data[i] = v
        is IntArray -> a[i] = (v as Number).toInt()
        is LongArray -> a[i] = (v as Number).toLong()
        is FloatArray -> a[i] = (v as Number).toFloat()
        is DoubleArray -> a[i] = (v as Number).toDouble()
        is ByteArray -> a[i] = (v as Number).toByte()
        is ShortArray -> a[i] = (v as Number).toShort()
        is CharArray -> a[i] = v as Char
        is BooleanArray -> a[i] = v as Boolean
        else -> vm.throwVm("java/lang/IllegalArgumentException", "Argument is not an array")
    }
}

/**
 * `Proxy.newProxyInstance`: a class defined on the spot that extends `Proxy`, implements [interfaces], and
 * answers every interface method (and `equals`/`hashCode`/`toString`) by calling the handler with the
 * `Method` and the boxed arguments. One class per interface set.
 */
private fun Vm.newProxy(interfaces: List<VmClass>, handler: Any?): VmObject {
    val key = "\u0000proxy:" + interfaces.joinToString(",") { it.name }
    val cls = proxyClasses.getOrPut(key) {
        val proxyBase = loadClass("java/lang/reflect/Proxy")
        val c = VmClass(this, "jdk/proxy/\$Proxy${proxyClasses.size}")
        c.isNative = true
        c.access = ACC_PUBLIC or ACC_FINAL
        c.superClass = proxyBase
        c.interfaces = interfaces.toTypedArray()
        c.primSlots = proxyBase.primSlots
        c.refSlots = proxyBase.refSlots
        c.initState = INIT_DONE
        val methods = LinkedHashMap<String, VmMethod>()
        for (m in publicMethods(objectClass)) if (m.name == "equals" || m.name == "hashCode" || m.name == "toString") methods[m.key] = m
        for (i in interfaces) for (m in publicMethods(i)) if (!m.isStatic && m.key !in methods) methods[m.key] = m
        for ((methodKey, declared) in methods) {
            val pm = VmMethod(c, declared.name, declared.descriptor, ACC_PUBLIC)
            val reflected = methodObject(declared)
            pm.native = Native { call -> proxyCall(call, pm, reflected) }
            c.methods[methodKey] = pm
        }
        c
    }
    val o = VmObject(cls)
    o.refs[0] = handler
    return o
}

private fun Vm.proxyCall(call: Call, pm: VmMethod, reflected: ReflectedMember) {
    val params = parameterDescriptors(pm.descriptor)
    var w = 1
    val args = Array<Any?>(params.size) { k ->
        val d = params[k]
        val v: Any? = when (d[0]) {
            'L', '[' -> call.r(w)
            'J' -> call.l(w)
            'D' -> call.d(w)
            'F' -> call.f(w)
            'Z' -> call.z(w)
            'C' -> call.c(w)
            'B' -> call.i(w).toByte()
            'S' -> call.i(w).toShort()
            else -> call.i(w)
        }
        w += if (d == "J" || d == "D") 2 else 1
        v
    }
    val self = call.self()
    val handler = self.refs[0]
    val argArray = if (args.isEmpty()) null else newRefArray("java/lang/Object", args)
    val result = callVirtual(handler, "invoke", "(Ljava/lang/Object;Ljava/lang/reflect/Method;[Ljava/lang/Object;)Ljava/lang/Object;", self, reflected, argArray)
    when (val r = returnDescriptor(pm.descriptor)[0]) {
        'V' -> {}
        'L', '[' -> call.retRef(result)
        else -> {
            if (result == null) throwVm("java/lang/NullPointerException", "proxy returned null for a primitive")
            when (r) {
                'Z' -> call.ret(result as Boolean)
                'C' -> call.ret(result as Char)
                'J' -> call.ret((result as Number).toLong())
                'F' -> call.ret((result as Number).toFloat())
                'D' -> call.ret((result as Number).toDouble())
                else -> call.ret((result as Number).toInt())
            }
        }
    }
}

/** Allocates an instance of [ctor]'s class and runs [ctor] with already-unboxed [args]. */
internal fun Vm.construct(ctor: VmMethod, args: Array<Any?>): Any? {
    val cls = ctor.owner
    ensureInitialized(cls)
    if (cls.isAbstract) throwVm("java/lang/InstantiationException", cls.javaName)
    val receiver: Any = if (cls.valueBacked) Uninit(cls) else VmObject(cls)
    val all = arrayOfNulls<Any?>(args.size + 1)
    all[0] = receiver
    args.copyInto(all, 1)
    if (receiver is Uninit) return callConstructorForValue(ctor, *all)
    callMethod(ctor, *all)
    return receiver
}

private fun Vm.serviceLoader(service: VmClass): VmObject {
    val names = classPath.resources("META-INF/services/${service.javaName}")
        .flatMap { it.decodeToString().lines() }
        .map { it.substringBefore('#').trim() }
        .filter { it.isNotEmpty() }
        .distinct()
    val loader = newVmObject("java/util/ServiceLoader")
    loader.native = names
    return loader
}
