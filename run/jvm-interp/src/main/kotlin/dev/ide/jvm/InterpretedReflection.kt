package dev.ide.jvm

import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.signature.SignatureReader
import org.objectweb.asm.signature.SignatureVisitor
import org.objectweb.asm.tree.AnnotationNode
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldInsnNode
import org.objectweb.asm.tree.FieldNode
import org.objectweb.asm.tree.InsnNode
import org.objectweb.asm.tree.JumpInsnNode
import org.objectweb.asm.tree.LabelNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.VarInsnNode
import java.lang.reflect.Field
import java.lang.reflect.GenericArrayType
import java.lang.reflect.GenericDeclaration
import java.lang.reflect.Method
import java.lang.reflect.ParameterizedType
import java.lang.reflect.TypeVariable
import java.lang.reflect.WildcardType
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import java.lang.reflect.Type as JType

/**
 * Reflection over INTERPRETED types, as interpreted code performs it. Such a type has no real class: its class
 * literal (and `getClass()`) is a constructor-only stand-in ([PeerFactory.reflectionClass]) that platform code
 * sees unchanged. When interpreted code asks the stand-in for its members, this answers from the interpreted
 * class itself:
 *
 * - **Members.** `getDeclaredMethods`/`getMethods`/`getDeclaredMethod`/`getMethod` and the field equivalents
 *   return real [Method]/[Field] objects of a MIRROR class ([PeerFactory.reflectionMirror]) declaring the same
 *   members, built on first use. Every use of a mirrored member is serviced here: `invoke` runs the interpreted
 *   method (virtually dispatched), `get`/`set` read and write the interpreted field, and the declaring class,
 *   parameter/return/field types, modifiers and `toString` read as the interpreted ones.
 * - **Supertypes and generics.** `getSuperclass`/`getInterfaces`/`getModifiers`/`isInterface`, and
 *   `getGenericSuperclass`/`getGenericInterfaces`/`getTypeParameters` plus the generic parameter, return and
 *   field types, built from the `Signature` attributes (so `new TypeToken<List<Foo>>(){}` reads `List<Foo>`).
 * - **Annotations.** `getAnnotation`/`isAnnotationPresent`/`getAnnotations`/`getDeclaredAnnotations`/
 *   `getAnnotationsByType` on classes (with `@Inherited`), methods, fields and parameters, and
 *   `Method.getDefaultValue`. An annotation is an instance of a class generated per annotation type that
 *   implements it, holding the element values, so it works for an interpreted annotation type as for a real one.
 *
 * Platform code reflecting over a stand-in still sees only its constructors: nothing here changes what a
 * library that is not interpreted observes, and no mirror is generated unless interpreted code reflects.
 */
internal class InterpretedReflection(private val vm: Vm) {

    companion object {
        /** Returned by [intercept] when the call is not one this handles. */
        val NOT_HANDLED = Any()
        private const val INHERITED = "Ljava/lang/annotation/Inherited;"
        private const val ANNOTATION_ARRAY = "Ljava/lang/annotation/Annotation;"
    }

    /** One interpreted class's mirror: the real class, and its members mapped back to the interpreted ones. */
    private class Mirror(
        val internalName: String,
        val cls: Class<*>,
        val methods: Map<Method, MethodNode>,
        val fields: Map<String, FieldNode>,
    )

    private val mirrors = ConcurrentHashMap<String, Mirror>()
    private val byMirrorClass = ConcurrentHashMap<Class<*>, Mirror>()

    /** Dispatches a reflective call on [receiver]; [args] are real values (peers for interpreted objects). */
    fun intercept(receiver: Any?, name: String, descriptor: String, args: List<Any?>): Any? {
        // A handled call may legitimately answer null (a void invoke, a field set, an interface's superclass).
        when (receiver) {
            is Class<*> -> {
                val internal = vm.peerFactory.interpretedNameOf(receiver) ?: return NOT_HANDLED
                return onClass(internal, name, descriptor, args)
            }
            is Method -> {
                val mirror = byMirrorClass[receiver.declaringClass] ?: return NOT_HANDLED
                val node = mirror.methods[receiver] ?: return NOT_HANDLED
                return onMethod(mirror, receiver, node, name, args)
            }
            is Field -> {
                val mirror = byMirrorClass[receiver.declaringClass] ?: return NOT_HANDLED
                val node = mirror.fields[receiver.name] ?: return NOT_HANDLED
                return onField(mirror, receiver, node, name, args)
            }
            else -> return NOT_HANDLED
        }
    }

    // ---- classes ---------------------------------------------------------------------------------------

