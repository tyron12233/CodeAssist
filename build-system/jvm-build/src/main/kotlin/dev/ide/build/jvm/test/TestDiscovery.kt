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

/**
 * One test method. [expected] is the internal name of the exception it must throw (JUnit 4 `expected`, TestNG
 * `expectedExceptions`), [skipReason] non-null when it is ignored or of a shape the runner does not support.
 */
data class TestMethod(
    val owner: String,
    val name: String,
    val descriptor: String,
    val expected: String? = null,
    val skipReason: String? = null,
)

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

    fun matches(m: TestMethod): Boolean = method == null || m.name == method

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
 * JUnit 4, JUnit 5 (Jupiter) and TestNG are recognised by annotation descriptor. Shapes the generated runner
 * cannot drive faithfully (parameterized/repeated tests, test factories, `@Nested` classes, custom JUnit 4 runners)
 * are reported as skipped with the reason, rather than run wrongly or silently dropped.
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
        // The class and its test superclasses, the topmost first, so their hooks run in JUnit's order.
        val chain = ArrayList<ClassNode>()
        var c: ClassNode? = node
        while (c != null && chain.size < 32) {
            chain.add(0, c)
            val sup = c.superName ?: break
            if (sup == "java/lang/Object") break
            c = lookup(sup)
        }
        val methods = chain.flatMap { cls -> cls.methods.orEmpty().map { cls.name to it } }
        val framework = frameworkOf(methods.map { it.second }) ?: return null

        val tests = LinkedHashMap<String, TestMethod>() // a subclass override replaces the inherited test
        val beforeEach = ArrayList<TestHook>()
        val afterEach = ArrayList<TestHook>()
        val beforeAll = ArrayList<TestHook>()
        val afterAll = ArrayList<TestHook>()
        val names = Names(framework)
        for ((owner, m) in methods) {
            val anns = annotations(m)
            val static = m.access and Opcodes.ACC_STATIC != 0
            fun hook() = TestHook(owner, m.name, m.desc, static)
            when {
                anns.any { it.desc in names.test } -> tests[m.name + m.desc] = testMethod(owner, m, anns, framework)
                anns.any { it.desc in names.unsupportedTests } ->
                    tests[m.name + m.desc] = TestMethod(owner, m.name, m.desc, skipReason = "${simple(anns.first { it.desc in names.unsupportedTests }.desc)} tests are not supported yet")
                anns.any { it.desc == names.beforeEach } -> beforeEach += hook()
                anns.any { it.desc == names.afterEach } -> afterEach.add(0, hook())
                anns.any { it.desc == names.beforeAll } -> beforeAll += hook()
                anns.any { it.desc == names.afterAll } -> afterAll.add(0, hook())
            }
        }
        if (tests.isEmpty()) return null

        val classAnns = chain.flatMap { annotations(it) }
        val shared = framework == TestFramework.TESTNG || classAnns.any { a ->
            a.desc == JUPITER_TEST_INSTANCE && enumValue(a, "value") == "PER_CLASS"
        }
        val classSkip = when {
            node.access and Opcodes.ACC_PUBLIC == 0 && framework == TestFramework.JUNIT4 -> "JUnit 4 test classes must be public"
            classAnns.any { it.desc == JUNIT4_IGNORE || it.desc == JUPITER_DISABLED } -> reasonOf(classAnns) ?: "disabled"
            classAnns.any { it.desc == JUNIT4_RUN_WITH } -> "classes with a custom @RunWith runner are not supported yet"
            classAnns.any { it.desc == JUPITER_NESTED } -> "@Nested classes are not supported yet"
            !hasNoArgConstructor(node) -> "a test class needs a constructor without parameters"
            else -> null
        }
        return TestClass(
            internalName = node.name,
            framework = framework,
            tests = tests.values.toList(),
            beforeEach = beforeEach,
            afterEach = afterEach,
            beforeAll = beforeAll,
            afterAll = afterAll,
            sharedInstance = shared,
            skipReason = classSkip,
        )
    }

    private fun testMethod(owner: String, m: MethodNode, anns: List<AnnotationNode>, framework: TestFramework): TestMethod {
        val test = anns.first { it.desc in Names(framework).test }
        val expected = when (framework) {
            TestFramework.JUNIT4 -> (value(test, "expected") as? Type)?.internalName
                ?.takeUnless { it == "org/junit/Test\$None" }
            TestFramework.TESTNG -> ((value(test, "expectedExceptions") as? List<*>)?.firstOrNull() as? Type)?.internalName
            TestFramework.JUNIT5 -> null
        }
        val skip = when {
            anns.any { it.desc == JUNIT4_IGNORE || it.desc == JUPITER_DISABLED } -> reasonOf(anns) ?: "disabled"
            framework == TestFramework.TESTNG && value(test, "enabled") == false -> "disabled"
            m.access and Opcodes.ACC_STATIC != 0 -> "a test method cannot be static"
            Type.getArgumentTypes(m.desc).isNotEmpty() -> "test methods with parameters are not supported yet"
            else -> null
        }
        return TestMethod(owner, m.name, m.desc, expected, skip)
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

    private fun hasNoArgConstructor(node: ClassNode): Boolean =
        node.methods.orEmpty().any { it.name == "<init>" && it.desc == "()V" && it.access and Opcodes.ACC_PRIVATE == 0 }

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
                    unsupportedTests = setOf(
                        "Lorg/junit/jupiter/params/ParameterizedTest;", "Lorg/junit/jupiter/api/RepeatedTest;",
                        "Lorg/junit/jupiter/api/TestFactory;", "Lorg/junit/jupiter/api/TestTemplate;",
                    )
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
