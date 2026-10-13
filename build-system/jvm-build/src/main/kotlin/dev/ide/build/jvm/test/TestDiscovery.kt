package dev.ide.build.jvm.test

import org.objectweb.asm.ClassReader
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.AnnotationNode
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodNode
import java.nio.file.Files
import java.nio.file.Path
import java.util.stream.Collectors
import java.util.zip.ZipFile

/** The test framework a class's tests are declared with. kotlin.test has none of its own: its annotations are
 *  typealiases that compile to one of these. */
enum class TestFramework { JUNIT4, JUNIT5, TESTNG }

/** A lifecycle method to call: [owner] is the internal name of the class declaring it (a superclass, for an
 *  inherited one). */
data class TestHook(val owner: String, val name: String, val descriptor: String, val isStatic: Boolean)

/** One argument fixed when the tests are found, already of its parameter's type. */
sealed interface ArgValue {
    /** A constant: a boxed primitive, a String, null, or an ASM [Type] for a class literal. */
    data class Const(val value: Any?) : ArgValue

    /** An enum constant. */
    data class EnumConst(val owner: String, val name: String) : ArgValue

    /** A value made from text by a static factory (`UUID.fromString`, `LocalDate.parse`) or, with [name]
     *  `<init>`, a constructor taking the text. */
    data class FromText(val owner: String, val name: String, val descriptor: String, val text: String) : ArgValue
}

/** One run of a parameterized or repeated test: its arguments and how its name reads (`[1] 2, "a"`). */
data class Invocation(val display: String, val args: List<ArgValue>)

/** A static method with no parameters whose result supplies parameter sets at run time (`@MethodSource`, JUnit 4's
 *  `@Parameters`): a Stream, Iterable, Iterator or array. */
data class FactoryRef(val owner: String, val name: String, val descriptor: String)

/**
 * One test method. [expected] is the internal name of the exception it must throw (JUnit 4 `expected`, TestNG
 * `expectedExceptions`), [skipReason] non-null when it is ignored or of a shape the runner does not support.
 *
 * A parameterized or repeated test runs once per [invocations] (fixed when found), then once per item [factory]
 * returns; [only] limits that to the invocations with these numbers, as their names number them.
 */
data class TestMethod(
    val owner: String,
    val name: String,
    val descriptor: String,
    val expected: String? = null,
    val skipReason: String? = null,
    val invocations: List<Invocation>? = null,
    val factory: FactoryRef? = null,
    val only: Set<Int>? = null,
) {
    /** Whether this test runs more than once, each run reported under its own name. */
    val parameterized: Boolean get() = invocations != null || factory != null
}

/** An enclosing class of a `@Nested` test class, outermost first: its instance encloses the next one's, and its
 *  per-test hooks run around the inner class's own. */
data class OuterLevel(val internalName: String, val beforeEach: List<TestHook>, val afterEach: List<TestHook>)

/** A field JUnit 4's `Parameterized` runner sets from parameter [index] (`@Parameter(index)`). */
data class ParamField(val owner: String, val name: String, val descriptor: String, val index: Int)

/**
 * A runnable test class: its [tests] and lifecycle [beforeEach]/[afterEach] (instance) and [beforeAll]/[afterAll]
 * hooks, in the order they run (a superclass's before-hooks first, its after-hooks last). [sharedInstance] is
 * true when ONE instance runs every test (TestNG, and JUnit 5's `@TestInstance(PER_CLASS)`), in which case the
 * all-hooks are instance methods. [skipReason] skips the whole class.
 */
data class TestClass(
    val internalName: String,
    val framework: TestFramework,
    val tests: List<TestMethod>,
    val beforeEach: List<TestHook> = emptyList(),
    val afterEach: List<TestHook> = emptyList(),
    val beforeAll: List<TestHook> = emptyList(),
    val afterAll: List<TestHook> = emptyList(),
    val sharedInstance: Boolean = false,
    val skipReason: String? = null,
    val outer: List<OuterLevel> = emptyList(),
    /** JUnit 4 `Parameterized`: the `@Parameters` factory; every test runs once per set it returns. */
    val classParams: FactoryRef? = null,
    /** How a parameter set reaches the instance: the constructor taking it ([paramConstructor]), or [paramFields]. */
    val paramConstructor: String? = null,
    val paramFields: List<ParamField> = emptyList(),
) {
    val fqn: String get() = internalName.replace('/', '.')
}

/**
 * Which tests to run: everything, one class, one method of it, or (with [anyOf]) a set of those, as "rerun the
 * failed tests" asks for.
 */