    private fun onClass(internal: String, name: String, descriptor: String, args: List<Any?>): Any? {
        val cls = vm.resolve(internal) ?: return NOT_HANDLED
        return when (name) {
            "getDeclaredMethods" -> mirror(cls).methods.keys.toTypedArray()
            "getMethods" -> publicMethods(cls).toTypedArray()
            "getDeclaredMethod" -> findMethod(mirror(cls).methods.keys, cls, args)
            "getMethod" -> findMethod(publicMethods(cls), cls, args)
            "getDeclaredFields" -> mirror(cls).fieldObjects().toTypedArray()
            "getFields" -> publicFields(cls).toTypedArray()
            "getDeclaredField" -> findField(mirror(cls).fieldObjects(), cls, args)
            "getField" -> findField(publicFields(cls), cls, args)
            "getSuperclass" -> if (cls.isInterface) null else cls.superName?.let(::classOfName) ?: Any::class.java
            "getInterfaces" -> cls.interfaces.map(::classOfName).toTypedArray()
            "getGenericSuperclass" -> if (cls.isInterface) null else classSignature(cls)?.superclass ?: cls.superName?.let(::classOfName)
            "getGenericInterfaces" -> classSignature(cls)?.interfaces?.toTypedArray() ?: cls.interfaces.map(::classOfName).toTypedArray()
            "getTypeParameters" -> classSignature(cls)?.typeParameters?.toTypedArray() ?: emptyArray<TypeVariable<*>>()
            "getModifiers" -> cls.access and Opcodes.ACC_SUPER.inv() and 0xFFFF
            "isInterface" -> Marshalling.realPrimToVm(cls.isInterface)
            "isAnnotation" -> Marshalling.realPrimToVm(cls.access and Opcodes.ACC_ANNOTATION != 0)
            else -> annotationQuery(name, descriptor, args, "class:$internal") { classAnnotations(cls) }
        }
    }

    private val VmClass.isInterface: Boolean get() = access and Opcodes.ACC_INTERFACE != 0

    /** [cls]'s declared methods as `getDeclaredMethods` returns them, each with the interpreted method it stands
     *  for (what a proxy over an interpreted interface hands its handler, see [InterpretedProxies]). */
    fun declaredMethods(cls: VmClass): Map<Method, MethodNode> = mirror(cls).methods

    /** The mirror of [cls], built the first time interpreted code reflects over its members. */
    private fun mirror(cls: VmClass): Mirror = mirrors[cls.name] ?: synchronized(this) {
        mirrors[cls.name] ?: buildMirror(cls).also { mirrors[cls.name] = it; byMirrorClass[it.cls] = it }
    }

    private fun buildMirror(cls: VmClass): Mirror {
        // A bridge method's realized descriptor can equal the real method's; the real one is declared first.
        val nodes = cls.methods.filter { it.name != "<init>" && it.name != "<clinit>" }
            .sortedBy { if (it.access and Opcodes.ACC_BRIDGE != 0) 1 else 0 }
        val methods = nodes.map { ReflectedMethod(it.name, realizedDescriptor(it.desc), it.access) }
        val fields = cls.fieldNodes.map { ReflectedField(it.name, vm.realizeType(it.desc), it.access) }
        val real = vm.peerFactory.reflectionMirror(cls.name, methods, fields)
        // Map each mirror method back to the interpreted method it was declared for (the factory may have widened
        // a parameter type it cannot load to Object).
        val byMethod = LinkedHashMap<Method, MethodNode>()
        for (m in real.declaredMethods) {
            val params = m.parameterTypes.map { Type.getDescriptor(it) }
            nodes.firstOrNull { n -> n.name == m.name && paramsMatch(Descriptors.paramTypes(n.desc), params) }?.let { byMethod[m] = it }
        }
        return Mirror(cls.name, real, byMethod, cls.fieldNodes.associateBy { it.name })
    }

    private fun Mirror.fieldObjects(): List<Field> = cls.declaredFields.filter { it.name in fields }

    private fun realizedDescriptor(desc: String): String {
        val ret = Descriptors.returnType(desc)
        return "(" + Descriptors.paramTypes(desc).joinToString("") { vm.realizeType(it) } + ")" + (if (ret == "V") ret else vm.realizeType(ret))
    }

    /** Whether interpreted parameter descriptors [interp] are the mirror's [mirrored] (Object standing for any
     *  reference type that could not be loaded). */
    private fun paramsMatch(interp: List<String>, mirrored: List<String>): Boolean =
        interp.size == mirrored.size && interp.indices.all { i ->
            vm.realizeType(interp[i]) == mirrored[i] || (mirrored[i] == "Ljava/lang/Object;" && interp[i].length > 1)
        }

    /** `getMethods()`: the public methods of [cls], its superclasses and its interfaces, a subclass's first. */
    private fun publicMethods(cls: VmClass): List<Method> {
        val out = LinkedHashMap<String, Method>()
        val seen = HashSet<String>()
        fun visit(internal: String, viaInterface: Boolean) {
            if (!seen.add(internal)) return
            val vc = vm.resolve(internal)
            if (vc == null) {
                val real = runCatching { vm.loadReal(internal) }.getOrNull() ?: return
                for (m in real.methods) {
                    if (viaInterface && java.lang.reflect.Modifier.isStatic(m.modifiers)) continue
                    out.putIfAbsent(m.name + Type.getMethodDescriptor(m).substringBefore(')'), m)
                }
                return
            }
            val mirror = mirror(vc)
            for ((m, node) in mirror.methods) {
                if (node.access and Opcodes.ACC_PUBLIC == 0) continue
                if (viaInterface && node.access and Opcodes.ACC_STATIC != 0) continue
                out.putIfAbsent(node.name + node.desc.substringBefore(')'), m)
            }
            if (!vc.isInterface) vc.superName?.let { visit(it, viaInterface) }
            vc.interfaces.forEach { visit(it, viaInterface = true) }
        }
        visit(cls.name, viaInterface = false)
        return out.values.toList()
    }

