package dev.ide.lang.java

import com.intellij.lang.java.JavaLanguage
import com.intellij.openapi.util.Disposer
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiNewExpression
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.cli.create
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.cli.jvm.config.configureJdkClasspathRoots
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.JVMConfigurationKeys
import org.jetbrains.kotlin.diagnostics.impl.BaseDiagnosticsCollector
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Spike (step 0): prove IntelliJ's Java PSI resolves classpath types and performs generic type inference in a
 * standalone [KotlinCoreEnvironment] — i.e. that the "use IntelliJ's native engine" strategy is viable before
 * building the real backend on top of it. Uses the running JDK as the classpath so resolution has real
 * binaries to find (on device the classpath will be android.jar + the module's libraries).
 */
class JavaResolutionSpikeTest {
    private val disposable = Disposer.newDisposable("java-resolution-spike")

    @AfterTest
    fun tearDown() = Disposer.dispose(disposable)

    @OptIn(CompilerConfiguration.Internals::class, org.jetbrains.kotlin.K1Deprecation::class)
    private fun env(): KotlinCoreEnvironment {
        // Keep the application-level environment alive across a zero refcount (mirrors IntellijPsiHost).
        if (System.getProperty("kotlin.environment.keepalive") == null) {
            System.setProperty("kotlin.environment.keepalive", "true")
        }
        val config = CompilerConfiguration.create(
            diagnosticsCollector = BaseDiagnosticsCollector.DoNothing,
            messageCollector = MessageCollector.NONE,
        ).apply {
            put(CommonConfigurationKeys.MODULE_NAME, "java-spike")
            // Mount the running JDK's modules as classpath roots so java.util.* resolves.
            put(JVMConfigurationKeys.JDK_HOME, File(System.getProperty("java.home")))
            configureJdkClasspathRoots()
        }
        return KotlinCoreEnvironment.createForProduction(
            disposable, config, EnvironmentConfigFiles.JVM_CONFIG_FILES,
        )
    }

    @Test
    fun `JavaPsiFacade resolves a classpath type`() {
        val env = env()
        val list = JavaPsiFacade.getInstance(env.project)
            .findClass("java.util.ArrayList", GlobalSearchScope.allScope(env.project))
        assertNotNull(list, "java.util.ArrayList should resolve from the JDK classpath")
        assertEquals("java.util.ArrayList", list.qualifiedName)
        // Supertype walk works over the decompiled Cls PSI (needed for member enumeration on `.`).
        assertTrue(
            InheritanceProbe.isSubclassOf(list, "java.util.List"),
            "ArrayList should be seen as a java.util.List via the Cls supertype graph",
        )
    }

    @Test
    fun `reference resolution and generic inference over a parsed buffer`() {
        val env = env()
        val src = """
            import java.util.ArrayList;
            class Foo {
                void m() {
                    ArrayList<String> xs = new ArrayList<String>();
                    String s = xs.get(0);
                }
            }
        """.trimIndent()
        val file = PsiFileFactory.getInstance(env.project)
            .createFileFromText("Foo.java", JavaLanguage.INSTANCE, src) as PsiJavaFile

        // 1. Reference resolution: `new ArrayList<String>()` resolves to the classpath PsiClass.
        val newExpr = PsiTreeUtil.collectElementsOfType(file, PsiNewExpression::class.java).first()
        val resolved = newExpr.classReference?.resolve()
        assertTrue(resolved is PsiClass, "the `new ArrayList<>()` class reference should resolve")
        assertEquals("java.util.ArrayList", (resolved as PsiClass).qualifiedName)

        // 2. Generic inference: xs.get(0) is typed String (ArrayList<String>.get -> E = String).
        val getCall = PsiTreeUtil.collectElementsOfType(file, PsiMethodCallExpression::class.java)
            .first { it.methodExpression.referenceName == "get" }
        val t = getCall.type
        assertNotNull(t, "xs.get(0) should have an inferred type")
        assertEquals("java.lang.String", t.canonicalText, "generic inference should substitute E=String")
    }

    private object InheritanceProbe {
        fun isSubclassOf(cls: PsiClass, fqn: String): Boolean {
            val seen = HashSet<String>()
            val stack = ArrayDeque<PsiClass>()
            stack.addLast(cls)
            while (stack.isNotEmpty()) {
                val c = stack.removeLast()
                val q = c.qualifiedName ?: continue
                if (!seen.add(q)) continue
                if (q == fqn) return true
                c.supers.forEach { stack.addLast(it) }
            }
            return false
        }
    }
}
