package dev.ide.android.support.metadata

import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CustomViewScannerTest {

    /** Emit a minimal `.class` for [internalName] extending [superInternal] with the given access flags. */
    private fun classBytes(internalName: String, superInternal: String, access: Int): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V1_8, access, internalName, null, superInternal, null)
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun jar(dir: Path, name: String, classes: Map<String, ByteArray>): Path {
        val jar = dir.resolve(name)
        ZipOutputStream(Files.newOutputStream(jar)).use { zip ->
            for ((internal, bytes) in classes) {
                zip.putNextEntry(ZipEntry("$internal.class"))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return jar
    }

    private val PUBLIC = Opcodes.ACC_PUBLIC
    private val ABSTRACT = Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT

    @Test
    fun findsLibraryViewSubclassesByFqnAndDetectsViewGroups() {
        val tmp = Files.createTempDirectory("cvs")
        // android.view.View / ViewGroup are not in the jar (they live in android.jar / the framework); the
        // scanner walks whatever ancestry it can see, so a class extending View directly is found.
        val jar = jar(tmp, "material.jar", mapOf(
            "com/google/android/material/button/MaterialButton" to classBytes(
                "com/google/android/material/button/MaterialButton", "android/widget/Button", PUBLIC),
            "com/example/MyView" to classBytes("com/example/MyView", "android/view/View", PUBLIC),
            "com/example/MyLayout" to classBytes("com/example/MyLayout", "android/view/ViewGroup", PUBLIC),
        ))

        val widgets = CustomViewScanner.scan(listOf(jar))
        val byTag = widgets.associateBy { it.tag }

        assertTrue("com.example.MyView" in byTag, "direct View subclass should be found by FQN")
        assertEquals(false, byTag["com.example.MyView"]!!.isViewGroup)
        assertTrue("com.example.MyLayout" in byTag, "ViewGroup subclass should be found")
        assertEquals(true, byTag["com.example.MyLayout"]!!.isViewGroup, "ViewGroup subclass should be flagged")
    }

    @Test
    fun seedsFrameworkBaseClassesAbsentFromJars() {
        val tmp = Files.createTempDirectory("cvs")
        // The realistic shape: a library view extends a framework base class (android.widget.Button) that is
        // NOT packaged in the jar. Only the framework-widget seed lets the scanner recognise it as a View.
        val jar = jar(tmp, "material.jar", mapOf(
            "com/google/android/material/button/MaterialButton" to classBytes(
                "com/google/android/material/button/MaterialButton", "android/widget/Button", PUBLIC),
            "com/example/MyFrame" to classBytes("com/example/MyFrame", "android/widget/FrameLayout", PUBLIC),
        ))
        val seed = mapOf("Button" to false, "FrameLayout" to true)

        val none = CustomViewScanner.scan(listOf(jar)) // no seed → framework ancestry invisible
        assertTrue(none.isEmpty(), "without the framework seed nothing resolves: ${none.map { it.tag }}")

        val byTag = CustomViewScanner.scan(listOf(jar), seed).associateBy { it.tag }
        assertTrue("com.google.android.material.button.MaterialButton" in byTag, "seeded View subclass found")
        assertEquals(false, byTag["com.google.android.material.button.MaterialButton"]!!.isViewGroup)
        assertEquals(true, byTag["com.example.MyFrame"]!!.isViewGroup, "FrameLayout descendant is a ViewGroup")
    }

    @Test
    fun resolvesViewAncestryAcrossJars() {
        val tmp = Files.createTempDirectory("cvs")
        // appcompat.jar: AppCompatButton extends the framework View.
        val appcompat = jar(tmp, "appcompat.jar", mapOf(
            "androidx/appcompat/widget/AppCompatButton" to classBytes(
                "androidx/appcompat/widget/AppCompatButton", "android/view/View", PUBLIC),
        ))
        // material.jar: MaterialButton extends AppCompatButton (defined in the *other* jar).
        val material = jar(tmp, "material.jar", mapOf(
            "com/google/android/material/button/MaterialButton" to classBytes(
                "com/google/android/material/button/MaterialButton", "androidx/appcompat/widget/AppCompatButton", PUBLIC),
        ))

        val tags = CustomViewScanner.scan(listOf(appcompat, material)).map { it.tag }
        assertTrue("com.google.android.material.button.MaterialButton" in tags,
            "a subclass whose View ancestor lives in another jar must still be recognised")
        assertTrue("androidx.appcompat.widget.AppCompatButton" in tags)
    }

    @Test
    fun excludesFrameworkAbstractInnerAndNonViewClasses() {
        val tmp = Files.createTempDirectory("cvs")
        val jar = jar(tmp, "lib.jar", mapOf(
            // Framework package — covered by SDK metadata, must be excluded.
            "android/widget/TextView" to classBytes("android/widget/TextView", "android/view/View", PUBLIC),
            // Abstract custom view — not directly usable as a tag.
            "com/example/AbstractView" to classBytes("com/example/AbstractView", "android/view/View", ABSTRACT),
            // Inner class — not a usable tag.
            "com/example/Outer\$Inner" to classBytes("com/example/Outer\$Inner", "android/view/View", PUBLIC),
            // Not a View at all.
            "com/example/Helper" to classBytes("com/example/Helper", "java/lang/Object", PUBLIC),
        ))

        val tags = CustomViewScanner.scan(listOf(jar)).map { it.tag }
        assertFalse("android.widget.TextView" in tags, "framework views are excluded")
        assertFalse("com.example.AbstractView" in tags, "abstract views are excluded")
        assertFalse(tags.any { it.contains('$') }, "inner classes are excluded")
        assertFalse("com.example.Helper" in tags, "non-View classes are excluded")
    }

    @Test
    fun cachedReusesScanUntilFingerprintChanges() {
        val tmp = Files.createTempDirectory("cvs")
        val jar = jar(tmp, "lib.jar", mapOf(
            "com/example/MyView" to classBytes("com/example/MyView", "android/view/View", PUBLIC),
        ))
        val cache = tmp.resolve("cache/views.txt")

        val first = CustomViewScanner.cached(listOf(jar), cache)
        assertTrue(Files.isRegularFile(cache), "cache file is written")
        assertEquals(listOf("com.example.MyView"), first.map { it.tag })

        // Second call with the same (unchanged) jar reads the cache and yields the same result.
        val second = CustomViewScanner.cached(listOf(jar), cache)
        assertEquals(first.map { it.tag }, second.map { it.tag })
    }
}