    /** `getFields()`: the public fields of [cls], its interfaces and superclasses. */
    private fun publicFields(cls: VmClass): List<Field> {
        val out = LinkedHashMap<String, Field>()
        val seen = HashSet<String>()
        fun visit(internal: String) {
            if (!seen.add(internal)) return
            val vc = vm.resolve(internal)
            if (vc == null) {
                runCatching { vm.loadReal(internal) }.getOrNull()?.fields?.forEach { out.putIfAbsent(it.name, it) }
                return
            }
            mirror(vc).fieldObjects().filter { java.lang.reflect.Modifier.isPublic(it.modifiers) }.forEach { out.putIfAbsent(it.name, it) }
            vc.interfaces.forEach(::visit)
            if (!vc.isInterface) vc.superName?.let(::visit)
        }
        visit(cls.name)
        return out.values.toList()
    }

    /** `getMethod(name, types…)` over [candidates]: the parameter types compared as the interpreted ones. */
    private fun findMethod(candidates: Collection<Method>, cls: VmClass, args: List<Any?>): Method {
        val name = args.getOrNull(0) as? String ?: throw VmException(NullPointerException("method name"))
        val wanted = (args.getOrNull(1) as? Array<*>).orEmpty().map { descOf(it as? Class<*> ?: Any::class.java) }
        return candidates.firstOrNull { m -> m.name == name && parameterDescs(m) == wanted }
            ?: throw VmException(NoSuchMethodException("${cls.name.replace('/', '.')}.$name(${wanted.joinToString(", ") { Type.getType(it).className }})"))
    }

    private fun findField(candidates: Collection<Field>, cls: VmClass, args: List<Any?>): Field {
        val name = args.getOrNull(0) as? String ?: throw VmException(NullPointerException("field name"))
        return candidates.firstOrNull { it.name == name } ?: throw VmException(NoSuchFieldException(name))
    }

    /** [m]'s parameter descriptors as the program declared them: the interpreted method's, for a mirrored one. */
    private fun parameterDescs(m: Method): List<String> =
        byMirrorClass[m.declaringClass]?.methods?.get(m)?.let { Descriptors.paramTypes(it.desc) }
            ?: m.parameterTypes.map { descOf(it) }

    // ---- methods ---------------------------------------------------------------------------------------

    private fun onMethod(mirror: Mirror, m: Method, node: MethodNode, name: String, args: List<Any?>): Any? = when (name) {
        "getDeclaringClass" -> classOfName(mirror.internalName)
        "invoke" -> invoke(mirror.internalName, node, args.getOrNull(0), (args.getOrNull(1) as? Array<*>)?.toList().orEmpty())
        "getParameterTypes" -> Descriptors.paramTypes(node.desc).map(::classOfDesc).toTypedArray()
        "getReturnType" -> classOfDesc(Descriptors.returnType(node.desc))
        "getExceptionTypes" -> node.exceptions.orEmpty().map(::classOfName).toTypedArray()
        "getGenericParameterTypes" -> (methodSignature(mirror.internalName, node, m)?.params ?: Descriptors.paramTypes(node.desc).map(::classOfDesc)).toTypedArray<JType>()
        "getGenericReturnType" -> methodSignature(mirror.internalName, node, m)?.returnType ?: classOfDesc(Descriptors.returnType(node.desc))
        "getTypeParameters" -> methodSignature(mirror.internalName, node, m)?.typeParameters?.toTypedArray() ?: emptyArray<TypeVariable<*>>()
        "getDefaultValue" -> node.annotationDefault?.let { boxed(annotationValue(it, Descriptors.returnType(node.desc)), Descriptors.returnType(node.desc)) }
        "getParameterAnnotations" -> parameterAnnotations(mirror.internalName, node)
        "toString" -> m.toString().replace(mirror.cls.name, mirror.internalName.replace('/', '.'))
        "toGenericString" -> m.toGenericString().replace(mirror.cls.name, mirror.internalName.replace('/', '.'))
        else -> annotationQuery(name, null, args, "method:${mirror.internalName}.${node.name}${node.desc}") {
            node.visibleAnnotations.orEmpty().mapNotNull(::annotationInstance)
        }
    }

