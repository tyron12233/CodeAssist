package dev.ide.embedded.gen

import dev.ide.ui.backend.IdeBackend
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import kotlin.reflect.KCallable
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.KMutableProperty
import kotlin.reflect.KParameter
import kotlin.reflect.KProperty
import kotlin.reflect.KType
import kotlin.reflect.KTypeParameter
import kotlin.reflect.KTypeProjection
import kotlin.reflect.KVisibility
import kotlin.reflect.full.createType
import kotlin.reflect.full.isSubclassOf
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.full.withNullability
import kotlin.reflect.jvm.javaField

/**
 * Writes the two ends of the whole backend across a byte channel, from `IdeBackend` as it is compiled.
 *
 * Every service of `IdeBackend` is walked and every member given a number. A member whose arguments and
 * result are all values this can write (primitives, strings, collections, enums, classes made of those, sealed
 * families of them) is carried: the near side (`RemoteIdeBackend`) writes the call, the far side
 * (`RemoteBackendServer`) makes it on the real backend. A member that is a flow is watched. A member with
 * anything else in it (a function, a composable, an interface with no closed set of implementations) cannot
 * cross, and the near side answers it from the host's own backend instead; [report] lists those, with why.
 *
 * Run it with `./gradlew :ide-embedded:generateRemoteBackend`. The output is checked in, and a test says when it
 * no longer matches the interfaces.
 */
class RemoteBackendGenerator {
    private class Unsupported(val why: String) : Exception(why)

    // ---- types -----------------------------------------------------------------------------------------

    /** A class this writes a pair of functions for, named by its number. */
    private class ClassCodec(val type: KType, val index: Int) {
        var write = ""
        var read = ""
    }

    private val codecs = LinkedHashMap<String, ClassCodec>()
    private val refused = HashMap<String, String>()
    private val pending = ArrayDeque<ClassCodec>()
    private var locals = 0

    private fun KType.render(): String = toString().replace("!", "")
    private fun KType.kclass(): KClass<*>? = classifier as? KClass<*>

    private fun KType.substitute(map: Map<KTypeParameter, KType>): KType {
        val c = classifier
        if (c is KTypeParameter) {
            val actual = map[c] ?: return this
            return if (isMarkedNullable) actual.withNullability(true) else actual
        }
        if (arguments.isEmpty() || c !is KClass<*>) return this
        return c.createType(arguments.map { a -> a.type?.let { KTypeProjection(a.variance, it.substitute(map)) } ?: a }, isMarkedNullable)
    }

    private fun KType.argument(i: Int): KType = arguments.getOrNull(i)?.type ?: throw Unsupported("a star projection in ${render()}")

    private val primitives = mapOf(
        Int::class to ("o.int(%s)" to "i.int()"),
        Long::class to ("o.long(%s)" to "i.long()"),
        Boolean::class to ("o.bool(%s)" to "i.bool()"),
        String::class to ("o.string(%s)" to "i.str()"),
        Double::class to ("o.double(%s)" to "i.double()"),
        Float::class to ("o.float(%s)" to "i.float()"),
        Char::class to ("o.int(%s.code)" to "i.int().toChar()"),
        Byte::class to ("o.int(%s.toInt())" to "i.int().toByte()"),
        Short::class to ("o.int(%s.toInt())" to "i.int().toShort()"),
        ULong::class to ("o.long(%s.toLong())" to "i.long().toULong()"),
        UInt::class to ("o.int(%s.toInt())" to "i.int().toUInt()"),
        ByteArray::class to ("o.bytes(%s)" to "i.bytes()"),
    )

    /** Throws [Unsupported] when a value of [type] cannot be written. */
    private fun check(type: KType) {
        val c = type.kclass() ?: throw Unsupported("the type parameter ${type.render()}")
        when {
            c == Unit::class || c == Any::class || c in primitives -> {}
            c == List::class || c == Set::class || c == Collection::class || c == Iterable::class ||
                c == java.util.List::class || c == java.util.Set::class -> check(type.argument(0))
            c == Map::class -> { check(type.argument(0)); check(type.argument(1)) }
            c == Pair::class -> { check(type.argument(0)); check(type.argument(1)) }
            c == Triple::class -> { check(type.argument(0)); check(type.argument(1)); check(type.argument(2)) }
            c.java.isEnum -> {}
            else -> codecFor(type)
        }
    }