data class TestSelector(val classFqn: String? = null, val method: String? = null, val anyOf: List<TestSelector> = emptyList()) {
    fun matches(c: TestClass): Boolean =
        if (anyOf.isNotEmpty()) anyOf.any { it.matches(c) } else classFqn == null || c.fqn == classFqn

    /** [method] names a test, or one invocation of it (`add[2] 1, 2`: `[` cannot occur in a method name). */
    fun matches(m: TestMethod): Boolean = method == null || m.name == method || invocation(m) != null

    private fun invocation(m: TestMethod): Int? =
        method?.takeIf { it.startsWith(m.name + "[") }?.substring(m.name.length + 1)?.substringBefore(']')?.toIntOrNull()

    /** The invocation numbers of test [m] of [c] this selects, or null for all of them. */
    fun invocationsOf(c: TestClass, m: TestMethod): Set<Int>? {
        val picks = (if (anyOf.isNotEmpty()) anyOf else listOf(this)).filter { it.matches(c, m) }
        if (picks.isEmpty() || picks.any { it.invocation(m) == null }) return null
        return picks.mapNotNull { it.invocation(m) }.toSet()
    }

    /** Whether test [m] of class [c] is selected. */
    fun matches(c: TestClass, m: TestMethod): Boolean =
        if (anyOf.isNotEmpty()) anyOf.any { it.matches(c, m) } else matches(c) && matches(m)

    companion object {
        val ALL = TestSelector()
    }
}

/**
 * Finds the tests in compiled test classes by reading their annotations with ASM, so nothing is loaded or run to
 * find them, and the same answer comes out on the desktop and on ART.
 *
 * JUnit 4, JUnit 5 (Jupiter) and TestNG are recognised by annotation descriptor, with parameterized and repeated
 * tests, `@Nested` classes and JUnit 4's `Parameterized` runner. Shapes the generated runner cannot drive faithfully
 * (test factories and templates, other JUnit 4 runners, sources it cannot read) are reported as skipped with the
 * reason, rather than run wrongly or silently dropped.
 */
object TestDiscovery {

    /** Every test class under [testDirs], in a stable order; [lookup] finds a superclass's bytes anywhere on the
     *  test classpath (for inherited tests and hooks). */
    fun discover(testDirs: List<Path>, classpath: List<Path>): List<TestClass> {
        val lookup = ClassLookup(testDirs + classpath)
        try {
            return testDirs.filter { Files.isDirectory(it) }
                .flatMap { dir -> classFiles(dir) }
                .sorted()
                .mapNotNull { file -> read(Files.readAllBytes(file)) }
                .mapNotNull { node -> testClassOf(node, lookup) }
                .distinctBy { it.internalName }
        } finally {
            lookup.close()
        }
    }

    private fun classFiles(dir: Path): List<Path> =
        Files.walk(dir).use { s -> s.filter { it.toString().endsWith(".class") }.collect(Collectors.toList()) }

    internal fun read(bytes: ByteArray): ClassNode? = runCatching {
        ClassNode().also { ClassReader(bytes).accept(it, ClassReader.SKIP_CODE or ClassReader.SKIP_FRAMES) }
    }.getOrNull()