    /** `Method.invoke`: the interpreted method run on [target] (its override, for an overridable method). Its
     *  failure arrives wrapped in `InvocationTargetException`, as reflection wraps it. */
    private fun invoke(internal: String, node: MethodNode, target: Any?, realArgs: List<Any?>): Any? {
        val cls = vm.resolve(internal) ?: throw VmUnsupportedException("$internal is not interpreted")
        val params = Descriptors.paramTypes(node.desc)
        if (realArgs.size != params.size) {
            throw VmException(IllegalArgumentException("wrong number of arguments: ${realArgs.size}, expected ${params.size}"))
        }
        val isStatic = node.access and Opcodes.ACC_STATIC != 0
        val vmTarget = target?.let { Marshalling.realToVm(it) }
        if (!isStatic && vmTarget == null) throw VmException(NullPointerException("${node.name} needs an instance"))
        // A lambda implementing the interface: its abstract method is the lambda body (a proxy handler forwarding
        // `method.invoke(delegate, args)` to a lambda delegate lands here).
        if (vmTarget is VmLambda && node.name == vmTarget.samName && node.access and Opcodes.ACC_ABSTRACT != 0) {
            val vmArgs = realArgs.mapIndexed { i, a -> Marshalling.realToVm(coerce(a, params[i]), params[i]) }
            val result = try {
                vmTarget.invokeSamReal(vmArgs)
            } catch (t: VmInterruptedException) {
                throw t
            } catch (t: Throwable) {
                throw VmException(java.lang.reflect.InvocationTargetException(t))
            }
            return Marshalling.realToVm(Marshalling.vmToReal(result, Descriptors.returnType(node.desc)))
        }
        val (declaring, method) = if (isStatic || node.access and Opcodes.ACC_PRIVATE != 0 || vmTarget !is VmObject) cls to node
            else vm.findInHierarchy(vmTarget.vmClass, node.name, node.desc) ?: (cls to node)
        val result = try {
            vm.invokeView(declaring, method, if (isStatic) null else target, realArgs.mapIndexed { i, a -> coerce(a, params[i]) },
                Descriptors.paramTypes(method.desc), Descriptors.returnType(method.desc))
        } catch (t: VmInterruptedException) {
            throw t // a cancelled run unwinds; it is not the method's failure
        } catch (t: Throwable) {
            throw VmException(java.lang.reflect.InvocationTargetException(t))
        }
        return Marshalling.realToVm(result)
    }

    private fun parameterAnnotations(internal: String, node: MethodNode): Any {
        val count = Descriptors.paramTypes(node.desc).size
        val visible = node.visibleParameterAnnotations
        val rows = (0 until count).map { i ->
            val list = cached("param:$internal.${node.name}${node.desc}#$i") { visible?.getOrNull(i).orEmpty().mapNotNull(::annotationInstance) }
            VmArray(ANNOTATION_ARRAY, list.toTypedArray())
        }
        return VmArray("[$ANNOTATION_ARRAY", rows.toTypedArray())
    }

    // ---- fields ----------------------------------------------------------------------------------------

    private fun onField(mirror: Mirror, f: Field, node: FieldNode, name: String, args: List<Any?>): Any? = when (name) {
        "getDeclaringClass" -> classOfName(mirror.internalName)
        "getType" -> classOfDesc(node.desc)
        "getGenericType" -> node.signature?.let { sig ->
            val scope = classScope(mirror.internalName)
            TypeParser(scope).also { SignatureReader(sig).acceptType(it.typeVisitor { t -> it.result = t }) }.result
        } ?: classOfDesc(node.desc)
        "get" -> boxed(read(mirror.internalName, node, args.getOrNull(0)), node.desc)
        "getInt", "getBoolean", "getByte", "getChar", "getShort" -> read(mirror.internalName, node, args.getOrNull(0))
        "getLong" -> (read(mirror.internalName, node, args.getOrNull(0)) as Number).toLong()
        "getFloat" -> (read(mirror.internalName, node, args.getOrNull(0)) as Number).toFloat()
        "getDouble" -> (read(mirror.internalName, node, args.getOrNull(0)) as Number).toDouble()
        "set", "setInt", "setBoolean", "setByte", "setChar", "setShort", "setLong", "setFloat", "setDouble" -> {
            write(mirror.internalName, node, args.getOrNull(0), coerce(args.getOrNull(1), node.desc)); null
        }
        "toString" -> f.toString().replace(mirror.cls.name, mirror.internalName.replace('/', '.'))
        "toGenericString" -> f.toGenericString().replace(mirror.cls.name, mirror.internalName.replace('/', '.'))
        else -> annotationQuery(name, null, args, "field:${mirror.internalName}.${node.name}") {
            node.visibleAnnotations.orEmpty().mapNotNull(::annotationInstance)
        }
    }

    /** The field's value in the interpreter's form. */
    @Suppress("UNCHECKED_CAST")
    private fun read(internal: String, node: FieldNode, target: Any?): Any? {
        val slot = if (node.access and Opcodes.ACC_STATIC != 0) {
            val holder = vm.resolve(internal)!!
            vm.ensureInitialized(holder)
            holder.statics[node.name]
        } else {
            instance(target, node).fields[node.name]
        }
        return if (node.access and Opcodes.ACC_VOLATILE != 0) (slot as AtomicReference<Any?>).get() else slot
    }

    @Suppress("UNCHECKED_CAST")
    private fun write(internal: String, node: FieldNode, target: Any?, value: Any?) {
        val slots = if (node.access and Opcodes.ACC_STATIC != 0) {
            val holder = vm.resolve(internal)!!
            vm.ensureInitialized(holder)
            holder.statics
        } else {
            instance(target, node).fields
        }
        if (node.access and Opcodes.ACC_VOLATILE != 0) (slots[node.name] as AtomicReference<Any?>).set(value)
        else slots[node.name] = value
    }

    private fun instance(target: Any?, node: FieldNode): VmObject {
        if (target == null) throw VmException(NullPointerException("field ${node.name} needs an instance"))
        val obj = Marshalling.realToVm(target) as? VmObject
        if (obj == null || node.name !in obj.fields) {
            throw VmException(IllegalArgumentException("cannot access field ${node.name} on ${target.javaClass.name}"))
        }
        return obj
    }