    /** Statements writing [value], which is of [type], to `o`. */
    private fun write(type: KType, value: String): String {
        if (type.kclass() == Any::class) return "o.any($value)"
        if (type.isMarkedNullable) {
            val n = "n${++locals}"
            return "o.opt($value) { $n -> ${write(type.withNullability(false), n)} }"
        }
        val c = type.kclass()!!
        primitives[c]?.let { return it.first.format(value) }
        return when {
            c == Unit::class -> ""
            c == List::class || c == java.util.List::class -> { val e = "e${++locals}"; "o.list($value) { $e -> ${write(type.argument(0), e)} }" }
            c == Set::class || c == Collection::class || c == Iterable::class || c == java.util.Set::class -> {
                val e = "e${++locals}"; "o.list($value.toList()) { $e -> ${write(type.argument(0), e)} }"
            }
            c == Map::class -> {
                val k = "k${++locals}"; val v = "v${++locals}"
                "o.map($value, { $k -> ${write(type.argument(0), k)} }, { $v -> ${write(type.argument(1), v)} })"
            }
            c == Pair::class -> "${write(type.argument(0), "$value.first")}; ${write(type.argument(1), "$value.second")}"
            c == Triple::class -> "${write(type.argument(0), "$value.first")}; ${write(type.argument(1), "$value.second")}; ${write(type.argument(2), "$value.third")}"
            c.java.isEnum -> "o.int($value.ordinal)"
            else -> "w${codecFor(type).index}(o, $value)"
        }
    }

    /** An expression reading a value of [type] from `i`. */
    private fun read(type: KType): String {
        if (type.kclass() == Any::class) return if (type.isMarkedNullable) "i.any()" else "i.any()!!"
        if (type.isMarkedNullable) return "i.opt { ${read(type.withNullability(false))} }"
        val c = type.kclass()!!
        primitives[c]?.let { return it.second }
        return when {
            c == Unit::class -> "Unit"
            c == List::class || c == Collection::class || c == Iterable::class || c == java.util.List::class -> "i.list { ${read(type.argument(0))} }"
            c == Set::class || c == java.util.Set::class -> "i.list { ${read(type.argument(0))} }.toSet()"
            c == Map::class -> "i.map({ ${read(type.argument(0))} }, { ${read(type.argument(1))} })"
            c == Pair::class -> "Pair(${read(type.argument(0))}, ${read(type.argument(1))})"
            c == Triple::class -> "Triple(${read(type.argument(0))}, ${read(type.argument(1))}, ${read(type.argument(2))})"
            c.java.isEnum -> "${c.qualifiedName}.entries[i.int()]"
            else -> "r${codecFor(type).index}(i)"
        }
    }

    private fun codecFor(type: KType): ClassCodec {
        val plain = type.withNullability(false)
        val key = plain.render()
        refused[key]?.let { throw Unsupported(it) }
        codecs[key]?.let { return it }
        val c = plain.kclass()!!
        try {
            preflight(c, plain)
        } catch (u: Unsupported) {
            refused[key] = u.why
            throw u
        }
        val codec = ClassCodec(plain, codecs.size)
        codecs[key] = codec
        try {
            fields(c, plain).forEach { check(it.second) }
            if (c.isSealed) c.sealedSubclasses.forEach { check(subtype(it, plain)) }
        } catch (u: Unsupported) {
            // Whatever was accepted on the way here on the strength of this one stays accepted only if it
            // stands by itself; simplest is to start over without it, which the driver does.
            codecs.remove(key)
            refused[key] = "${c.simpleName}: ${u.why}"
            throw Unsupported(refused[key]!!).also { poisoned = true }
        }
        pending.addLast(codec)
        return codec
    }

    /** Set when a class was refused after others had been accepted on its account: the run starts again. */
    private var poisoned = false

