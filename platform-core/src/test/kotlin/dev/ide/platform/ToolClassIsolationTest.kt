// Copyright (C) 2026 tyron12233
// SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
// See LICENSE-EXCEPTION: a plugin linking against this file may use any license.
package dev.ide.platform

import dev.ide.testkit.JarBuilder
import dev.ide.testkit.TestJars
import dev.ide.testkit.withTempDir
import java.lang.reflect.InvocationTargetException
import java.net.URLClassLoader
import java.nio.file.Path
import java.util.function.Function
import java.util.function.Supplier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.objectweb.asm.Opcodes

/**
 * [ToolClassIsolation.CHILD_FIRST_PACKAGES] is what keeps a KSP processor bundle's guava consistent with
 * itself. The app ships one guava version (bundletool's) and every processor bundle ships its own, and on ART
 * the app's copy is dexed at build time while the bundle's is dexed on device. D8 names each lambda's synthetic
 * class after its context class plus a per-compilation index, so the same
 * `com.google.common.**$$ExternalSyntheticLambdaN` name denotes a different lambda in the two dex files. Under
 * plain parent-first the app's copy of that name wins over the bundle's and the processor dies with
 * `IncompatibleClassChangeError: Class 'com.google.common.collect.CollectCollectors$$ExternalSyntheticLambda62'
 * does not implement interface 'java.util.function.Supplier'`, which is what enabling Hilt hit.
 *
 * The collision needs no dex to reproduce: two jars defining that one name over different functional
 * interfaces are the shape D8 produces from two guava versions, and the delegation policy under test is the
 * same one `ArtKotlinPluginLoader` applies on device.
 */
class ToolClassIsolationTest {

    private companion object {
        /** A guava lambda synthetic: `Supplier` as the bundle dexed it, `Function` in the app's dex. */
        const val SYNTHETIC = "com/google/common/collect/CollectCollectors\$\$ExternalSyntheticLambda62"

        /** Stands in for a bundle-only class (dagger's codegen) that consumes the synthetic as a `Supplier`. */
        const val ENTRY = "demo/processor/Entry"

        /** A guava class only the app carries, to pin the fallback for a class the bundle lacks. */
        const val APP_ONLY = "com/google/common/base/AppOnly"
    }

    /** A class with a public no-arg constructor whose [iface] method returns the constant [result]. */
    private fun JarBuilder.functionalClass(
        name: String,
        iface: String,
        method: String,
        descriptor: String,
        result: String,
    ) = asmClass(name, interfaces = arrayOf(iface)) {
        visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null).run {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
            visitInsn(Opcodes.RETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
        visitMethod(Opcodes.ACC_PUBLIC, method, descriptor, null, null).run {
            visitCode()
            visitLdcInsn(result)
            visitInsn(Opcodes.ARETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
    }

    /** `static Object call()` = `((Supplier) new <SYNTHETIC>()).get()`, linked against the bundle's shape. */
    private fun JarBuilder.entryCallingTheSyntheticAsASupplier() = asmClass(ENTRY) {
        visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "call", "()Ljava/lang/Object;", null, null).run {
            visitCode()
            visitTypeInsn(Opcodes.NEW, SYNTHETIC)
            visitInsn(Opcodes.DUP)
            visitMethodInsn(Opcodes.INVOKESPECIAL, SYNTHETIC, "<init>", "()V", false)
            visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/function/Supplier", "get", "()Ljava/lang/Object;", true)
            visitInsn(Opcodes.ARETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
    }

    /** The app's guava jar and the bundle's, which disagree on what [SYNTHETIC] is. */
    private fun guavaPair(dir: Path): Pair<Path, Path> {
        val app = TestJars.buildJar(dir.resolve("app-guava.jar")) {
            functionalClass(SYNTHETIC, "java/util/function/Function", "apply", "(Ljava/lang/Object;)Ljava/lang/Object;", "app")
            asmClass(APP_ONLY)
        }
        val bundle = TestJars.buildJar(dir.resolve("bundle-guava.jar")) {
            functionalClass(SYNTHETIC, "java/util/function/Supplier", "get", "()Ljava/lang/Object;", "bundle")
            entryCallingTheSyntheticAsASupplier()
        }
        return app to bundle
    }

    private fun ClassLoader.callEntry(): Any? =
        loadClass(ENTRY.replace('/', '.')).getMethod("call").invoke(null)

    private fun ClassLoader.synthetic(): Class<*> = loadClass(SYNTHETIC.replace('/', '.'))

    @Test
    fun plainParentFirstResolvesTheBundlesGuavaSyntheticToTheAppsCopy() {
        withTempDir("tool-isolation") { dir ->
            val (app, bundle) = guavaPair(dir)
            val parent = URLClassLoader(arrayOf(app.toUri().toURL()), javaClass.classLoader)
            val loader = URLClassLoader(arrayOf(bundle.toUri().toURL()), parent)

            assertTrue(
                Function::class.java.isAssignableFrom(loader.synthetic()),
                "parent-first should hand the bundle the app's synthetic, which is a Function",
            )
            assertFalse(
                Supplier::class.java.isAssignableFrom(loader.synthetic()),
                "the fixture only proves something while the two jars disagree on the interface",
            )
            val failure = assertFailsWith<InvocationTargetException> { loader.callEntry() }
            assertTrue(
                failure.cause is IncompatibleClassChangeError,
                "the mixed pair must fail exactly the way the Hilt processor did: ${failure.cause}",
            )
        }
    }

    @Test
    fun toolClassLoaderResolvesGuavaFromTheBundlesOwnJars() {
        withTempDir("tool-isolation") { dir ->
            val (app, bundle) = guavaPair(dir)
            val parent = URLClassLoader(arrayOf(app.toUri().toURL()), javaClass.classLoader)
            val loader = ToolUrlClassLoader(arrayOf(bundle.toUri().toURL()), parent)

            assertTrue(
                Supplier::class.java.isAssignableFrom(loader.synthetic()),
                "the tool classloader must take com.google.common.* from the bundle's own jars",
            )
            assertEquals("bundle", loader.callEntry(), "the bundle's guava must link against its own synthetics")
            // A guava class the bundle does not carry still comes from the app, so listing the package is safe.
            assertSame(
                parent,
                loader.loadClass(APP_ONLY.replace('/', '.')).classLoader,
                "child-first must fall back to the parent for a class the bundle lacks",
            )
        }
    }

    @Test
    fun childFirstCoversGuavaAndDaggerButNotTheSharedSpi() {
        assertTrue(ToolClassIsolation.isChildFirst("com.google.common.collect.ImmutableList"))
        assertTrue(ToolClassIsolation.isChildFirst(SYNTHETIC.replace('/', '.')))
        assertTrue(ToolClassIsolation.isChildFirst("dagger.internal.DoubleCheck"))
        // The types that cross the boundary have to stay parent-loaded, or the two sides hold different classes.
        assertFalse(ToolClassIsolation.isChildFirst("kotlin.Unit"))
        assertFalse(ToolClassIsolation.isChildFirst("com.google.devtools.ksp.processing.SymbolProcessorProvider"))
        assertFalse(ToolClassIsolation.isChildFirst("com.google.gson.Gson"), "the prefix is com.google.common., not com.google.")
    }
}