    /** A real value bound for a slot of [desc]: the interpreter's form, widened as reflection widens it. */
    private fun coerce(value: Any?, desc: String): Any? {
        val v = Marshalling.realToVm(value, desc)
        return when (desc) {
            "J" -> (v as? Number)?.toLong() ?: v
            "F" -> (v as? Number)?.toFloat() ?: v
            "D" -> (v as? Number)?.toDouble() ?: v
            else -> v
        }
    }

    /** A value in the interpreter's form for a slot of [desc], as the `Object` a reflective read returns. */
    private fun boxed(value: Any?, desc: String): Any? = when (desc) {
        "Z" -> (value as Int) != 0
        "B" -> (value as Int).toByte()
        "C" -> (value as Int).toChar()
        "S" -> (value as Int).toShort()
        else -> value
    }

    // ---- types -----------------------------------------------------------------------------------------

    /** The class a reflective result shows for internal name [internal]: its stand-in when interpreted, else the
     *  real class, else `Object` when neither exists. */
    private fun classOfName(internal: String): Class<*> =
        vm.classForInterpreted(internal) ?: runCatching { vm.loadReal(internal) }.getOrDefault(Any::class.java)

    private fun classOfDesc(desc: String): Class<*> = when (desc) {
        "V" -> Void.TYPE
        "Z" -> java.lang.Boolean.TYPE
        "B" -> java.lang.Byte.TYPE
        "C" -> Character.TYPE
        "S" -> java.lang.Short.TYPE
        "I" -> Integer.TYPE
        "J" -> java.lang.Long.TYPE
        "F" -> java.lang.Float.TYPE
        "D" -> java.lang.Double.TYPE
        else -> if (desc.startsWith("[")) arrayClass(classOfDesc(desc.substring(1)))
            else classOfName(desc.substring(1, desc.length - 1))
    }

    private fun arrayClass(component: Class<*>): Class<*> = java.lang.reflect.Array.newInstance(component, 0).javaClass

    /** The descriptor [c] stands for: an interpreted type's own name for its stand-in. */
    private fun descOf(c: Class<*>): String = when {
        c.isArray -> "[" + descOf(c.componentType)
        else -> vm.peerFactory.interpretedNameOf(c)?.let { "L$it;" } ?: Type.getDescriptor(c)
    }

    /** How a type prints: an interpreted one by its own name. */
    private fun typeName(t: JType): String = when (t) {
        is Class<*> -> if (t.isArray) typeName(t.componentType) + "[]" else vm.peerFactory.interpretedNameOf(t)?.replace('/', '.') ?: t.typeName
        else -> t.toString()
    }

    private class ClassSignature(val typeParameters: List<TypeVariable<*>>, val superclass: JType?, val interfaces: List<JType>)
    private class MethodSignature(val typeParameters: List<TypeVariable<*>>, val params: List<JType>, val returnType: JType)

    private val classSignatures = ConcurrentHashMap<String, Any>()
    private val methodSignatures = ConcurrentHashMap<String, Any>()
    private val NONE = Any()

    private fun classSignature(cls: VmClass): ClassSignature? = classSignatures.getOrPut(cls.name) {
        val sig = cls.signature ?: return@getOrPut NONE
        val parser = TypeParser(parent = null, declaration = classOfName(cls.name))
        SignatureReader(sig).accept(parser)
        ClassSignature(parser.formals, parser.superclass, parser.interfaces)
    } as? ClassSignature

    private fun classScope(internal: String): TypeParser? =
        vm.resolve(internal)?.let(::classSignature)?.let { sig -> TypeParser(null, classOfName(internal)).also { p -> p.formals += sig.typeParameters } }

    private fun methodSignature(internal: String, node: MethodNode, m: Method): MethodSignature? =
        methodSignatures.getOrPut("$internal.${node.name}${node.desc}") {
            val sig = node.signature ?: return@getOrPut NONE
            val parser = TypeParser(classScope(internal), m)
            SignatureReader(sig).accept(parser)
            MethodSignature(parser.formals, parser.params, parser.returnType ?: Void.TYPE)
        } as? MethodSignature

