package dev.ide.lang.java

import dev.ide.lang.BackendCapability
import dev.ide.lang.CompilationContext
import dev.ide.lang.LanguageBackend
import dev.ide.lang.LanguageId
import dev.ide.lang.SourceAnalyzer
import dev.ide.lang.java.env.JavaEnvironment
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * The Java [LanguageBackend] built on IntelliJ's Java PSI parser AND its native resolution/inference engine
 * (`JavaPsiFacade` + the Cls decompiler + `PsiResolveHelper`/`InferenceSession`), held per module in a
 * [JavaEnvironment]. Intended to replace `:lang-jdt` as the `.java` editor backend once at feature parity.
 *
 * Editor-only: the build still compiles Java with ecj (`:lang-jdt` / `:jvm-build`). Host wiring: ide-core
 * registers it on `LANGUAGE_BACKEND_EP` and routes `.java` here.
 *
 * Capabilities today: ERROR_RECOVERY (the tolerant PSI parse) and BINDINGS (resolution). Completion and the
 * editor-QoL services are added incrementally.
 */
class JavaLanguageBackend : LanguageBackend {
    override val id: String = "java-psi"
    override val languages: Set<LanguageId> = setOf(LANGUAGE_ID)
    override val capabilities: Set<BackendCapability> = setOf(
        BackendCapability.ERROR_RECOVERY,
        BackendCapability.BINDINGS,
        BackendCapability.COMPLETION,
        BackendCapability.SNIPPETS,       // completion emits snippet items (live + postfix templates)
        BackendCapability.POSTFIX,        // `expr.sout` / `expr.nn` / `expr.for` postfix templates
        BackendCapability.SIGNATURE_HELP,
        BackendCapability.SEMANTIC_HIGHLIGHT,
        BackendCapability.CODE_FOLDING,
        BackendCapability.INLAY_HINTS,
    )

    override fun createAnalyzer(ctx: CompilationContext): SourceAnalyzer {
        // The JDK arrives as a boot-classpath DIRECTORY that is a JDK image (`lib/modules` / `jmods`), which
        // must be mounted via `configureJdkClasspathRoots` (JDK_HOME), NOT as a plain classpath root — else
        // `java.*` never resolves. Everything else (library jars, AAR classes, module-output dirs, android.jar)
        // is a normal classpath root. Mirrors `JdtSourceAnalyzer`'s boot-classpath handling.
        val bootPaths = ctx.bootClasspath.entries.pathsOf()
        val jdkImage = bootPaths.firstOrNull { Files.isDirectory(it) && isJdkImage(it) }

        val classpath = (ctx.bootClasspath.entries + ctx.classpath.entries).pathsOf()
            .filter { it != jdkImage && Files.exists(it) }
            .map { it.toFile() }
        val sourceRoots = ctx.sourceRoots.mapNotNull { runCatching { File(it.path) }.getOrNull() }
            .filter { it.isDirectory }

        // JDK home: the JDK image boot dir, else the running JDK when the platform isn't android.jar (a
        // core-Java/console module with no boot classpath) — else null (Android: java.* comes from android.jar).
        val hasAndroidJar = classpath.any { it.name == "android.jar" }
        val jdkHome = jdkImage?.toFile()
            ?: if (!hasAndroidJar) File(System.getProperty("java.home")).takeIf { it.exists() } else null

        val env = JavaEnvironment.create(classpath, sourceRoots, jdkHome)
        return JavaSourceAnalyzer(env)
    }

    private fun List<dev.ide.model.ClasspathEntry>.pathsOf(): List<Path> =
        mapNotNull { runCatching { Paths.get(it.root.path) }.getOrNull() }

    private fun isJdkImage(p: Path): Boolean =
        Files.exists(p.resolve("lib/modules")) || Files.isDirectory(p.resolve("jmods"))

    companion object {
        val LANGUAGE_ID = LanguageId("java")
    }
}