    /** The test class [node] declares, or null when it has no tests (or cannot be instantiated). */
    internal fun testClassOf(node: ClassNode, lookup: (String) -> ClassNode?): TestClass? {
        if (node.access and (Opcodes.ACC_ABSTRACT or Opcodes.ACC_INTERFACE or Opcodes.ACC_ANNOTATION) != 0) return null
        val chain = chainOf(node, lookup)
        val methods = chain.flatMap { cls -> cls.methods.orEmpty().map { cls.name to it } }
        val framework = frameworkOf(methods.map { it.second }) ?: return null
        val names = Names(framework)

        val tests = LinkedHashMap<String, TestMethod>() // a subclass override replaces the inherited test
        for ((owner, m) in methods) {
            val anns = annotations(m)
            when {
                anns.any { it.desc in names.test } -> tests[m.name + m.desc] = testMethod(owner, m, anns, framework)
                anns.any { it.desc == JUPITER_PARAMETERIZED } -> tests[m.name + m.desc] = parameterizedTest(owner, m, anns, lookup)
                anns.any { it.desc == JUPITER_REPEATED } -> tests[m.name + m.desc] = repeatedTest(owner, m, anns)
                anns.any { it.desc in names.unsupportedTests } ->
                    tests[m.name + m.desc] = TestMethod(owner, m.name, m.desc, skipReason = "${simple(anns.first { it.desc in names.unsupportedTests }.desc)} tests are not supported yet")
            }
        }
        if (tests.isEmpty()) return null
        val hooks = hooksOf(chain, names)

        val classAnns = chain.flatMap { annotations(it) }
        val shared = framework == TestFramework.TESTNG || classAnns.any { a ->
            a.desc == JUPITER_TEST_INSTANCE && enumValue(a, "value") == "PER_CLASS"
        }
        val nested = annotations(node).any { it.desc == JUPITER_NESTED }
        val outer = if (nested) outerChain(node, lookup, names) else emptyList()
        val runner = classAnns.firstOrNull { it.desc == JUNIT4_RUN_WITH }?.let { (value(it, "value") as? Type)?.internalName }
        val parameterized = runner == JUNIT4_PARAMETERIZED
        val params = if (parameterized) junit4Parameters(chain) else null
        val classSkip = when {
            node.access and Opcodes.ACC_PUBLIC == 0 && framework == TestFramework.JUNIT4 -> "JUnit 4 test classes must be public"
            classAnns.any { it.desc == JUNIT4_IGNORE || it.desc == JUPITER_DISABLED } -> reasonOf(classAnns) ?: "disabled"
            runner != null && runner !in PLAIN_JUNIT4_RUNNERS && !parameterized ->
                "the ${runner.substringAfterLast('/').substringAfterLast('$')} runner is not supported yet"
            parameterized && params == null -> "a Parameterized test needs a public static @Parameters method"
            nested && outer.isEmpty() -> "a @Nested class must be an inner class of a test class"
            nested && !hasConstructor(node, "(L${outer.last().internalName};)V") -> "a @Nested class needs a constructor without parameters"
            !nested && !parameterized && !hasNoArgConstructor(node) -> "a test class needs a constructor without parameters"
            else -> null
        }
        return TestClass(
            internalName = node.name,
            framework = framework,
            tests = tests.values.toList(),
            beforeEach = hooks.beforeEach,
            afterEach = hooks.afterEach,
            // A nested class runs its enclosing classes' class hooks too, as JUnit runs them around it.
            beforeAll = outer.flatMap { o -> lookup(o.internalName)?.let { hooksOf(chainOf(it, lookup), names).beforeAll }.orEmpty() } + hooks.beforeAll,
            afterAll = hooks.afterAll + outer.reversed().flatMap { o -> lookup(o.internalName)?.let { hooksOf(chainOf(it, lookup), names).afterAll }.orEmpty() },
            sharedInstance = shared,
            skipReason = classSkip,
            outer = outer,
            classParams = params?.first,
            paramConstructor = params?.second?.takeIf { params.third.isEmpty() },
            paramFields = params?.third.orEmpty(),
        )
    }

    /** [node] and its superclasses, the topmost first, so their hooks run in JUnit's order. */
    private fun chainOf(node: ClassNode, lookup: (String) -> ClassNode?): List<ClassNode> {
        val chain = ArrayList<ClassNode>()
        var c: ClassNode? = node
        while (c != null && chain.size < 32) {
            chain.add(0, c)
            val sup = c.superName ?: break
            if (sup == "java/lang/Object") break
            c = lookup(sup)
        }
        return chain
    }

    private class Hooks(
        val beforeEach: List<TestHook>, val afterEach: List<TestHook>,
        val beforeAll: List<TestHook>, val afterAll: List<TestHook>,
    )

    private fun hooksOf(chain: List<ClassNode>, names: Names): Hooks {
        val beforeEach = ArrayList<TestHook>()
        val afterEach = ArrayList<TestHook>()
        val beforeAll = ArrayList<TestHook>()
        val afterAll = ArrayList<TestHook>()
        for (cls in chain) for (m in cls.methods.orEmpty()) {
            val anns = annotations(m)
            val hook = TestHook(cls.name, m.name, m.desc, m.access and Opcodes.ACC_STATIC != 0)
            when {
                anns.any { it.desc == names.beforeEach } -> beforeEach += hook
                anns.any { it.desc == names.afterEach } -> afterEach.add(0, hook)
                anns.any { it.desc == names.beforeAll } -> beforeAll += hook
                anns.any { it.desc == names.afterAll } -> afterAll.add(0, hook)
            }
        }
        return Hooks(beforeEach, afterEach, beforeAll, afterAll)
    }

    /** The classes enclosing an inner (`@Nested`) class, outermost first; empty when it is not an inner class. */
    private fun outerChain(node: ClassNode, lookup: (String) -> ClassNode?, names: Names): List<OuterLevel> {
        val levels = ArrayList<OuterLevel>()
        var c = node
        while (levels.size < 16) {
            val entry = c.innerClasses.orEmpty().firstOrNull { it.name == c.name } ?: break
            if (entry.access and Opcodes.ACC_STATIC != 0) break // a static nested class has no enclosing instance
            val outerName = entry.outerName ?: break
            val outer = lookup(outerName) ?: break
            val hooks = hooksOf(chainOf(outer, lookup), names)
            levels.add(0, OuterLevel(outerName, hooks.beforeEach, hooks.afterEach))
            if (annotations(outer).none { it.desc == JUPITER_NESTED }) break
            c = outer
        }
        return levels
    }