    /**
     * Reads a class, method or field signature into [JType]s: class types as their classes (stand-ins for
     * interpreted ones), parameterized ones as [VmParameterizedType], type variables resolved against the formals
     * this declares, then [parent]'s.
     */
    private inner class TypeParser(
        private val parent: TypeParser?,
        private val declaration: GenericDeclaration? = null,
    ) : SignatureVisitor(Opcodes.ASM9) {
        val formals = ArrayList<TypeVariable<*>>()
        var superclass: JType? = null
        val interfaces = ArrayList<JType>()
        val params = ArrayList<JType>()
        var returnType: JType? = null
        var result: JType? = null
        private var currentBounds: MutableList<JType>? = null

        fun variable(name: String): JType =
            formals.firstOrNull { it.name == name } ?: parent?.variable(name) ?: VmTypeVariable(name, declaration, mutableListOf(Any::class.java))

        override fun visitFormalTypeParameter(name: String) {
            val bounds = ArrayList<JType>()
            formals += VmTypeVariable(name, declaration, bounds)
            currentBounds = bounds
        }

        override fun visitClassBound(): SignatureVisitor = typeVisitor { currentBounds?.add(it) }
        override fun visitInterfaceBound(): SignatureVisitor = typeVisitor { currentBounds?.add(it) }
        override fun visitSuperclass(): SignatureVisitor = typeVisitor { superclass = it }
        override fun visitInterface(): SignatureVisitor = typeVisitor { interfaces += it }
        override fun visitParameterType(): SignatureVisitor = typeVisitor { params += it }
        override fun visitReturnType(): SignatureVisitor = typeVisitor { returnType = it }
        override fun visitExceptionType(): SignatureVisitor = typeVisitor { }

        fun typeVisitor(done: (JType) -> Unit): SignatureVisitor = TypeVisitor(done)

        private inner class TypeVisitor(private val done: (JType) -> Unit) : SignatureVisitor(Opcodes.ASM9) {
            private var className: String? = null
            private var owner: JType? = null
            private val args = ArrayList<JType>()

            override fun visitBaseType(descriptor: Char) = done(classOfDesc(descriptor.toString()))
            override fun visitTypeVariable(name: String) = done(variable(name))
            override fun visitArrayType(): SignatureVisitor = TypeVisitor { c ->
                done(if (c is Class<*>) arrayClass(c) else VmGenericArrayType(c, ::typeName))
            }
            override fun visitClassType(name: String) { className = name }
            override fun visitInnerClassType(name: String) {
                owner = current()
                className = "$className$$name"
                args.clear()
            }
            override fun visitTypeArgument() { args += VmWildcardType(arrayOf(Any::class.java), emptyArray(), ::typeName) }
            override fun visitTypeArgument(wildcard: Char): SignatureVisitor = TypeVisitor { t ->
                args += when (wildcard) {
                    SignatureVisitor.EXTENDS -> VmWildcardType(arrayOf(t), emptyArray(), ::typeName)
                    SignatureVisitor.SUPER -> VmWildcardType(arrayOf(Any::class.java), arrayOf(t), ::typeName)
                    else -> t
                }
            }
            override fun visitEnd() = done(current())

            private fun current(): JType {
                val raw = classOfName(className!!)
                return if (args.isEmpty() && owner == null) raw else VmParameterizedType(raw, owner, args.toTypedArray(), ::typeName)
            }
        }
    }

    private class VmParameterizedType(
        private val raw: Class<*>,
        private val owner: JType?,
        private val args: Array<JType>,
        private val names: (JType) -> String,
    ) : ParameterizedType {
        override fun getRawType(): JType = raw
        override fun getOwnerType(): JType? = owner
        override fun getActualTypeArguments(): Array<JType> = args.clone()
        override fun equals(other: Any?): Boolean = other is ParameterizedType && raw == other.rawType &&
            owner == other.ownerType && args.contentEquals(other.actualTypeArguments)
        override fun hashCode(): Int = args.contentHashCode() xor raw.hashCode() xor (owner?.hashCode() ?: 0)
        override fun toString(): String = names(raw) + args.joinToString(", ", "<", ">") { names(it) }
    }

    private class VmGenericArrayType(private val component: JType, private val names: (JType) -> String) : GenericArrayType {
        override fun getGenericComponentType(): JType = component
        override fun equals(other: Any?): Boolean = other is GenericArrayType && component == other.genericComponentType
        override fun hashCode(): Int = component.hashCode()
        override fun toString(): String = names(component) + "[]"
    }

    private class VmWildcardType(
        private val upper: Array<JType>,
        private val lower: Array<JType>,
        private val names: (JType) -> String,
    ) : WildcardType {
        override fun getUpperBounds(): Array<JType> = upper.clone()
        override fun getLowerBounds(): Array<JType> = lower.clone()
        override fun equals(other: Any?): Boolean = other is WildcardType &&
            upper.contentEquals(other.upperBounds) && lower.contentEquals(other.lowerBounds)
        override fun hashCode(): Int = upper.contentHashCode() xor lower.contentHashCode()
        override fun toString(): String = when {
            lower.isNotEmpty() -> "? super " + names(lower[0])
            upper.isEmpty() || upper[0] == Any::class.java -> "?"
            else -> "? extends " + names(upper[0])
        }
    }

    private class VmTypeVariable(
        private val name: String,
        private val declaration: GenericDeclaration?,
        private val bounds: List<JType>,
    ) : TypeVariable<GenericDeclaration> {
        override fun getName(): String = name
        override fun getBounds(): Array<JType> = bounds.ifEmpty { listOf(Any::class.java) }.toTypedArray()
        override fun getGenericDeclaration(): GenericDeclaration? = declaration
        override fun getAnnotatedBounds(): Array<java.lang.reflect.AnnotatedType> = emptyArray()
        override fun <T : Annotation?> getAnnotation(annotationClass: Class<T>?): T? = null
        override fun getAnnotations(): Array<Annotation> = emptyArray()
        override fun getDeclaredAnnotations(): Array<Annotation> = emptyArray()
        override fun equals(other: Any?): Boolean = other is TypeVariable<*> && name == other.name && declaration == other.genericDeclaration
        override fun hashCode(): Int = name.hashCode() xor (declaration?.hashCode() ?: 0)
        override fun toString(): String = name
    }