    private fun preflight(c: KClass<*>, type: KType) {
        val name = c.qualifiedName ?: throw Unsupported("an anonymous class")
        if (c.isSubclassOf(Function::class)) throw Unsupported("a function (${type.render()})")
        if (c.isSubclassOf(Flow::class)) throw Unsupported("a flow inside a value (${type.render()})")
        if (c.isSubclassOf(Throwable::class)) throw Unsupported("an exception ($name)")
        if (c == Any::class) throw Unsupported("Any")
        if (c.objectInstance != null) return
        if (c.isSealed) {
            if (c.sealedSubclasses.isEmpty()) throw Unsupported("$name is sealed with nothing in it")
            return
        }
        if (c.java.isInterface || c.isAbstract) throw Unsupported("$name is open: no closed set of implementations")
        if (!name.startsWith("dev.ide.") && !name.startsWith("kotlin.")) throw Unsupported("$name is not the IDE's own")
        val ctor = c.primaryConstructor ?: throw Unsupported("$name has no primary constructor")
        if (ctor.visibility != KVisibility.PUBLIC) throw Unsupported("$name cannot be constructed from outside")
        for (p in ctor.parameters) {
            val property = c.memberProperties.firstOrNull { it.name == p.name }
            if (property == null || property.visibility != KVisibility.PUBLIC) throw Unsupported("$name.${p.name} cannot be read back")
        }
    }

    /** The constructor's parameters of a plain class, as (name, type) with [type]'s arguments put in. */
    private fun fields(c: KClass<*>, type: KType): List<Pair<String, KType>> {
        if (c.objectInstance != null || c.isSealed) return emptyList()
        val map = c.typeParameters.zip(type.arguments.map { it.type ?: throw Unsupported("a star projection in ${type.render()}") }).toMap()
        return c.primaryConstructor!!.parameters.map { it.name!! to it.type.substitute(map) }
    }

    private fun subtype(sub: KClass<*>, parent: KType): KType {
        if (sub.typeParameters.isEmpty()) return sub.createType()
        // A generic member of a sealed family takes the family's own arguments, in order.
        if (sub.typeParameters.size == parent.arguments.size) return sub.createType(parent.arguments)
        throw Unsupported("${sub.qualifiedName} is generic in a way its sealed parent is not")
    }

    private fun emit(codec: ClassCodec) {
        val type = codec.type
        val c = type.kclass()!!
        val name = type.render()
        val n = codec.index
        when {
            c.objectInstance != null -> {
                codec.write = "private fun w$n(o: RemoteOut, v: $name) {}"
                codec.read = "private fun r$n(i: RemoteIn): $name = ${c.qualifiedName}"
            }
            c.isSealed -> {
                val subs = c.sealedSubclasses.sortedBy { it.qualifiedName }
                codec.write = buildString {
                    append("private fun w$n(o: RemoteOut, v: $name) {\n    when (v) {\n")
                    subs.forEachIndexed { index, sub ->
                        val t = subtype(sub, type)
                        append("        is ${t.render()} -> { o.int($index); ${write(t, "v")} }\n")
                    }
                    append("    }\n}")
                }
                codec.read = buildString {
                    append("private fun r$n(i: RemoteIn): $name = when (val tag = i.int()) {\n")
                    subs.forEachIndexed { index, sub -> append("    $index -> ${read(subtype(sub, type))}\n") }
                    append("    else -> error(\"unknown ${c.simpleName} \$tag\")\n}")
                }
            }
            else -> {
                val all = fields(c, type)
                codec.write = buildString {
                    append("private fun w$n(o: RemoteOut, v: $name) {\n")
                    for ((field, t) in all) append("    ${write(t, "v.${ident(field)}")}\n")
                    append("}")
                }
                codec.read = buildString {
                    append("private fun r$n(i: RemoteIn): $name = ${c.qualifiedName}(\n")
                    for ((field, t) in all) append("    ${ident(field)} = ${read(t)},\n")
                    append(")")
                }
            }
        }
    }

    // ---- services --------------------------------------------------------------------------------------

    private enum class Kind { CALL, VALUE, STATE_FLOW, EVENT_FLOW, SERVICE, LOCAL }

    private class Member(val callable: KCallable<*>, val kind: Kind, val why: String? = null) {
        var number = -1
        var service: Service? = null
    }

    private class Service(val kclass: KClass<*>, val number: Int) {
        val members = ArrayList<Member>()
    }

    private val services = LinkedHashMap<KClass<*>, Service>()
    val report = ArrayList<String>()

    private val hardKeywords = setOf(
        "as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if", "in", "interface", "is", "null",
        "object", "package", "return", "super", "this", "throw", "true", "try", "typealias", "typeof", "val", "var", "when", "while",
    )

    private fun ident(name: String) = if (name in hardKeywords) "`$name`" else name

    private fun isService(c: KClass<*>): Boolean =
        c.java.isInterface && !c.isSealed && c.qualifiedName?.startsWith("dev.ide.ui.backend.") == true && !c.isSubclassOf(Flow::class)