    /** JUnit 4 `Parameterized`: the `@Parameters` factory, the constructor taking a set, and any `@Parameter` fields. */
    private fun junit4Parameters(chain: List<ClassNode>): Triple<FactoryRef, String?, List<ParamField>>? {
        val factory = chain.asReversed().firstNotNullOfOrNull { cls ->
            cls.methods.orEmpty().firstOrNull { m ->
                m.access and Opcodes.ACC_STATIC != 0 && Type.getArgumentTypes(m.desc).isEmpty() &&
                    annotations(m).any { it.desc == JUNIT4_PARAMETERS }
            }?.let { FactoryRef(cls.name, it.name, it.desc) }
        } ?: return null
        val fields = chain.flatMap { cls ->
            cls.fields.orEmpty().mapNotNull { f ->
                val ann = (f.visibleAnnotations.orEmpty() + f.invisibleAnnotations.orEmpty()).firstOrNull { it.desc == JUNIT4_PARAMETER }
                    ?: return@mapNotNull null
                ParamField(cls.name, f.name, f.desc, (value(ann, "value") as? Int) ?: 0)
            }
        }
        val node = chain.last()
        val ctor = node.methods.orEmpty().filter { it.name == "<init>" && it.access and Opcodes.ACC_PUBLIC != 0 }
            .maxByOrNull { Type.getArgumentTypes(it.desc).size }?.desc
        return Triple(factory, ctor, fields)
    }

    private fun testMethod(owner: String, m: MethodNode, anns: List<AnnotationNode>, framework: TestFramework): TestMethod {
        val test = anns.first { it.desc in Names(framework).test }
        val expected = when (framework) {
            TestFramework.JUNIT4 -> (value(test, "expected") as? Type)?.internalName
                ?.takeUnless { it == "org/junit/Test\$None" }
            TestFramework.TESTNG -> ((value(test, "expectedExceptions") as? List<*>)?.firstOrNull() as? Type)?.internalName
            TestFramework.JUNIT5 -> null
        }
        val skip = commonSkip(m, anns) ?: when {
            framework == TestFramework.TESTNG && value(test, "enabled") == false -> "disabled"
            Type.getArgumentTypes(m.desc).isNotEmpty() -> "test methods with parameters are not supported yet"
            else -> null
        }
        return TestMethod(owner, m.name, m.desc, expected, skip)
    }

    private fun commonSkip(m: MethodNode, anns: List<AnnotationNode>): String? = when {
        anns.any { it.desc == JUNIT4_IGNORE || it.desc == JUPITER_DISABLED } -> reasonOf(anns) ?: "disabled"
        m.access and Opcodes.ACC_STATIC != 0 -> "a test method cannot be static"
        else -> null
    }

    /** `@RepeatedTest(n)`: n runs, each named as JUnit names it. */
    private fun repeatedTest(owner: String, m: MethodNode, anns: List<AnnotationNode>): TestMethod {
        val n = (value(anns.first { it.desc == JUPITER_REPEATED }, "value") as? Int) ?: 1
        val skip = commonSkip(m, anns) ?: when {
            Type.getArgumentTypes(m.desc).isNotEmpty() -> "a @RepeatedTest taking RepetitionInfo is not supported yet"
            n < 1 -> "a @RepeatedTest needs at least one repetition"
            else -> null
        }
        return TestMethod(owner, m.name, m.desc, skipReason = skip, invocations = (1..maxOf(n, 1)).map { Invocation("repetition $it of $n", emptyList()) })
    }

