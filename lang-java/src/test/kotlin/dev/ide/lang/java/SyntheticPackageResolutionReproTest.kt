package dev.ide.lang.java

import com.intellij.psi.PsiJavaCodeReferenceElement
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import dev.ide.lang.java.env.JavaEnvironment
import dev.ide.lang.synthetic.SyntheticClass
import dev.ide.lang.synthetic.SyntheticMethod
import dev.ide.lang.synthetic.SyntheticModifier
import dev.ide.psi.IntellijPsiHost
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Repro for: ViewBinding classes (synthetic, in `<namespace>.databinding` — a package with NO on-disk
 * directory) can't be resolved in the editor even though the build generates them. R/BuildConfig work
 * because they live in `<namespace>`, a real source package.
 */
class SyntheticPackageResolutionReproTest {

    private val jdkHome = File(System.getProperty("java.home"))
    private var env: JavaEnvironment? = null

    @AfterTest
    fun tearDown() {
        env?.close()
    }

    private val binding = SyntheticClass(
        fqName = "com.example.app.databinding.ActivityMainBinding",
        modifiers = setOf(SyntheticModifier.PUBLIC, SyntheticModifier.FINAL),
        methods = listOf(
            SyntheticMethod(
                "inflate",
                returnType = "com.example.app.databinding.ActivityMainBinding",
                modifiers = setOf(SyntheticModifier.PUBLIC, SyntheticModifier.STATIC),
            ),
        ),
    )

    @Test
    fun `synthetic class in a directoryless package resolves`() {
        val e = JavaEnvironment.create(classpath = emptyList(), sourceRoots = emptyList(), jdkHome = jdkHome)
            .also { env = it }
        e.syntheticProvider = { listOf(binding) }

        // 1. Direct facade lookup by FQN — goes straight through the injected finder.
        val direct = IntellijPsiHost.withParseLock {
            e.facade.findClass(binding.fqName, GlobalSearchScope.allScope(e.project))
        }
        assertNotNull(direct, "facade.findClass should resolve the synthetic binding class by FQN")

        // 2. The real editor path: an import + a usage reference must resolve. This is what breaks —
        //    resolving the qualified reference resolves its qualifier `com.example.app.databinding` as a
        //    PACKAGE first, and the finder never reports that package exists.
        val src = """
            package com.example.app;
            import com.example.app.databinding.ActivityMainBinding;
            class Probe {
                void m() { ActivityMainBinding b = ActivityMainBinding.inflate(); }
            }
        """.trimIndent()
        val file = e.parse("Probe.java", src)

        IntellijPsiHost.withParseLock {
            val refs = PsiTreeUtil.collectElementsOfType(file, PsiJavaCodeReferenceElement::class.java)
            val importRef = refs.first { it.canonicalText == binding.fqName }
            assertNotNull(importRef.resolve(), "the import of the synthetic binding class should resolve")

            val pkg = e.facade.findPackage("com.example.app.databinding")
            assertNotNull(pkg, "the synthetic binding's package should be reported to the facade")
        }
    }
}