    // ---- annotations -----------------------------------------------------------------------------------

    private val annotationCache = ConcurrentHashMap<String, List<Any>>()

    private fun cached(key: String, make: () -> List<Any>): List<Any> = annotationCache.getOrPut(key, make)

    /**
     * Answers an AnnotatedElement query ([name]) against the annotations [all] gives (cached under [key]), or
     * [NOT_HANDLED] when [name] is not one.
     */
    private fun annotationQuery(name: String, descriptor: String?, args: List<Any?>, key: String, all: () -> List<Any>): Any? {
        fun list() = cached(key, all)
        fun typeArg(): String = descOf(args.getOrNull(0) as? Class<*> ?: throw VmException(NullPointerException("annotation type")))
        fun ofType(desc: String) = list().filter { annotationDesc(it) == desc }
        return when (name) {
            "getAnnotation", "getDeclaredAnnotation" -> ofType(typeArg()).firstOrNull()
            "isAnnotationPresent" -> Marshalling.realPrimToVm(ofType(typeArg()).isNotEmpty())
            "getAnnotations", "getDeclaredAnnotations" -> VmArray(ANNOTATION_ARRAY, list().toTypedArray())
            "getAnnotationsByType", "getDeclaredAnnotationsByType" -> typeArg().let { d -> VmArray(d, ofType(d).toTypedArray()) }
            else -> NOT_HANDLED
        }
    }

    /** A class's annotations: its own, then any `@Inherited` one of a superclass's that it does not redeclare. */
    private fun classAnnotations(cls: VmClass): List<Any> {
        val own = cls.visibleAnnotations.mapNotNull(::annotationInstance)
        val present = own.mapTo(HashSet()) { annotationDesc(it) }
        val inherited = ArrayList<Any>()
        var sup = cls.superName?.let { vm.resolve(it) }
        while (sup != null) {
            for (a in sup.visibleAnnotations) {
                if (a.desc !in present && isInherited(a.desc)) annotationInstance(a)?.let { inherited += it; present += a.desc }
            }
            sup = sup.superName?.let { vm.resolve(it) }
        }
        return own + inherited
    }

    private fun isInherited(desc: String): Boolean {
        val internal = desc.substring(1, desc.length - 1)
        vm.resolve(internal)?.let { t -> return t.visibleAnnotations.any { it.desc == INHERITED } }
        return runCatching { vm.loadReal(internal).isAnnotationPresent(java.lang.annotation.Inherited::class.java) }.getOrDefault(false)
    }

    /** The descriptor of the annotation type an instance made by [annotationInstance] implements. */
    private fun annotationDesc(a: Any): String? = ((a as? VmObject)?.vmClass?.interfaces?.firstOrNull())?.let { "L$it;" }

    /** An annotation element: its name, its value's descriptor and its default (ASM's form, or a real value). */
    private class Element(val name: String, val desc: String, val default: Any?, val realDefault: Boolean)

    private val annotationClasses = ConcurrentHashMap<String, Pair<VmClass, List<Element>>>()
    private val annotationCounter = java.util.concurrent.atomic.AtomicInteger()

    /** An instance of [node]'s annotation type holding its values (and the type's defaults), or null when the
     *  type is neither interpreted nor loadable, as the JVM skips an annotation whose type is missing. */
    private fun annotationInstance(node: AnnotationNode): Any? {
        val type = node.desc.substring(1, node.desc.length - 1)
        val (impl, elements) = annotationClass(type) ?: return null
        val given = HashMap<String, Any?>()
        node.values?.chunked(2)?.forEach { (k, v) -> given[k as String] = v }
        val obj = vm.newInstance(impl)
        val text = StringBuilder("@").append(type.replace('/', '.')).append('(')
        elements.forEachIndexed { i, e ->
            val value = when {
                e.name in given -> annotationValue(given[e.name], e.desc)
                e.realDefault -> Marshalling.realToVm(e.default, e.desc)
                e.default != null -> annotationValue(e.default, e.desc)
                else -> null
            }
            obj.fields[e.name] = value
            if (i > 0) text.append(", ")
            text.append(e.name).append('=').append(display(value))
        }
        obj.fields["\$text"] = text.append(')').toString()
        return obj
    }

    private fun display(v: Any?): String = when (v) {
        is VmArray -> v.data.joinToString(", ", "{", "}") { display(it) }
        is String -> "\"$v\""
        is Class<*> -> typeName(v) + ".class"
        is VmObject -> (v.fields["\$text"] as? String) ?: (v.fields["name"]?.toString() ?: v.toString())
        else -> v.toString()
    }

    /** The generated class implementing annotation [type], and its elements, or null when [type] is unknown. */
    private fun annotationClass(type: String): Pair<VmClass, List<Element>>? = annotationClasses[type] ?: synchronized(annotationClasses) {
        annotationClasses[type] ?: run {
            val elements = annotationElements(type) ?: return null
            val name = "dev/ide/jvm/annotation/" + type.replace('/', '_').replace('$', '_') + "_A" + annotationCounter.incrementAndGet()
            (vm.defineSynthetic(annotationClassNode(name, type, elements)) to elements).also { annotationClasses[type] = it }
        }
    }