    /**
     * `@ParameterizedTest`: the sets its sources give. The fixed sources (`@ValueSource`, `@CsvSource`,
     * `@EnumSource`, the null and empty sources) are converted to the parameter types here, as JUnit converts them;
     * a `@MethodSource` is called when the test runs.
     */
    private fun parameterizedTest(owner: String, m: MethodNode, anns: List<AnnotationNode>, lookup: (String) -> ClassNode?): TestMethod {
        val params = Type.getArgumentTypes(m.desc)
        fun skipped(reason: String) = TestMethod(owner, m.name, m.desc, skipReason = reason)
        commonSkip(m, anns)?.let { return skipped(it) }
        val raw = ArrayList<List<Any?>>()       // each set's values, before conversion
        var factory: FactoryRef? = null
        for (a in anns) when (a.desc) {
            VALUE_SOURCE -> a.values.orEmpty().chunked(2).forEach { (_, v) -> (v as? List<*>)?.forEach { raw += listOf(it) } }
            CSV_SOURCE -> csvRows(a).forEach { raw += it }
            ENUM_SOURCE -> {
                val enumType = (value(a, "value") as? Type)?.takeUnless { it.internalName == "org/junit/jupiter/params/provider/NullEnum" }
                    ?: params.firstOrNull()?.takeIf { it.sort == Type.OBJECT } ?: return skipped("@EnumSource needs an enum parameter")
                val constants = enumConstants(enumType.internalName, lookup) ?: return skipped("${enumType.className} is not an enum")
                val names = (value(a, "names") as? List<*>)?.map { it.toString() }.orEmpty()
                val exclude = enumValue(a, "mode") == "EXCLUDE"
                constants.filter { names.isEmpty() || (it in names) != exclude }.forEach { raw += listOf(EnumName(it)) }
            }
            NULL_SOURCE -> raw += listOf(null)
            EMPTY_SOURCE -> raw += listOf(Empty)
            NULL_AND_EMPTY_SOURCE -> { raw += listOf(null); raw += listOf(Empty) }
            METHOD_SOURCE -> {
                val ref = (value(a, "value") as? List<*>)?.firstOrNull()?.toString()?.takeIf { it.isNotBlank() } ?: m.name
                if (ref.contains('#') || ref.contains('(')) return skipped("a @MethodSource naming another class is not supported yet")
                val src = lookup(owner)?.methods.orEmpty().firstOrNull {
                    it.name == ref && it.access and Opcodes.ACC_STATIC != 0 && Type.getArgumentTypes(it.desc).isEmpty()
                } ?: return skipped("no static method $ref() for @MethodSource (in Kotlin, a @JvmStatic companion member)")
                factory = FactoryRef(owner, src.name, src.desc)
            }
            in UNSUPPORTED_SOURCES -> return skipped("${simple(a.desc)} is not supported yet")
        }
        if (raw.isEmpty() && factory == null) return skipped("a @ParameterizedTest needs a source of arguments")
        val invocations = ArrayList<Invocation>()
        for (set in raw) {
            if (set.size < params.size) return skipped("a source gives ${set.size} argument(s) for ${params.size} parameter(s)")
            val args = params.indices.map { i ->
                convert(set[i], params[i], lookup) ?: return skipped("cannot convert ${display(set[i])} to ${params[i].className}")
            }
            invocations += Invocation(set.take(params.size).joinToString(", ") { display(it) }, args)
        }
        return TestMethod(owner, m.name, m.desc, invocations = invocations, factory = factory)
    }

    /** A `@CsvSource`'s rows, split as JUnit splits them: a quote character groups, an empty unquoted value is null. */
    private fun csvRows(a: AnnotationNode): List<List<Any?>> {
        val lines = (value(a, "value") as? List<*>)?.map { it.toString() }.orEmpty() +
            (value(a, "textBlock") as? String)?.lines()?.filter { it.isNotBlank() && !it.trimStart().startsWith("#") }.orEmpty()
        val delimiter = (value(a, "delimiterString") as? String)?.takeIf { it.isNotEmpty() }
            ?: (value(a, "delimiter") as? Char)?.takeIf { it != '\u0000' }?.toString() ?: ","
        val quote = (value(a, "quoteCharacter") as? Char) ?: '\''
        val nullValues = (value(a, "nullValues") as? List<*>)?.map { it.toString() }.orEmpty().toSet()
        val emptyValue = (value(a, "emptyValue") as? String) ?: ""
        return lines.map { line ->
            val cells = ArrayList<Any?>()
            val cell = StringBuilder()
            var quoted = false
            var wasQuoted = false
            var i = 0
            fun finish() {
                val text = if (wasQuoted) cell.toString() else cell.toString().trim()
                cells += when {
                    wasQuoted && text.isEmpty() -> emptyValue
                    !wasQuoted && text.isEmpty() -> null
                    text in nullValues -> null
                    else -> text
                }
                cell.setLength(0); wasQuoted = false
            }
            while (i < line.length) {
                val ch = line[i]
                when {
                    ch == quote -> { quoted = !quoted; wasQuoted = true; i++ }
                    !quoted && line.startsWith(delimiter, i) -> { finish(); i += delimiter.length }
                    else -> { if (wasQuoted && !quoted) {} else cell.append(ch); i++ }
                }
            }
            finish()
            cells
        }
    }

    private object Empty
    private class EnumName(val name: String)