    private fun serviceFor(c: KClass<*>): Service {
        services[c]?.let { return it }
        val service = Service(c, services.size)
        services[c] = service
        val own = c.members
            .filter { it.name !in setOf("equals", "hashCode", "toString") }
            .filter { it !is KProperty<*> || it.javaField == null }
            .sortedWith(compareBy({ it.name }, { it.parameters.joinToString { p -> p.type.toString() } }))
        own.forEachIndexed { index, callable ->
            val member = classify(callable)
            member.number = index
            if (member.kind == Kind.LOCAL) report += "${c.simpleName}.${callable.name}: ${member.why}"
            service.members += member
        }
        return service
    }

    private fun classify(callable: KCallable<*>): Member = try {
        val returns = callable.returnType
        val rc = returns.kclass()
        val values = callable.parameters.filter { it.kind == KParameter.Kind.VALUE }
        if (callable.parameters.any { it.kind == KParameter.Kind.EXTENSION_RECEIVER }) throw Unsupported("an extension member")
        if (callable.typeParameters.isNotEmpty()) throw Unsupported("a generic member")
        if (values.any { it.isVararg }) throw Unsupported("a vararg parameter")
        val noArguments = values.isEmpty()
        when {
            rc != null && rc.isSubclassOf(Flow::class) -> {
                if (!noArguments) throw Unsupported("a flow made per call")
                if (returns.isMarkedNullable) throw Unsupported("a flow that may be absent")
                check(returns.argument(0))
                Member(callable, if (rc.isSubclassOf(StateFlow::class)) Kind.STATE_FLOW else Kind.EVENT_FLOW)
            }
            rc != null && isService(rc) && callable is KProperty<*> && !returns.isMarkedNullable ->
                Member(callable, Kind.SERVICE).also { it.service = serviceFor(rc) }
            else -> {
                values.forEach { check(it.type) }
                check(returns)
                Member(callable, if (callable is KProperty<*>) Kind.VALUE else Kind.CALL)
            }
        }
    } catch (u: Unsupported) {
        Member(callable, Kind.LOCAL, u.why)
    }

    private fun signature(callable: KCallable<*>): String {
        val values = callable.parameters.filter { it.kind == KParameter.Kind.VALUE }
        return when (callable) {
            is KProperty<*> -> "override ${if (callable is KMutableProperty<*>) "var" else "val"} ${ident(callable.name)}: ${callable.returnType.render()}"
            else -> buildString {
                append("override ")
                if (callable.isSuspend) append("suspend ")
                append("fun ")
                if (callable.typeParameters.isNotEmpty()) {
                    append(callable.typeParameters.joinToString(prefix = "<", postfix = "> ") { tp ->
                        val bound = tp.upperBounds.firstOrNull()?.takeIf { it.render() != "kotlin.Any?" }
                        if (bound != null) "${tp.name} : ${bound.render()}" else tp.name
                    })
                }
                callable.parameters.firstOrNull { it.kind == KParameter.Kind.EXTENSION_RECEIVER }?.let { append("${it.type.render()}.") }
                append(ident(callable.name))
                append(values.joinToString(prefix = "(", postfix = ")") { p ->
                    if (p.isVararg) "vararg ${ident(p.name!!)}: ${p.type.argument(0).render()}" else "${ident(p.name!!)}: ${p.type.render()}"
                })
                append(": ${callable.returnType.render()}")
            }
        }
    }

    private fun clientName(s: Service) = "Remote${s.kclass.simpleName}"

