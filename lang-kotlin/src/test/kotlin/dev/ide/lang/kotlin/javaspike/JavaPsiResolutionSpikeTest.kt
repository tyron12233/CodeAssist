package dev.ide.lang.kotlin.javaspike

import dev.ide.index.IndexExtension
import dev.ide.index.IndexId
import dev.ide.index.IndexInput
import dev.ide.index.IndexOrigin
import dev.ide.index.IndexScope
import dev.ide.index.IndexService
import dev.ide.index.InputFilter
import dev.ide.index.MatchingMode
import dev.ide.index.StringExternalizer
import dev.ide.index.StringKeyDescriptor
import dev.ide.index.impl.IndexServiceImpl
import kotlinx.coroutines.runBlocking
import org.jetbrains.kotlin.com.intellij.codeInsight.ExternalAnnotationsManager
import org.jetbrains.kotlin.com.intellij.codeInsight.InferredAnnotationsManager
import org.jetbrains.kotlin.com.intellij.core.CoreApplicationEnvironment
import org.jetbrains.kotlin.com.intellij.core.JavaCoreApplicationEnvironment
import org.jetbrains.kotlin.com.intellij.core.JavaCoreProjectEnvironment
import org.jetbrains.kotlin.com.intellij.ide.highlighter.JavaFileType
import org.jetbrains.kotlin.com.intellij.lang.jvm.facade.JvmElementProvider
import org.jetbrains.kotlin.com.intellij.openapi.Disposable
import org.jetbrains.kotlin.com.intellij.openapi.application.ApplicationManager
import org.jetbrains.kotlin.com.intellij.openapi.extensions.AreaInstance
import org.jetbrains.kotlin.com.intellij.openapi.extensions.LoadingOrder
import org.jetbrains.kotlin.com.intellij.openapi.project.Project
import org.jetbrains.kotlin.com.intellij.openapi.roots.LanguageLevelProjectExtension
import org.jetbrains.kotlin.com.intellij.openapi.util.Computable
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFileSystem
import org.jetbrains.kotlin.com.intellij.pom.java.LanguageLevel
import org.jetbrains.kotlin.com.intellij.psi.JavaModuleSystem
import org.jetbrains.kotlin.com.intellij.psi.JavaPsiFacade
import org.jetbrains.kotlin.com.intellij.psi.PsiAnnotation
import org.jetbrains.kotlin.com.intellij.psi.PsiClass
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.com.intellij.psi.PsiElementFinder
import org.jetbrains.kotlin.com.intellij.psi.PsiExpression
import org.jetbrains.kotlin.com.intellij.psi.PsiFile
import org.jetbrains.kotlin.com.intellij.psi.PsiFileFactory
import org.jetbrains.kotlin.com.intellij.psi.PsiJavaFile
import org.jetbrains.kotlin.com.intellij.psi.PsiLocalVariable
import org.jetbrains.kotlin.com.intellij.psi.PsiManager
import org.jetbrains.kotlin.com.intellij.psi.PsiMethodCallExpression
import org.jetbrains.kotlin.com.intellij.psi.PsiModifierListOwner
import org.jetbrains.kotlin.com.intellij.psi.PsiNameValuePair
import org.jetbrains.kotlin.com.intellij.psi.PsiPackage
import org.jetbrains.kotlin.com.intellij.psi.augment.PsiAugmentProvider
import org.jetbrains.kotlin.com.intellij.psi.impl.JavaClassSupersImpl
import org.jetbrains.kotlin.com.intellij.psi.impl.PsiElementFinderImpl
import org.jetbrains.kotlin.com.intellij.psi.impl.file.PsiPackageImpl
import org.jetbrains.kotlin.com.intellij.psi.search.GlobalSearchScope
import org.jetbrains.kotlin.com.intellij.psi.util.JavaClassSupers
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * SPIKE (feasibility de-risking): run IntelliJ's Java resolution engine standalone on the intellij-core that
 * kotlin-compiler-embeddable already bundles (and that we've proven runs on ART) — the Java mirror of the
 * kotlinc-on-ART spike. NO IDE indexing framework, NO Gradle, NO JDK.
 *
 * Test 1 proves the engine works (binary resolution + generic inference) against a real android.jar on the
 * PSI classpath. Test 2 proves the architecturally important thing: OUR `index-impl` can be the name->file
 * backing store for IntelliJ's resolver — a custom PsiElementFinder resolves FQN -> owning jar THROUGH a real
 * IndexServiceImpl (the classLocator pattern lang-jdt's name env already uses), registered ahead of the
 * default finder, and full generic inference runs on top of it.
 *
 * Self-gates (assumeTrue) when no android.jar is present.
 */
class JavaPsiResolutionSpikeTest {

    @Test
    fun `standalone JavaPsiFacade resolves generics against android_jar`() {
        val androidJar = androidJar()
        assumeTrue(androidJar != null, "no android.jar on this machine; skipping Java-PSI resolution spike")

        val disposable: Disposable = Disposer.newDisposable("java-psi-spike")
        try {
            val t0 = System.nanoTime()
            val (_, project) = standUpJavaEnv(disposable, androidJar!!, withDefaultFinder = true)
            val standUpMs = (System.nanoTime() - t0) / 1_000_000.0

            ApplicationManager.getApplication().runReadAction(Computable {
                val scope = GlobalSearchScope.allScope(project)
                val facade = JavaPsiFacade.getInstance(project)
                val tFind = System.nanoTime()
                assertNotNull(facade.findClass("java.lang.String", scope), "java.lang.String from android.jar")
                assertNotNull(facade.findClass("android.app.Activity", scope), "android.app.Activity from android.jar")
                val findMs = (System.nanoTime() - tFind) / 1_000_000.0

                val psi = parseDemo(project)
                val tType = System.nanoTime()
                assertInferenceTypes(psi)
                val typeMs = (System.nanoTime() - tType) / 1_000_000.0
                println("[java-psi-spike] stand-up=${"%.1f".format(standUpMs)}ms findClass=${"%.1f".format(findMs)}ms infer=${"%.1f".format(typeMs)}ms")
            })
        } finally {
            Disposer.dispose(disposable)
        }
    }

    @Test
    fun `JavaPsiFacade name resolution served entirely by our index-impl`() {
        val androidJar = androidJar()
        assumeTrue(androidJar != null, "no android.jar on this machine; skipping index-backed spike")

        val disposable: Disposable = Disposer.newDisposable("java-psi-index-spike")
        val cacheRoot = Files.createTempDirectory("spike-index")
        // classLocator (FQN -> jar) + packageTypes (package -> its top-level types): the two indexes a PSI
        // finder needs to serve both findClass and findPackage/getClasses/getClassNames.
        val index = IndexServiceImpl(listOf(SpikeClassLocator, SpikePackageTypes), cacheRoot)
        try {
            val tIdx = System.nanoTime()
            runBlocking { index.ensureUpToDate(IndexScope(libraryJars = listOf(androidJar!!))) }
            val idxMs = (System.nanoTime() - tIdx) / 1_000_000.0
            assertTrue(index.exact<String>(SpikeClassLocator.id, "java.util.List").any(), "index locates java.util.List")
            assertTrue(index.exact<String>(SpikePackageTypes.id, "java.util").any(), "index enumerates java.util types")

            // withDefaultFinder=false ⇒ NO PsiElementFinderImpl (the classpath name-resolver). Our finder is
            // the sole class AND package name resolver; the jar is only there for Cls byte access.
            val (appEnv, project) = standUpJavaEnv(disposable, androidJar!!, withDefaultFinder = false)
            val finder = IndexBackedFinder(project, appEnv.jarFileSystem, index)
            (project as AreaInstance).extensionArea
                .getExtensionPoint<PsiElementFinder>(PsiElementFinder.EP.name)
                .registerExtension(finder, LoadingOrder.FIRST, disposable)

            ApplicationManager.getApplication().runReadAction(Computable {
                val scope = GlobalSearchScope.allScope(project)

                // (1) Package layer served entirely by our index.
                assertNotNull(finder.findPackage("java.lang"), "findPackage(java.lang) via our index")
                assertNotNull(finder.findPackage("java.util"), "findPackage(java.util) via our index")
                assertNull(finder.findPackage("does.not.exist"), "unknown package -> null")

                // (2) Class + supertype hierarchy resolves THROUGH our index (each super re-enters the finder).
                val list = assertNotNull(finder.findClass("java.util.List", scope), "index findClass(java.util.List)")
                assertTrue(
                    list.supers.any { it.qualifiedName == "java.util.Collection" },
                    "List's supertype Collection must resolve via the index; got ${list.supers.map { s -> s.qualifiedName }}",
                )

                // (3) Full generic inference — resolving EVERYTHING, incl. the implicit `java.lang.*` imports
                //     (String/Integer) via findPackage+getClasses — entirely through our index.
                assertInferenceTypes(parseDemo(project))
                assertTrue(
                    listOf("java.util.List", "java.util.Arrays", "java.util.HashMap", "java.lang.String").all { it in finder.resolved },
                    "inference must have resolved these THROUGH our index; got ${finder.resolved.sorted().take(20)}",
                )
                assertNull(finder.findClass("does.not.Exist", scope), "unknown class -> null (index says not present)")
                println("[java-psi-index-spike] index build=${"%.0f".format(idxMs)}ms; ${finder.resolved.size} classes name-resolved via our index (sole finder)")
            })
        } finally {
            index.close()
            Disposer.dispose(disposable)
        }
    }

    /** The demo source: exercises generic method inference, substitution, and constructor generics. */
    private fun parseDemo(project: Project): PsiJavaFile {
        val src = """
            import java.util.List;
            import java.util.Arrays;
            import java.util.HashMap;
            class Demo {
                void m() {
                    List<String> xs = Arrays.asList("hello", "world");
                    String first = xs.get(0);
                    HashMap<String, Integer> counts = new HashMap<String, Integer>();
                    Integer n = counts.get(first);
                }
            }
        """.trimIndent()
        return PsiFileFactory.getInstance(project).createFileFromText("Demo.java", JavaFileType.INSTANCE, src) as PsiJavaFile
    }

    private fun assertInferenceTypes(psi: PsiJavaFile) {
        val vars = PsiTreeUtil.findChildrenOfType(psi, PsiLocalVariable::class.java).associateBy { it.name }
        fun initType(name: String): String {
            val init = vars[name]?.initializer ?: error("no initializer for '$name'")
            return assertNotNull((init as PsiExpression).type, "'$name' initializer has no resolved type").canonicalText
        }
        assertEquals("java.util.List<java.lang.String>", initType("xs"), "Arrays.asList generic-method inference")
        assertEquals("java.lang.String", initType("first"), "List<String>.get substitution")
        assertEquals("java.util.HashMap<java.lang.String,java.lang.Integer>", initType("counts"), "constructor generics")
        assertEquals("java.lang.Integer", initType("n"), "Map<..>.get substitution")

        val getCall = PsiTreeUtil.findChildrenOfType(psi, PsiMethodCallExpression::class.java)
            .first { it.methodExpression.referenceName == "get" && it.text.startsWith("xs.") }
        val resolved = assertNotNull(getCall.resolveMethod(), "xs.get(0) must resolve to a PsiMethod")
        assertEquals("java.util.List", resolved.containingClass?.qualifiedName, "get resolves onto java.util.List")
    }

    /**
     * The undocumented bootstrap a standalone Java env needs (each surfaced as a runtime crash one layer deeper):
     * PathManager props, language level, the elementFinder / jvm.elementProvider / psiAugmentProvider /
     * javaModuleSystem EPs, and the External/Inferred-annotation + JavaClassSupers services.
     */
    private fun standUpJavaEnv(disposable: Disposable, androidJar: Path, withDefaultFinder: Boolean): Pair<JavaCoreApplicationEnvironment, Project> {
        val ideaHome = Files.createTempDirectory("idea-home-spike")
        System.setProperty("idea.home.path", ideaHome.toString())
        System.setProperty("idea.config.path", ideaHome.resolve("config").toString())
        System.setProperty("idea.system.path", ideaHome.resolve("system").toString())
        System.setProperty("idea.plugins.path", ideaHome.resolve("plugins").toString())
        System.setProperty("idea.ignore.disabled.plugins", "true")

        val appEnv = JavaCoreApplicationEnvironment(disposable)
        val projectEnv = JavaCoreProjectEnvironment(disposable, appEnv)
        // The jar is registered so the platform can open it + build Cls PSI from `.class` bytes (a byte-access
        // concern: PsiManager returns a plain PsiBinaryFile for an unregistered jar). NAME resolution is a
        // separate matter: when withDefaultFinder=false we do NOT register PsiElementFinderImpl, so the
        // classpath is never consulted to map an FQN to a file — our index-backed finder is the sole resolver.
        projectEnv.addJarToClassPath(androidJar.toFile())
        val project = projectEnv.project
        LanguageLevelProjectExtension.getInstance(project).languageLevel = LanguageLevel.HIGHEST

        val area = (project as AreaInstance).extensionArea
        val epName = PsiElementFinder.EP.name
        if (!area.hasExtensionPoint(epName)) {
            CoreApplicationEnvironment.registerExtensionPoint(area, epName, PsiElementFinder::class.java)
        }
        if (withDefaultFinder) {
            area.getExtensionPoint<PsiElementFinder>(epName).registerExtension(PsiElementFinderImpl(project), disposable)
        }
        if (!area.hasExtensionPoint(JvmElementProvider.EP_NAME.name)) {
            CoreApplicationEnvironment.registerExtensionPoint(area, JvmElementProvider.EP_NAME, JvmElementProvider::class.java)
        }
        runCatching { CoreApplicationEnvironment.registerApplicationExtensionPoint(PsiAugmentProvider.EP_NAME, PsiAugmentProvider::class.java) }
        runCatching { CoreApplicationEnvironment.registerApplicationExtensionPoint(JavaModuleSystem.EP_NAME, JavaModuleSystem::class.java) }

        // MockComponentManager's supertype `ComponentManagerEx` isn't in the relocated intellij-core on the
        // test classpath, so NAMING the type (even in an `as` cast) fails to compile ("Cannot access
        // ComponentManagerEx which is a supertype of MockProject"). Register the services reflectively — the
        // runtime object is a MockProject / MockApplication that has `registerService(Class, Object)`.
        registerService(project, ExternalAnnotationsManager::class.java, StubExternalAnnotations())
        registerService(project, InferredAnnotationsManager::class.java, StubInferredAnnotations())
        registerService(ApplicationManager.getApplication(), JavaClassSupers::class.java, JavaClassSupersImpl())
        return appEnv to project
    }

    /** Call `MockComponentManager.registerService(Class<T>, T)` reflectively (see [standUpJavaEnv]). */
    private fun registerService(container: Any, iface: Class<*>, impl: Any) {
        container.javaClass.getMethod("registerService", Class::class.java, Any::class.java).invoke(container, iface, impl)
    }

    private fun sdkRoots() = listOfNotNull(
        System.getenv("ANDROID_HOME"),
        System.getenv("ANDROID_SDK_ROOT"),
        System.getProperty("user.home") + "/Library/Android/sdk",
    ).map { Path.of(it) }.filter { Files.isDirectory(it) }

    private fun androidJar(): Path? = sdkRoots().map { it.resolve("platforms") }.filter { Files.isDirectory(it) }
        .flatMap { runCatching { Files.list(it).use { s -> s.toList() } }.getOrDefault(emptyList()) }
        .map { it.resolve("android.jar") }.filter { Files.isRegularFile(it) }
        .maxByOrNull { it.parent.fileName.toString() }
}

/**
 * classLocator, our index's authoritative "which jar holds this top-level type" map (mirrors lang-jdt's
 * [dev.ide.lang.jdt.index] JavaClassLocatorIndex): library `.class` FQN -> owning jar path. Top-level only.
 */
private object SpikeClassLocator : IndexExtension<String, String> {
    override val id = IndexId("spike.classLocator")
    override val version = 1
    override val keyDescriptor = StringKeyDescriptor
    override val valueExternalizer = StringExternalizer
    override val matching = MatchingMode.PREFIX_ONLY
    override val inputFilter = InputFilter { it.origin == IndexOrigin.LIBRARY && it.unitName?.endsWith(".class") == true }

    override fun index(input: IndexInput): Map<String, Collection<String>> {
        val entry = input.unitName ?: return emptyMap()
        if ('$' in entry) return emptyMap()
        val jar = input.sourcePath ?: return emptyMap()
        val fqn = entry.removeSuffix(".class").replace('/', '.')
        return mapOf(fqn to listOf(jar.toAbsolutePath().normalize().toString()))
    }
}

/**
 * packageTypes: package FQN -> the top-level types directly in it (mirrors lang-jdt's JavaPackageTypesIndex).
 * Backs the finder's findPackage (existence via exact-or-prefix) + getClasses/getClassNames (enumeration for
 * on-demand `import p.*` / the implicit `java.lang.*`).
 */
private object SpikePackageTypes : IndexExtension<String, String> {
    override val id = IndexId("spike.packageTypes")
    override val version = 1
    override val keyDescriptor = StringKeyDescriptor
    override val valueExternalizer = StringExternalizer
    override val matching = MatchingMode.PREFIX_ONLY
    override val inputFilter = InputFilter { it.origin == IndexOrigin.LIBRARY && it.unitName?.endsWith(".class") == true }

    override fun index(input: IndexInput): Map<String, Collection<String>> {
        val entry = input.unitName ?: return emptyMap()
        if ('$' in entry) return emptyMap()
        val fqn = entry.removeSuffix(".class").replace('/', '.')
        val pkg = fqn.substringBeforeLast('.', "")
        return mapOf(pkg to listOf(fqn))
    }
}

/**
 * A PsiElementFinder whose name->file layer is OUR index: FQN -> owning jar via the classLocator query, then
 * the `.class` is read from that jar through the platform's own VFS + Cls reader (so the PsiClass is shared
 * with any classpath-backed finder for the same VirtualFile). Proves our index can drive IntelliJ resolution.
 */
private class IndexBackedFinder(
    private val project: Project,
    private val jarFs: VirtualFileSystem,
    private val index: IndexService,
) : PsiElementFinder() {
    private val mgr = PsiManager.getInstance(project)
    val resolved: MutableSet<String> = ConcurrentHashMap.newKeySet()

    override fun findClass(qualifiedName: String, scope: GlobalSearchScope): PsiClass? {
        val jar = index.exact<String>(SpikeClassLocator.id, qualifiedName).firstOrNull() ?: return null
        // Open the jar ROOT first (primes the CoreJarFileSystem handler), then navigate to the entry — a cold
        // deep-entry lookup returns null without the root open (this is what addJarToClassPath does internally).
        val root = jarFs.findFileByPath("$jar!/") ?: return null
        val vf = root.findFileByRelativePath(qualifiedName.replace('.', '/') + ".class") ?: return null
        val psiFile = mgr.findFile(vf) as? PsiJavaFile ?: return null
        val cls = psiFile.classes.firstOrNull { it.qualifiedName == qualifiedName } ?: psiFile.classes.firstOrNull() ?: return null
        resolved += qualifiedName
        return cls
    }

    override fun findClasses(qualifiedName: String, scope: GlobalSearchScope): Array<PsiClass> =
        findClass(qualifiedName, scope)?.let { arrayOf(it) } ?: PsiClass.EMPTY_ARRAY

    // ---- package layer, also served by our index (packageTypes) ----

    override fun findPackage(qualifiedName: String): PsiPackage? {
        if (qualifiedName.isEmpty()) return PsiPackageImpl(mgr, "")
        val exists = index.exact<String>(SpikePackageTypes.id, qualifiedName).any() ||
            index.prefix<String>(SpikePackageTypes.id, "$qualifiedName.", 1).any()
        return if (exists) PsiPackageImpl(mgr, qualifiedName) else null
    }

    override fun getClasses(psiPackage: PsiPackage, scope: GlobalSearchScope): Array<PsiClass> =
        index.exact<String>(SpikePackageTypes.id, psiPackage.qualifiedName)
            .mapNotNull { findClass(it, scope) }.toList().toTypedArray()

    /** Name-filtered fast path (avoids enumerating the whole package for a single-name lookup). */
    override fun getClasses(shortName: String?, psiPackage: PsiPackage, scope: GlobalSearchScope): Array<PsiClass> {
        val name = shortName ?: return getClasses(psiPackage, scope)
        val pkg = psiPackage.qualifiedName
        val fqn = if (pkg.isEmpty()) name else "$pkg.$name"
        return findClass(fqn, scope)?.let { arrayOf(it) } ?: PsiClass.EMPTY_ARRAY
    }

    override fun getClassNames(psiPackage: PsiPackage, scope: GlobalSearchScope): MutableSet<String> =
        index.exact<String>(SpikePackageTypes.id, psiPackage.qualifiedName)
            .map { it.substringAfterLast('.') }.toMutableSet()
}

/** No-op ExternalAnnotationsManager: there are no external annotation roots in a standalone env. */
private class StubExternalAnnotations : ExternalAnnotationsManager() {
    override fun hasAnnotationRootsForFile(file: org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile): Boolean = false
    override fun isExternalAnnotation(annotation: PsiAnnotation): Boolean = false
    override fun findExternalAnnotation(owner: PsiModifierListOwner, fqn: String): PsiAnnotation? = null
    override fun findExternalAnnotations(owner: PsiModifierListOwner, fqn: String): MutableList<PsiAnnotation> = mutableListOf()
    override fun isExternalAnnotationWritable(owner: PsiModifierListOwner, fqn: String): Boolean = false
    override fun findExternalAnnotations(owner: PsiModifierListOwner): Array<PsiAnnotation> = emptyArray()
    override fun findDefaultConstructorExternalAnnotations(c: PsiClass): MutableList<PsiAnnotation> = mutableListOf()
    override fun findDefaultConstructorExternalAnnotations(c: PsiClass, fqn: String): MutableList<PsiAnnotation> = mutableListOf()
    override fun annotateExternally(owner: PsiModifierListOwner, fqn: String, file: PsiFile, values: Array<out PsiNameValuePair>?) {}
    override fun deannotate(owner: PsiModifierListOwner, fqn: String): Boolean = false
    override fun editExternalAnnotation(owner: PsiModifierListOwner, fqn: String, values: Array<out PsiNameValuePair>?): Boolean = false
    override fun chooseAnnotationsPlaceNoUi(element: PsiElement): AnnotationPlace = AnnotationPlace.NOWHERE
    override fun chooseAnnotationsPlace(element: PsiElement): AnnotationPlace = AnnotationPlace.NOWHERE
    override fun findExternalAnnotationsFiles(owner: PsiModifierListOwner): MutableList<PsiFile>? = null
    override fun hasConfiguredAnnotationRoot(owner: PsiModifierListOwner): Boolean = false
}

/** No-op InferredAnnotationsManager: the standalone env infers no annotations. */
private class StubInferredAnnotations : InferredAnnotationsManager() {
    override fun findInferredAnnotation(owner: PsiModifierListOwner, fqn: String): PsiAnnotation? = null
    override fun findInferredAnnotations(owner: PsiModifierListOwner): Array<PsiAnnotation> = emptyArray()
    override fun isInferredAnnotation(annotation: PsiAnnotation): Boolean = false
}