    private fun display(v: Any?): String = when (v) {
        null -> "null"
        Empty -> "\"\""
        is EnumName -> v.name
        is Type -> v.className
        else -> v.toString()
    }

    /** The enum constants [internalName] declares, in order, or null when it is not an enum. */
    private fun enumConstants(internalName: String, lookup: (String) -> ClassNode?): List<String>? {
        val node = lookup(internalName) ?: return null
        if (node.access and Opcodes.ACC_ENUM == 0) return null
        return node.fields.orEmpty().filter { it.access and Opcodes.ACC_ENUM != 0 }.map { it.name }
    }

    /**
     * [v] (an annotation value, a CSV cell, null or [Empty]) as an argument of parameter type [t], converting as
     * JUnit's implicit conversion does: numbers widen or parse, text becomes a char, boolean, enum constant, or an
     * object through a static factory or constructor taking it. Null when no conversion applies.
     */
    private fun convert(v: Any?, t: Type, lookup: (String) -> ClassNode?): ArgValue? {
        if (v == null) return if (t.sort == Type.OBJECT || t.sort == Type.ARRAY) ArgValue.Const(null) else null
        if (v === Empty) return when {
            t.internalName == "java/lang/String" -> ArgValue.Const("")
            else -> null
        }
        if (v is EnumName) return ArgValue.EnumConst(t.internalName, v.name)
        if (v is Type) return if (t.internalName == "java/lang/Class" || t.internalName == "java/lang/Object") ArgValue.Const(v) else null
        val primitive = when (t.sort) {
            Type.INT, Type.LONG, Type.SHORT, Type.BYTE, Type.FLOAT, Type.DOUBLE, Type.BOOLEAN, Type.CHAR -> t.descriptor
            else -> UNBOXED[t.internalName]
        }
        if (primitive != null) return primitiveOf(v, primitive)?.let { ArgValue.Const(it) }
        return when (t.internalName) {
            "java/lang/String", "java/lang/CharSequence" -> ArgValue.Const(v.toString())
            "java/lang/Object" -> ArgValue.Const(v)
            else -> {
                if (v !is String) return null
                val constants = enumConstants(t.internalName, lookup)
                if (constants != null) return if (v in constants) ArgValue.EnumConst(t.internalName, v) else null
                TEXT_FACTORIES[t.internalName]?.let { (name, desc) -> return ArgValue.FromText(t.internalName, name, desc, v) }
                textFactory(t.internalName, lookup)?.let { (name, desc) -> ArgValue.FromText(t.internalName, name, desc, v) }
            }
        }
    }

    /** A project class's way to be made from text: a static `fromString`/`valueOf`/`of`/`parse(String)` returning it,
     *  or a constructor taking a String. */
    private fun textFactory(internalName: String, lookup: (String) -> ClassNode?): Pair<String, String>? {
        val node = lookup(internalName) ?: return null
        val ret = "L$internalName;"
        node.methods.orEmpty().firstOrNull { m ->
            m.access and Opcodes.ACC_STATIC != 0 && m.name in setOf("fromString", "valueOf", "of", "parse") &&
                m.desc == "(Ljava/lang/String;)$ret"
        }?.let { return it.name to it.desc }
        return node.methods.orEmpty().firstOrNull { it.name == "<init>" && it.desc == "(Ljava/lang/String;)V" }?.let { "<init>" to it.desc }
    }

    /** [v] as the boxed value of primitive descriptor [d], or null when it does not convert. */
    private fun primitiveOf(v: Any, d: String): Any? {
        val text = v as? String
        val n = v as? Number
        return runCatching {
            when (d) {
                "I" -> n?.takeIf { it is Int || it is Short || it is Byte }?.toInt() ?: (v as? Char)?.code ?: text!!.trim().toInt()
                "J" -> n?.takeIf { it !is Float && it !is Double }?.toLong() ?: text!!.trim().toLong()
                "S" -> n?.takeIf { it is Short || it is Byte }?.toShort() ?: text!!.trim().toShort()
                "B" -> (n as? Byte) ?: text!!.trim().toByte()
                "F" -> n?.takeIf { it !is Double }?.toFloat() ?: text!!.trim().toFloat()
                "D" -> n?.toDouble() ?: text!!.trim().toDouble()
                "Z" -> (v as? Boolean) ?: text!!.trim().lowercase().let { if (it == "true") true else if (it == "false") false else null }
                "C" -> (v as? Char) ?: text!!.takeIf { it.length == 1 }!![0]
                else -> null
            }
        }.getOrNull()
    }

