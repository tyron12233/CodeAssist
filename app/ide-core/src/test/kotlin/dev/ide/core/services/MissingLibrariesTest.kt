package dev.ide.core.services

import dev.ide.analysis.Codes
import dev.ide.analysis.Diagnostic
import dev.ide.analysis.DiagnosticSource
import dev.ide.lang.dom.Severity
import dev.ide.android.support.AndroidFacet
import dev.ide.core.analysis.MissingLibraryScan
import dev.ide.index.ClassNameValue
import dev.ide.index.Hit
import dev.ide.index.IndexId
import dev.ide.index.IndexOrigin
import dev.ide.index.IndexScope
import dev.ide.index.IndexService
import dev.ide.index.IndexStatus
import dev.ide.lang.dom.TextRange
import dev.ide.model.Coordinate
import dev.ide.model.DependencyScope
import dev.ide.model.FacetContainer
import dev.ide.model.FacetKey
import dev.ide.model.LibraryDependency
import dev.ide.model.LibraryRef
import dev.ide.model.Module
import dev.ide.model.OrderEntry
import dev.ide.model.UnknownModuleType
import dev.ide.platform.Disposable
import dev.ide.ui.backend.UiAddResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MissingLibrariesTest {

    // ---- the catalog ----

    @Test
    fun `the catalog picks the artifact by class where several share a package, else by the longest package`() {
        fun coord(fqn: String, android: Boolean = true) = LibraryCatalog.forFqn(fqn, android)?.coordinate
        assertEquals("androidx.lifecycle:lifecycle-viewmodel-ktx", coord("androidx.lifecycle.ViewModel"))
        assertEquals("androidx.lifecycle:lifecycle-viewmodel-ktx", coord("androidx.lifecycle.viewModelScope"))
        assertEquals("androidx.lifecycle:lifecycle-livedata-ktx", coord("androidx.lifecycle.MutableLiveData"))
        assertEquals("androidx.lifecycle:lifecycle-runtime-ktx", coord("androidx.lifecycle.lifecycleScope"))
        assertEquals("androidx.lifecycle:lifecycle-viewmodel-compose", coord("androidx.lifecycle.viewmodel.compose.viewModel"))
        assertEquals("androidx.compose.ui:ui-tooling-preview", coord("androidx.compose.ui.tooling.preview.Preview"))
        assertEquals("androidx.compose.ui:ui", coord("androidx.compose.ui.Modifier"))
        assertEquals("androidx.recyclerview:recyclerview", coord("androidx.recyclerview.widget.RecyclerView.ViewHolder"))
        assertEquals("com.squareup.retrofit2:converter-gson", coord("retrofit2.converter.gson.GsonConverterFactory"))
        assertEquals("com.squareup.retrofit2:retrofit", coord("retrofit2.http.GET"))
        assertEquals("org.jetbrains.kotlinx:kotlinx-coroutines-test", coord("kotlinx.coroutines.test.runTest"))
        assertEquals("org.jetbrains.kotlinx:kotlinx-coroutines-android", coord("kotlinx.coroutines.flow.StateFlow"))
        assertEquals("org.jetbrains.kotlinx:kotlinx-coroutines-core", coord("kotlinx.coroutines.flow.StateFlow", android = false))
        assertNull(coord("androidx.appcompat.app.AppCompatActivity", android = false), "an Android library is no answer for a JVM module")
        assertNull(coord("com.example.app.Thing"))
    }

    @Test
    fun `Guava comes in the flavor the module needs`() {
        val android = LibraryCatalog.forFqn("com.google.common.collect.ImmutableList", true)!!
        val jvm = LibraryCatalog.forFqn("com.google.common.collect.ImmutableList", false)!!
        assertEquals("-android", android.versionSuffix)
        assertEquals("-jre", jvm.versionSuffix)
    }

    @Test
    fun `a class typed by name finds its library only for names that mean one library`() {
        assertEquals("com.google.code.gson:gson" to "com.google.gson.Gson",
            LibraryCatalog.forSimpleName("Gson", true)?.let { it.first.coordinate to it.second })
        assertEquals("androidx.appcompat.app.AppCompatActivity", LibraryCatalog.forSimpleName("AppCompatActivity", true)?.second)
        assertNull(LibraryCatalog.forSimpleName("AppCompatActivity", false))
        assertNull(LibraryCatalog.forSimpleName("gson", true))
        assertNull(LibraryCatalog.forSimpleName("Request", true))
    }

    @Test
    fun `every catalog entry is a well-formed coordinate`() {
        for (e in LibraryCatalog.entries) {
            assertEquals(2, e.coordinate.count { it == ':' } + 1, e.coordinate)
            assertTrue(e.version.isNotBlank(), e.coordinate)
            assertTrue(e.packages.isNotEmpty() || e.classes.isNotEmpty(), "$e names no package and no class")
            e.versionSuffix?.let { assertTrue(e.version.endsWith(it), "$e falls back to a version of the wrong flavor") }
        }
    }

    // ---- what a diagnostic wants ----

    private val libraries = MissingLibraries(FakeHost(), CoroutineScope(Dispatchers.Unconfined))

    @Test
    fun `an import line names the class whatever part of it the diagnostic covers`() {
        val java = "package a;\n\nimport com.google.gson.Gson;\nclass A {}\n"
        val start = java.indexOf("com.google")
        // ecj marks only the package that does not exist.
        assertEquals(MissingLibraries.Wanted.Import("com.google.gson.Gson"),
            libraries.wanted(Codes.UNRESOLVED_REFERENCE, "The import com.google cannot be resolved", java, start, start + 10))
        assertEquals("a.b.C.d", libraries.importTarget("import static a.b.C.d;"))
        assertEquals("androidx.compose.ui.Modifier", libraries.importTarget("import androidx.compose.ui.Modifier as M"))
        assertEquals("kotlinx.coroutines", libraries.importTarget("import kotlinx.coroutines.*"))
        assertEquals("okhttp3.OkHttpClient", libraries.importTarget("import okhttp3.OkHttpClient // client"))
        assertNull(libraries.importTarget("import Foo"))
    }

    @Test
    fun `a bare unresolved name is a class only when it is capitalized`() {
        val kt = "fun f() {\n    val g = Gson()\n    foo()\n}\n"
        val g = kt.indexOf("Gson")
        assertEquals(MissingLibraries.Wanted.SimpleName("Gson"), libraries.wanted("kt.unresolved", "Unresolved reference: Gson", kt, g, g + 4))
        val foo = kt.indexOf("foo")
        assertNull(libraries.wanted("kt.unresolved", "Unresolved reference: foo", kt, foo, foo + 3))
        assertNull(libraries.wanted("kt.typeMismatch", "Type mismatch", kt, g, g + 4))
    }

    @Test
    fun `an unknown layout tag is a class only when it is qualified`() {
        assertEquals(MissingLibraries.Wanted.Tag("androidx.constraintlayout.widget.ConstraintLayout"),
            libraries.wanted("android.unknownTag", "Cannot resolve element <androidx.constraintlayout.widget.ConstraintLayout>", "", 0, 0))
        assertNull(libraries.wanted("android.unknownTag", "Cannot resolve element <Buton>. Did you mean <Button>?", "", 0, 0))
    }

    // ---- versions ----

    @Test
    fun `only stable versions of the right flavor count`() {
        assertTrue(MissingLibraries.isStable("1.7.0", null))
        assertFalse(MissingLibraries.isStable("1.8.0-alpha02", null))
        assertFalse(MissingLibraries.isStable("2.0.0-RC1", null))
        assertFalse(MissingLibraries.isStable("1.0.0-beta01", null))
        assertTrue(MissingLibraries.isStable("33.4.0-android", "-android"))
        assertFalse(MissingLibraries.isStable("33.4.0-jre", "-android"))
        assertEquals(listOf("1.13.1", "1.12.0", "1.9.3"), MissingLibraries.newestPerMinorLine(listOf("1.13.1", "1.13.0", "1.12.0", "1.9.3", "1.9.0")))
    }

    @Test
    fun `an Android module gets the newest version its minSdk and compileSdk can take`() {
        val dir = Files.createTempDirectory("aars")
        val host = FakeHost(
            versions = listOf("1.15.0", "1.15.0-beta01", "1.14.1", "1.14.0", "1.13.1"),
            aars = mapOf(
                "1.15.0" to aar(dir, "a", minSdk = 23, minCompileSdk = 35),
                "1.14.1" to aar(dir, "b", minSdk = 21, minCompileSdk = 34),
            ),
        )
        val missing = MissingLibraries(host, CoroutineScope(Dispatchers.Unconfined))
        val core = MissingLibraries.Library("androidx.core:core-ktx", "Core", "1.13.1")
        assertEquals("1.14.1", runBlocking { missing.versionFor(core, module(android = AndroidFacet("a", compileSdk = 34, minSdk = 21))) })
        assertEquals("1.15.0", runBlocking { missing.versionFor(core, module(android = AndroidFacet("a", compileSdk = 35, minSdk = 24))) })
        assertEquals("1.15.0", runBlocking { missing.versionFor(core, module()) }, "a JVM module has no SDK levels to respect")
        assertEquals("1.13.1", runBlocking { MissingLibraries(FakeHost(), CoroutineScope(Dispatchers.Unconfined)).versionFor(core, module()) }, "offline")
        assertEquals("1.0", runBlocking { missing.versionFor(core.copy(version = "1.0", pinned = true), module()) })
        dir.toFile().deleteRecursively()
    }

    @Test
    fun `an AAR's requirements are read from its manifest and metadata`() {
        val dir = Files.createTempDirectory("aar")
        assertEquals(MissingLibraries.AarRequirements(23, 35), MissingLibraries.aarRequirements(aar(dir, "x", 23, 35)))
        assertEquals(MissingLibraries.AarRequirements(null, null), MissingLibraries.aarRequirements(dir.resolve("absent.aar")))
        dir.toFile().deleteRecursively()
    }

    @Test
    fun `the class search picks the library itself over its repackagings`() {
        val hits = listOf(
            Coordinate("org.example.shaded", "gson-shaded", "1"),
            Coordinate("com.google.code.gson", "gson-parent", "2"),
            Coordinate("com.google.code.gson", "gson", "2"),
        )
        assertEquals(Coordinate("com.google.code.gson", "gson", "2"), MissingLibraries.bestHit("com.google.gson.Gson", hits))
        assertTrue(MissingLibraries.searchable("org.apache.poi.ss.usermodel.Workbook"))
        assertFalse(MissingLibraries.searchable("java.util.Lisst"))
        assertFalse(MissingLibraries.searchable("android.widget.Buton"))
    }

    // ---- adding ----

    @Test
    fun `adding skips what is declared and puts test libraries in the test configuration`() {
        val host = FakeHost(versions = listOf("2.11.0"))
        host.modules["app"] = module(deps = listOf(lib("com.google.code.gson:gson:2.10.1")))
        val missing = MissingLibraries(host, CoroutineScope(Dispatchers.Unconfined))
        val result = runBlocking {
            missing.add("app", listOf(
                MissingLibraries.Library("com.google.code.gson:gson", "Gson", "2.11.0"),
                MissingLibraries.Library("com.squareup.okhttp3:okhttp", "OkHttp", "4.12.0"),
                MissingLibraries.Library("junit:junit", "JUnit 4", "4.13.2", test = true),
            ), testSource = false)
        }
        assertTrue(result.success, result.message)
        assertEquals(
            listOf("com.squareup.okhttp3:okhttp:2.11.0" to "implementation", "junit:junit:2.11.0" to "testImplementation"),
            host.added,
        )
        assertEquals(result, missing.lastOutcome.value)
    }

    // ---- the file scan and the import edit ----

    @Test
    fun `a file's missing libraries are counted once each, leaving out declared ones and known classes`() {
        val text = """
            package a

            import com.google.gson.Gson
            import retrofit2.Retrofit
            import okhttp3.OkHttpClient

            fun f() = Glide.with(x) + Gson() + Known()
        """.trimIndent()
        fun at(word: String, code: String = "kt.unresolved") = text.indexOf(word).let {
            Diagnostic(TextRange(it, it + word.length), Severity.ERROR, "Unresolved reference: $word", DiagnosticSource.Compiler, code)
        }
        val diagnostics = listOf(at("com.google.gson.Gson"), at("retrofit2.Retrofit"), at("okhttp3.OkHttpClient"),
            at("Glide"), at("Known"), at("Gson()"))
        val module = module(deps = listOf(lib("com.squareup.okhttp3:okhttp:4.12.0")), android = AndroidFacet("a", 35))
        val needs = MissingLibraryScan.needs(libraries, diagnostics, text, module, index("Known"))
        assertEquals(
            listOf("com.google.code.gson:gson" to null, "com.squareup.retrofit2:retrofit" to null, "com.github.bumptech.glide:glide" to "com.bumptech.glide.Glide"),
            needs.map { it.library.coordinate to it.importFqn },
        )
    }

    @Test
    fun `imports go in sorted place, as one block`() {
        val java = "package a;\n\nimport android.os.Bundle;\nimport java.util.List;\n\nclass A {}\n"
        val edit = MissingLibraryScan.importInsertion(java, listOf("com.google.gson.Gson", "androidx.appcompat.app.AppCompatActivity"), java = true)!!
        val out = java.substring(0, edit.offset) + edit.newText + java.substring(edit.offset + edit.oldLength)
        assertEquals("package a;\n\nimport android.os.Bundle;\nimport androidx.appcompat.app.AppCompatActivity;\nimport com.google.gson.Gson;\nimport java.util.List;\n\nclass A {}\n", out)

        val kt = "package a\n\nfun f() {}\n"
        val e2 = MissingLibraryScan.importInsertion(kt, listOf("com.google.gson.Gson"), java = false)!!
        assertEquals("package a\n\nimport com.google.gson.Gson\n\nfun f() {}\n", kt.substring(0, e2.offset) + e2.newText + kt.substring(e2.offset))
        assertNull(MissingLibraryScan.importInsertion("import com.google.gson.Gson\n", listOf("com.google.gson.Gson"), java = false))
    }

    // ---- fakes ----

    private class FakeHost(
        val versions: List<String> = emptyList(),
        val aars: Map<String, Path> = emptyMap(),
    ) : MissingLibraries.Host {
        val modules = HashMap<String, Module>()
        val added = ArrayList<Pair<String, String>>()
        override suspend fun publishedVersions(group: String, name: String) = versions
        override suspend fun artifactsWithClass(fqn: String): List<Coordinate>? = null
        override suspend fun fetchAar(coordinate: Coordinate) = aars[coordinate.version]
        override fun moduleNamed(name: String) = modules[name]
        override fun isAndroidModule(module: Module) = module.facets.get(AndroidFacet.KEY) != null
        override fun declaredUnresolved(module: Module) = emptyList<String>()
        override suspend fun addDependency(moduleName: String, coordinate: String, scope: String): UiAddResult {
            added += coordinate to scope
            return UiAddResult(true, "Added $coordinate")
        }
        override suspend fun retryDependencyResolution() {}
    }

    private fun lib(coordinate: String) = LibraryDependency(LibraryRef(coordinate), DependencyScope.IMPLEMENTATION)

    private fun module(deps: List<OrderEntry> = emptyList(), android: AndroidFacet? = null): Module {
        val facets = object : FacetContainer {
            @Suppress("UNCHECKED_CAST")
            override fun <T : dev.ide.model.Facet> get(key: FacetKey<T>): T? = if (key == AndroidFacet.KEY) android as T? else null
            override val all = listOfNotNull<dev.ide.model.Facet>(android)
        }
        val type = UnknownModuleType(if (android != null) "android-app" else "java-lib")
        return Proxy.newProxyInstance(Module::class.java.classLoader, arrayOf(Module::class.java)) { _, method, _ ->
            when (method.name) {
                "getName" -> "app"
                "getDependencies" -> deps
                "getFacets" -> facets
                "getType" -> type
                "hashCode" -> 1
                "toString" -> "module app"
                "equals" -> false
                else -> throw UnsupportedOperationException(method.name)
            }
        } as Module
    }

    private fun aar(dir: Path, name: String, minSdk: Int, minCompileSdk: Int): Path {
        val file = dir.resolve("$name.aar")
        ZipOutputStream(Files.newOutputStream(file)).use { zip ->
            zip.putNextEntry(ZipEntry("AndroidManifest.xml"))
            zip.write("""<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="x"><uses-sdk android:minSdkVersion="$minSdk" /></manifest>""".toByteArray())
            zip.putNextEntry(ZipEntry("META-INF/com/android/build/gradle/aar-metadata.properties"))
            zip.write("aarFormatVersion=1.0\nminCompileSdk=$minCompileSdk\n".toByteArray())
        }
        return file
    }

    /** A ready class-name index that knows only [names]. */
    private fun index(vararg names: String) = object : IndexService {
        @Suppress("UNCHECKED_CAST")
        override fun <V : Any> exact(id: IndexId, key: String): Sequence<V> =
            if (key in names) sequenceOf(ClassNameValue("p.$key", IndexOrigin.SOURCE, "class") as V) else emptySequence()
        override fun <V : Any> prefix(id: IndexId, prefix: String, limit: Int): Sequence<Hit<V>> = emptySequence()
        override fun <V : Any> fuzzy(id: IndexId, pattern: String, limit: Int): Sequence<Hit<V>> = emptySequence()
        override suspend fun ensureUpToDate(scope: IndexScope) {}
        override suspend fun reindexSource(path: Path, text: String) {}
        override val status = IndexStatus(ready = true)
        override fun observeStatus(listener: (IndexStatus) -> Unit) = Disposable { }
    }
}
