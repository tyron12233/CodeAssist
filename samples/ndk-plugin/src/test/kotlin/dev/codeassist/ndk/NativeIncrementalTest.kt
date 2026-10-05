package dev.codeassist.ndk

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class DepFileTest {

    @Test
    fun readsEveryPrerequisiteAcrossContinuationLines() {
        val text = "/m/build/obj/a.cpp.o: /m/src/a.cpp /m/src/a.h \\\n  /m/src/b.h \\\n  /tc/include/vector\n"
        assertEquals(listOf("/m/src/a.cpp", "/m/src/a.h", "/m/src/b.h", "/tc/include/vector"), DepFile.parse(text))
    }

    @Test
    fun keepsAnEscapedSpaceInsideAPath() {
        val text = "out.o: /my\\ project/a.cpp /my\\ project/a.h\n"
        assertEquals(listOf("/my project/a.cpp", "/my project/a.h"), DepFile.parse(text))
    }

    @Test
    fun aTargetWithAnEscapedSpaceDoesNotEndTheRuleEarly() {
        val text = "/my\\ project/a.o: /my\\ project/a.cpp\n"
        assertEquals(listOf("/my project/a.cpp"), DepFile.parse(text))
    }

    @Test
    fun handlesWindowsLineEndings() {
        assertEquals(listOf("a.cpp", "a.h"), DepFile.parse("a.o: a.cpp \\\r\n a.h\r\n"))
    }

    @Test
    fun stopsAtASecondRule() {
        // -MP adds an empty rule per header; those are not prerequisites of the object.
        assertEquals(listOf("a.cpp", "a.h"), DepFile.parse("a.o: a.cpp a.h\na.h:\n"))
    }

    @Test
    fun noRuleIsNoDependencies() {
        assertEquals(emptyList<String>(), DepFile.parse(""))
        assertEquals(emptyList<String>(), DepFile.parse("garbage without a colon"))
    }
}

class NativeIncrementalTest {

    @TempDir
    lateinit var dir: Path

    private val times = HashMap<Path, Long>()
    private val mtime: (Path) -> Long? = { times[it.normalize()] }

    private fun file(name: String, time: Long, text: String = ""): Path =
        dir.resolve(name).also {
            Files.createDirectories(it.parent)
            Files.write(it, text.toByteArray())
            times[it.normalize()] = time
        }

    @Test
    fun anObjectNewerThanItsSourceAndHeadersIsCurrent() {
        val src = file("a.cpp", 10)
        val header = file("a.h", 10)
        val obj = file("a.cpp.o", 20)
        val dep = file("a.cpp.o.d", 20, "$obj: $src $header\n")
        assertFalse(NativeIncremental.isStale(src, obj, dep, mtime))
    }

    @Test
    fun editingOnlyAHeaderMakesTheObjectStale() {
        val src = file("a.cpp", 10)
        val header = file("a.h", 30)
        val obj = file("a.cpp.o", 20)
        val dep = file("a.cpp.o.d", 20, "$obj: $src $header\n")
        assertTrue(NativeIncremental.isStale(src, obj, dep, mtime))
    }

    @Test
    fun aNewerSourceMakesTheObjectStale() {
        val src = file("a.cpp", 30)
        val obj = file("a.cpp.o", 20)
        val dep = file("a.cpp.o.d", 20, "$obj: $src\n")
        assertTrue(NativeIncremental.isStale(src, obj, dep, mtime))
    }

    @Test
    fun aDeletedHeaderMakesTheObjectStale() {
        val src = file("a.cpp", 10)
        val obj = file("a.cpp.o", 20)
        val dep = file("a.cpp.o.d", 20, "$obj: $src ${dir.resolve("gone.h")}\n")
        assertTrue(NativeIncremental.isStale(src, obj, dep, mtime))
    }

    @Test
    fun withoutADependencyFileNothingIsTrusted() {
        val src = file("a.cpp", 10)
        val obj = file("a.cpp.o", 20)
        assertTrue(NativeIncremental.isStale(src, obj, dir.resolve("a.cpp.o.d"), mtime))
    }

    @Test
    fun aMissingObjectIsStale() {
        val src = file("a.cpp", 10)
        assertTrue(NativeIncremental.isStale(src, dir.resolve("a.cpp.o"), dir.resolve("a.cpp.o.d"), mtime))
    }

    @Test
    fun linksWhenSomethingCompiledOrTheLinkChanged() {
        val out = file("libx.so", 50)
        val obj = file("a.cpp.o", 40)
        assertFalse(NativeIncremental.needsLink(false, out, listOf(obj), "s", "s", mtime))
        assertTrue(NativeIncremental.needsLink(true, out, listOf(obj), "s", "s", mtime))
        assertTrue(NativeIncremental.needsLink(false, out, listOf(obj), "s2", "s", mtime), "objects or libraries changed")
        assertTrue(NativeIncremental.needsLink(false, dir.resolve("missing.so"), listOf(obj), "s", "s", mtime))
        times[obj.normalize()] = 60
        assertTrue(NativeIncremental.needsLink(false, out, listOf(obj), "s", "s", mtime), "an object is newer than the library")
    }

    @Test
    fun recordedDependenciesCollectsEveryDepFile() {
        file("obj/arm64-v8a/src/a.cpp.o.d", 1, "a.o: /m/a.cpp /m/a.h\n")
        file("obj/arm64-v8a/src/b.cpp.o.d", 1, "b.o: /m/b.cpp /inc/x.h\n")
        assertEquals(
            setOf("/m/a.cpp", "/m/a.h", "/m/b.cpp", "/inc/x.h"),
            NativeIncremental.recordedDependencies(dir.resolve("obj")),
        )
    }
}