    private fun frameworkOf(methods: List<MethodNode>): TestFramework? {
        val descs = methods.flatMap { annotations(it) }.mapTo(HashSet()) { it.desc }
        return when {
            JUPITER_ANY.any { it in descs } -> TestFramework.JUNIT5
            JUNIT4_TEST in descs -> TestFramework.JUNIT4
            TESTNG_TEST in descs -> TestFramework.TESTNG
            else -> null
        }
    }

    private fun annotations(m: MethodNode): List<AnnotationNode> =
        m.visibleAnnotations.orEmpty() + m.invisibleAnnotations.orEmpty()

    private fun annotations(c: ClassNode): List<AnnotationNode> =
        c.visibleAnnotations.orEmpty() + c.invisibleAnnotations.orEmpty()

    private fun value(a: AnnotationNode, name: String): Any? {
        val values = a.values ?: return null
        var i = 0
        while (i + 1 < values.size) {
            if (values[i] == name) return values[i + 1]
            i += 2
        }
        return null
    }

    private fun enumValue(a: AnnotationNode, name: String): String? = (value(a, name) as? Array<*>)?.getOrNull(1) as? String

    /** The text of an `@Ignore("why")`/`@Disabled("why")`, when given. */
    private fun reasonOf(anns: List<AnnotationNode>): String? =
        anns.firstOrNull { it.desc == JUNIT4_IGNORE || it.desc == JUPITER_DISABLED }
            ?.let { value(it, "value") as? String }?.takeIf { it.isNotBlank() }

    private fun hasNoArgConstructor(node: ClassNode): Boolean = hasConstructor(node, "()V")

    private fun hasConstructor(node: ClassNode, desc: String): Boolean =
        node.methods.orEmpty().any { it.name == "<init>" && it.desc == desc && it.access and Opcodes.ACC_PRIVATE == 0 }

    private fun simple(desc: String): String = "@" + desc.removeSuffix(";").substringAfterLast('/')

    /** The annotation descriptors one framework uses for each role. */
    private class Names(framework: TestFramework) {
        val test: Set<String>
        val unsupportedTests: Set<String>
        val beforeEach: String
        val afterEach: String
        val beforeAll: String
        val afterAll: String

        init {
            when (framework) {
                TestFramework.JUNIT4 -> {
                    test = setOf(JUNIT4_TEST); unsupportedTests = emptySet()
                    beforeEach = "Lorg/junit/Before;"; afterEach = "Lorg/junit/After;"
                    beforeAll = "Lorg/junit/BeforeClass;"; afterAll = "Lorg/junit/AfterClass;"
                }
                TestFramework.JUNIT5 -> {
                    test = setOf(JUPITER_TEST)
                    unsupportedTests = setOf("Lorg/junit/jupiter/api/TestFactory;", "Lorg/junit/jupiter/api/TestTemplate;")
                    beforeEach = "Lorg/junit/jupiter/api/BeforeEach;"; afterEach = "Lorg/junit/jupiter/api/AfterEach;"
                    beforeAll = "Lorg/junit/jupiter/api/BeforeAll;"; afterAll = "Lorg/junit/jupiter/api/AfterAll;"
                }
                TestFramework.TESTNG -> {
                    test = setOf(TESTNG_TEST); unsupportedTests = emptySet()
                    beforeEach = "Lorg/testng/annotations/BeforeMethod;"; afterEach = "Lorg/testng/annotations/AfterMethod;"
                    beforeAll = "Lorg/testng/annotations/BeforeClass;"; afterAll = "Lorg/testng/annotations/AfterClass;"
                }
            }
        }
    }

    /** Reads class files by internal name from directories and jars, opening each jar once. */
    private class ClassLookup(private val roots: List<Path>) : (String) -> ClassNode?, AutoCloseable {
        private val jars = HashMap<Path, ZipFile?>()

        override fun invoke(internalName: String): ClassNode? {
            val entry = "$internalName.class"
            for (root in roots) {
                if (Files.isDirectory(root)) {
                    val f = root.resolve(entry)
                    if (Files.isRegularFile(f)) return read(Files.readAllBytes(f))
                } else if (Files.isRegularFile(root)) {
                    val zip = jars.getOrPut(root) { runCatching { ZipFile(root.toFile()) }.getOrNull() } ?: continue
                    val e = zip.getEntry(entry) ?: continue
                    return read(zip.getInputStream(e).use { it.readBytes() })
                }
            }
            return null
        }

        override fun close() {
            jars.values.forEach { runCatching { it?.close() } }
        }
    }