    private fun client(s: Service): String = buildString {
        val type = s.kclass.qualifiedName
        append("private class ${clientName(s)}(private val c: RemoteCalls, private val local: $type) : $type {\n")
        for (m in s.members) {
            val f = m.callable
            val sig = signature(f)
            val values = f.parameters.filter { it.kind == KParameter.Kind.VALUE }
            val at = "${s.number}, ${m.number}"
            when (m.kind) {
                Kind.SERVICE -> append("    $sig = ${clientName(m.service!!)}(c, local.${ident(f.name)})\n")
                Kind.STATE_FLOW, Kind.EVENT_FLOW -> {
                    val how = if (m.kind == Kind.STATE_FLOW) "stateFlow" else "events"
                    val made = "c.$how($at) { i -> ${read(f.returnType.argument(0))} }"
                    if (f is KProperty<*>) append("    $sig by lazy { $made }\n")
                    else {
                        append("    private val flow${m.number} by lazy { $made }\n")
                        append("    $sig = flow${m.number}\n")
                    }
                }
                Kind.VALUE -> {
                    append("    $sig\n        get() = c.call($at) {}.let { i -> ${read(f.returnType)} }\n")
                    if (f is KMutableProperty<*>) append("        set(value) { c.call($at + $SETTER) { o -> ${write(f.returnType, "value")} } }\n")
                }
                Kind.CALL -> {
                    val call = if (f.isSuspend) "callSuspend" else "call"
                    val writes = values.joinToString("; ") { write(it.type, ident(it.name!!)) }
                    val body = "c.$call($at) { o -> $writes }"
                    if (f.returnType.kclass() == Unit::class && !f.returnType.isMarkedNullable) append("    $sig { $body }\n")
                    else append("    $sig =\n        $body.let { i -> ${read(f.returnType)} }\n")
                }
                Kind.LOCAL -> {
                    append("    // Not carried: ${m.why}.\n")
                    when (f) {
                        is KMutableProperty<*> -> append("    $sig\n        get() = local.${ident(f.name)}\n        set(value) { local.${ident(f.name)} = value }\n")
                        is KProperty<*> -> append("    $sig get() = local.${ident(f.name)}\n")
                        else -> {
                            val receiver = f.parameters.firstOrNull { it.kind == KParameter.Kind.EXTENSION_RECEIVER }
                            val args = values.joinToString { p -> (if (p.isVararg) "*" else "") + ident(p.name!!) }
                            if (receiver != null) append("    $sig = with(local) { this@${ident(f.name)}.${ident(f.name)}($args) }\n")
                            else append("    $sig = local.${ident(f.name)}($args)\n")
                        }
                    }
                }
            }
        }
        append("}\n")
    }

    private fun server(): String = buildString {
        append("class RemoteBackendServer(private val backend: IdeBackend, scope: CoroutineScope) : RemoteServerRuntime(scope) {\n")
        val paths = servicePaths()
        append("    override suspend fun dispatch(service: Int, member: Int, input: RemoteIn, out: RemoteOut) {\n        when (service) {\n")
        for (s in services.values) append("            ${s.number} -> s${s.number}(${paths[s]}, member, input, out)\n")
        append("            else -> error(\"unknown service \$service\")\n        }\n    }\n\n")
        for (s in services.values) {
            append("    private suspend fun s${s.number}(t: ${s.kclass.qualifiedName}, member: Int, i: RemoteIn, o: RemoteOut) {\n        when (member) {\n")
            for (m in s.members) {
                val f = m.callable
                when (m.kind) {
                    Kind.VALUE -> {
                        append("            ${m.number} -> { val r = t.${ident(f.name)}; ${write(f.returnType, "r")} }\n")
                        if (f is KMutableProperty<*>) append("            ${m.number + SETTER} -> { t.${ident(f.name)} = ${read(f.returnType)} }\n")
                    }
                    Kind.CALL -> {
                        val values = f.parameters.filter { it.kind == KParameter.Kind.VALUE }
                        val reads = values.mapIndexed { index, p -> "val a$index = ${read(p.type)}; " }.joinToString("")
                        val args = values.indices.joinToString { "a$it" }
                        val unit = f.returnType.kclass() == Unit::class && !f.returnType.isMarkedNullable
                        if (unit) append("            ${m.number} -> { ${reads}t.${ident(f.name)}($args) }\n")
                        else append("            ${m.number} -> { ${reads}val r = t.${ident(f.name)}($args); ${write(f.returnType, "r")} }\n")
                    }
                    else -> {}
                }
            }
            append("            else -> error(\"${s.kclass.simpleName} has no member \$member that is called\")\n        }\n    }\n\n")
        }
        append("    override fun flowOf(service: Int, member: Int): Flow<*>? = when (service) {\n")
        for (s in services.values) {
            val flows = s.members.filter { it.kind == Kind.STATE_FLOW || it.kind == Kind.EVENT_FLOW }
            if (flows.isEmpty()) continue
            append("        ${s.number} -> when (member) {\n")
            for (m in flows) append("            ${m.number} -> ${paths[s]}.${ident(m.callable.name)}${if (m.callable is KFunction<*>) "()" else ""}\n")
            append("            else -> null\n        }\n")
        }
        append("        else -> null\n    }\n\n")
        append("    override fun writeFlowValue(service: Int, member: Int, value: Any?, out: RemoteOut) {\n        val o = out\n        when (service) {\n")
        for (s in services.values) {
            val flows = s.members.filter { it.kind == Kind.STATE_FLOW || it.kind == Kind.EVENT_FLOW }
            if (flows.isEmpty()) continue
            append("            ${s.number} -> when (member) {\n")
            for (m in flows) {
                val t = m.callable.returnType.argument(0)
                append("                ${m.number} -> { val v = value as ${t.render()}; ${write(t, "v")} }\n")
            }
            append("            }\n")
        }
        append("        }\n    }\n}\n")
    }