    private fun annotationElements(type: String): List<Element>? {
        vm.resolve(type)?.let { t ->
            return t.methods.filter { it.access and Opcodes.ACC_STATIC == 0 && it.access and Opcodes.ACC_ABSTRACT != 0 }
                .map { Element(it.name, Descriptors.returnType(it.desc), it.annotationDefault, realDefault = false) }
        }
        val real = runCatching { vm.loadReal(type) }.getOrNull()?.takeIf { it.isAnnotation } ?: return null
        return real.declaredMethods.filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) }
            .map { Element(it.name, Type.getDescriptor(it.returnType), it.defaultValue, realDefault = true) }
    }

    /** `final class <name> implements <type>`: a field and an accessor per element, plus `annotationType()`,
     *  `toString()` (from a precomputed `$text`), and identity `equals`/`hashCode`. */
    private fun annotationClassNode(name: String, type: String, elements: List<Element>): ClassNode {
        val cn = ClassNode()
        cn.version = Opcodes.V1_8
        cn.access = Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL or Opcodes.ACC_SYNTHETIC
        cn.name = name
        cn.superName = "java/lang/Object"
        cn.interfaces = mutableListOf(type)
        fun method(n: String, d: String, build: MethodNode.() -> Unit) {
            cn.methods.add(MethodNode(Opcodes.ACC_PUBLIC, n, d, null, null).apply { build(); maxStack = 4; maxLocals = 4 })
        }
        method("<init>", "()V") {
            instructions.add(VarInsnNode(Opcodes.ALOAD, 0))
            instructions.add(MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false))
            instructions.add(InsnNode(Opcodes.RETURN))
        }
        for (e in elements) {
            cn.fields.add(FieldNode(Opcodes.ACC_PRIVATE, e.name, e.desc, null, null))
            method(e.name, "()${e.desc}") {
                instructions.add(VarInsnNode(Opcodes.ALOAD, 0))
                instructions.add(FieldInsnNode(Opcodes.GETFIELD, name, e.name, e.desc))
                instructions.add(InsnNode(Type.getType(e.desc).getOpcode(Opcodes.IRETURN)))
            }
        }
        cn.fields.add(FieldNode(Opcodes.ACC_PRIVATE, "\$text", "Ljava/lang/String;", null, null))
        method("annotationType", "()Ljava/lang/Class;") {
            instructions.add(LdcInsnNode(Type.getObjectType(type)))
            instructions.add(InsnNode(Opcodes.ARETURN))
        }
        method("toString", "()Ljava/lang/String;") {
            instructions.add(VarInsnNode(Opcodes.ALOAD, 0))
            instructions.add(FieldInsnNode(Opcodes.GETFIELD, name, "\$text", "Ljava/lang/String;"))
            instructions.add(InsnNode(Opcodes.ARETURN))
        }
        method("equals", "(Ljava/lang/Object;)Z") {
            val no = LabelNode()
            instructions.add(VarInsnNode(Opcodes.ALOAD, 0))
            instructions.add(VarInsnNode(Opcodes.ALOAD, 1))
            instructions.add(JumpInsnNode(Opcodes.IF_ACMPNE, no))
            instructions.add(InsnNode(Opcodes.ICONST_1))
            instructions.add(InsnNode(Opcodes.IRETURN))
            instructions.add(no)
            instructions.add(InsnNode(Opcodes.ICONST_0))
            instructions.add(InsnNode(Opcodes.IRETURN))
        }
        method("hashCode", "()I") {
            instructions.add(VarInsnNode(Opcodes.ALOAD, 0))
            instructions.add(MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/System", "identityHashCode", "(Ljava/lang/Object;)I", false))
            instructions.add(InsnNode(Opcodes.IRETURN))
        }
        return cn
    }

    /** An annotation value as ASM reads it ([v]) for an element of [desc], in the interpreter's form. */
    private fun annotationValue(v: Any?, desc: String): Any? = when (v) {
        null -> null
        is AnnotationNode -> annotationInstance(v)
        is Type -> when (v.sort) {
            Type.OBJECT, Type.ARRAY -> runCatching { vm.classLiteral(v) }.getOrDefault(Any::class.java)
            else -> classOfDesc(v.descriptor)
        }
        is Array<*> -> enumConstant(v[0] as String, v[1] as String) // ASM's {descriptor, name} for an enum
        is List<*> -> {
            val element = if (desc.startsWith("[")) desc.substring(1) else desc
            VmArray(element, v.map { annotationValue(it, element) }.toTypedArray())
        }
        is Boolean -> if (v) 1 else 0
        is Byte -> v.toInt()
        is Char -> v.code
        is Short -> v.toInt()
        else -> v // Int, Long, Float, Double, String
    }

    private fun enumConstant(desc: String, name: String): Any? {
        val internal = desc.substring(1, desc.length - 1)
        vm.resolve(internal)?.let { e ->
            vm.ensureInitialized(e)
            val slot = e.statics[name]
            return if (slot is AtomicReference<*>) slot.get() else slot
        }
        return runCatching { vm.loadReal(internal).enumConstants?.firstOrNull { (it as Enum<*>).name == name } }.getOrNull()
    }
}