    private const val JUNIT4_TEST = "Lorg/junit/Test;"
    private const val JUNIT4_IGNORE = "Lorg/junit/Ignore;"
    private const val JUNIT4_RUN_WITH = "Lorg/junit/runner/RunWith;"
    private const val JUNIT4_PARAMETERIZED = "org/junit/runners/Parameterized"
    private const val JUNIT4_PARAMETERS = "Lorg/junit/runners/Parameterized\$Parameters;"
    private const val JUNIT4_PARAMETER = "Lorg/junit/runners/Parameterized\$Parameter;"
    private const val JUPITER_PARAMETERIZED = "Lorg/junit/jupiter/params/ParameterizedTest;"
    private const val JUPITER_REPEATED = "Lorg/junit/jupiter/api/RepeatedTest;"
    private const val VALUE_SOURCE = "Lorg/junit/jupiter/params/provider/ValueSource;"
    private const val CSV_SOURCE = "Lorg/junit/jupiter/params/provider/CsvSource;"
    private const val ENUM_SOURCE = "Lorg/junit/jupiter/params/provider/EnumSource;"
    private const val METHOD_SOURCE = "Lorg/junit/jupiter/params/provider/MethodSource;"
    private const val NULL_SOURCE = "Lorg/junit/jupiter/params/provider/NullSource;"
    private const val EMPTY_SOURCE = "Lorg/junit/jupiter/params/provider/EmptySource;"
    private const val NULL_AND_EMPTY_SOURCE = "Lorg/junit/jupiter/params/provider/NullAndEmptySource;"
    private val UNSUPPORTED_SOURCES = setOf(
        "Lorg/junit/jupiter/params/provider/CsvFileSource;", "Lorg/junit/jupiter/params/provider/ArgumentsSource;",
        "Lorg/junit/jupiter/params/provider/FieldSource;",
    )

    /** Runners that are JUnit 4's default under another name: run like a plain class. */
    private val PLAIN_JUNIT4_RUNNERS = setOf(
        "org/junit/runners/JUnit4", "org/junit/runners/BlockJUnit4ClassRunner",
        "androidx/test/ext/junit/runners/AndroidJUnit4", "androidx/test/runner/AndroidJUnit4",
    )

    /** Boxed type -> its primitive descriptor. */
    private val UNBOXED = mapOf(
        "java/lang/Integer" to "I", "java/lang/Long" to "J", "java/lang/Short" to "S", "java/lang/Byte" to "B",
        "java/lang/Float" to "F", "java/lang/Double" to "D", "java/lang/Boolean" to "Z", "java/lang/Character" to "C",
    )

    /** JDK types JUnit makes from text, and the static factory (or constructor) that does it. */
    private val TEXT_FACTORIES = mapOf(
        "java/math/BigDecimal" to ("<init>" to "(Ljava/lang/String;)V"),
        "java/math/BigInteger" to ("<init>" to "(Ljava/lang/String;)V"),
        "java/util/UUID" to ("fromString" to "(Ljava/lang/String;)Ljava/util/UUID;"),
        "java/io/File" to ("<init>" to "(Ljava/lang/String;)V"),
        "java/time/LocalDate" to ("parse" to "(Ljava/lang/CharSequence;)Ljava/time/LocalDate;"),
        "java/time/LocalTime" to ("parse" to "(Ljava/lang/CharSequence;)Ljava/time/LocalTime;"),
        "java/time/LocalDateTime" to ("parse" to "(Ljava/lang/CharSequence;)Ljava/time/LocalDateTime;"),
        "java/time/Instant" to ("parse" to "(Ljava/lang/CharSequence;)Ljava/time/Instant;"),
        "java/time/Duration" to ("parse" to "(Ljava/lang/CharSequence;)Ljava/time/Duration;"),
        "java/time/Period" to ("parse" to "(Ljava/lang/CharSequence;)Ljava/time/Period;"),
    )
    private const val JUPITER_TEST = "Lorg/junit/jupiter/api/Test;"
    private const val JUPITER_DISABLED = "Lorg/junit/jupiter/api/Disabled;"
    private const val JUPITER_NESTED = "Lorg/junit/jupiter/api/Nested;"
    private const val JUPITER_TEST_INSTANCE = "Lorg/junit/jupiter/api/TestInstance;"
    private const val TESTNG_TEST = "Lorg/testng/annotations/Test;"

    /** Any annotation that marks a class as Jupiter's, so a class with only `@ParameterizedTest`s is found. */
    private val JUPITER_ANY = setOf(
        JUPITER_TEST, "Lorg/junit/jupiter/params/ParameterizedTest;", "Lorg/junit/jupiter/api/RepeatedTest;",
        "Lorg/junit/jupiter/api/TestFactory;", "Lorg/junit/jupiter/api/TestTemplate;",
    )
}