    /** How the far side reaches each service from the backend it was given. */
    private fun servicePaths(): Map<Service, String> {
        val paths = HashMap<Service, String>()
        fun walk(s: Service, path: String) {
            if (paths.putIfAbsent(s, path) != null) return
            for (m in s.members) if (m.kind == Kind.SERVICE) walk(m.service!!, "$path.${ident(m.callable.name)}")
        }
        walk(services.values.first(), "backend")
        return paths
    }

    /** The near side's file and the far side's. */
    fun generate(): Pair<String, String> {
        // A class refused after others were accepted because of it leaves those others standing; the refusals
        // are remembered and the walk done again until one comes out clean.
        while (true) {
            poisoned = false
            codecs.clear(); pending.clear(); services.clear(); report.clear(); locals = 0
            serviceFor(IdeBackend::class)
            if (!poisoned) break
        }
        // Bodies name other classes' functions, which may be new ones: until none are.
        val clients = services.values.map { client(it) }
        val serverBody = server()
        while (pending.isNotEmpty()) emit(pending.removeFirst())

        val header = "// GENERATED by RemoteBackendGenerator (./gradlew :ide-embedded:generateRemoteBackend). Do not edit.\n" +
            "@file:Suppress(\"UNCHECKED_CAST\", \"RedundantVisibilityModifier\", \"UNUSED_PARAMETER\", \"UNUSED_ANONYMOUS_PARAMETER\", \"RemoveRedundantQualifierName\", \"USELESS_CAST\", \"UNUSED_VARIABLE\", \"REDUNDANT_ELSE_IN_WHEN\")\n\n" +
            "package dev.ide.ui.backend.remote\n\n"
        val near = buildString {
            append(header)
            append("import dev.ide.ui.backend.IdeBackend\n\n")
            append("/**\n * `IdeBackend` answered by a backend on the far side of a byte channel. What cannot cross (see the\n")
            append(" * members marked \"Not carried\") is answered by [local], the host's own backend.\n */\n")
            append("fun remoteIdeBackend(calls: RemoteCalls, local: IdeBackend): IdeBackend = ${clientName(services.values.first())}(calls, local)\n\n")
            clients.forEach { append(it); append("\n") }
            append("// ---- values ----\n\n")
            for (codec in codecs.values) {
                append("internal ${codec.write.removePrefix("private ")}\n")
                append("internal ${codec.read.removePrefix("private ")}\n\n")
            }
        }
        val far = buildString {
            append(header)
            append("import dev.ide.ui.backend.IdeBackend\nimport kotlinx.coroutines.CoroutineScope\nimport kotlinx.coroutines.flow.Flow\n\n")
            append("/** Answers the near side's calls (`remoteIdeBackend`) from a real [backend]. */\n")
            append(serverBody)
        }
        return near to far
    }

    companion object {
        /** A property's setter is its number plus this. */
        const val SETTER = 100_000

        const val NEAR = "app/ide-ui-api/src/commonMain/kotlin/dev/ide/ui/backend/remote/RemoteIdeBackend.kt"
        const val FAR = "app/ide-ui-api/src/jvmShared/kotlin/dev/ide/ui/backend/remote/RemoteBackendServer.kt"

        /** `main(<repository root>)`: writes both files, and what is not carried to the standard output. */
        @JvmStatic
        fun main(args: Array<String>) {
            val root = File(args[0])
            val generator = RemoteBackendGenerator()
            val (near, far) = generator.generate()
            File(root, NEAR).writeText(near)
            File(root, FAR).writeText(far)
            println("${generator.services.size} services, ${generator.services.values.sumOf { it.members.size }} members, ${generator.codecs.size} classes")
            println("not carried (${generator.report.size}):")
            generator.report.forEach { println("  $it") }
        }
    }
}
