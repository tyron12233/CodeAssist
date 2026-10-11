package dev.ide.android.support.apilevels

import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AndroidApiDatabaseTest {

    private val xml = """
        <?xml version="1.0" encoding="utf-8"?>
        <api version="3">
            <class name="java/lang/Object" since="1">
                <method name="&lt;init>()V"/>
                <method name="toString()Ljava/lang/String;"/>
            </class>
            <class name="java/util/List" since="1">
                <extends name="java/lang/Object"/>
                <method name="of()Ljava/util/List;" since="30"/>
            </class>
            <class name="a/Base" since="1">
                <extends name="java/lang/Object"/>
                <method name="hoisted()V" since="29"/>
                <method name="fresh(I)V" since="31" deprecated="33"/>
                <method name="fresh(Ljava/lang/String;)V" since="24"/>
                <field name="CONST" since="26"/>
            </class>
            <class name="a/Sub" since="5">
                <extends name="a/Base"/>
                <method name="hoisted()V"/>
            </class>
            <class name="a/Outer${'$'}Inner" since="21" deprecated="28" removed="30">
                <extends name="java/lang/Object"/>
            </class>
        </api>
    """.trimIndent()

    private fun db(desugared: Set<String> = emptySet()): AndroidApiDatabase {
        val parsed = ApiVersionsXml.parse(ByteArrayInputStream(xml.toByteArray()), desugared)
        return AndroidApiDatabase.read(ByteArrayInputStream(AndroidApiDatabase.write(parsed.platformLevel, parsed.classes)))
    }

    @Test
    fun roundTripKeepsClassLevelsUnderEverySpelling() {
        val db = db()
        assertEquals(31, db.platformLevel, "highest since")
        for (name in listOf("a/Outer\$Inner", "a.Outer\$Inner", "a.Outer.Inner")) {
            val level = assertNotNull(db.classLevel(name), name)
            assertEquals(21, level.since)
            assertEquals(28, level.deprecatedIn)
            assertEquals(30, level.removedIn)
        }
        assertNull(db.classLevel("a.Missing"))
    }

    @Test
    fun memberWithoutLevelTakesItsClassesNotTheSupertypes() {
        // a/Sub lists hoisted() with no level: it has had it since the class appeared (5), even though the
        // supertype only gained it at 29. Walking up first would wrongly report 29.
        assertEquals(5, db().methodLevel("a.Sub", "hoisted", "()V")?.since)
    }

    @Test
    fun memberNotListedIsFoundThroughSupertypes() {
        val db = db()
        assertEquals(26, db.fieldLevel("a.Sub", "CONST")?.since)
        assertEquals(31, db.methodLevel("a.Sub", "fresh", "(I)V")?.since)
        assertEquals(1, db.methodLevel("a.Sub", "toString", "()Ljava/lang/String;")?.since)
    }

    @Test
    fun overloadsMergeConservativelyWithoutADescriptor() {
        val db = db()
        val any = assertNotNull(db.methodLevel("a.Base", "fresh", null))
        assertEquals(24, any.since, "earliest overload")
        assertEquals(0, any.deprecatedIn, "deprecated only when every overload is")
        assertEquals(24, db.methodLevel("a.Base", "fresh", null, arity = 1)?.since)
        assertNull(db.methodLevel("a.Base", "fresh", null, arity = 2))
        val byDescriptor = assertNotNull(db.methodLevel("a.Base", "fresh", "(I)V"))
        assertEquals(33, byDescriptor.deprecatedIn)
    }

    @Test
    fun backportedMethodsAreMarkedDesugared() {
        val db = db(desugared = setOf("java/util/List#of()Ljava/util/List;"))
        assertTrue(db.methodLevel("java.util.List", "of", "()Ljava/util/List;")!!.desugared)
        assertFalse(db.methodLevel("a.Base", "hoisted", "()V")!!.desugared)
    }

    @Test
    fun arityCountsArraysAndObjectTypes() {
        assertEquals(0, AndroidApiDatabase.arityOf("f()V", 1))
        assertEquals(3, AndroidApiDatabase.arityOf("f(I[Ljava/lang/String;[[J)V", 1))
    }

    @Test
    fun bundledTableAnswersRealPlatformFacts() {
        val db = assertNotNull(AndroidApiDatabase.bundled(), "android-api-levels.bin is bundled")
        assertTrue(db.platformLevel >= 36)
        assertEquals(26, db.classLevel("android.app.NotificationChannel")?.since)
        assertEquals(23, db.methodLevel("android.content.Context", "getColor", "(I)I")?.since)
        // Inherited through Activity -> ContextThemeWrapper -> ContextWrapper -> Context.
        assertEquals(23, db.methodLevel("android.app.Activity", "getColor", "(I)I")?.since)
        assertEquals(28, db.methodLevel("android.app.Activity", "requireViewById", null, arity = 1)?.since)
        assertEquals(26, db.fieldLevel("android.os.Build.VERSION_CODES", "O")?.since)
        assertEquals(26, db.classLevel("android.app.ProgressDialog")?.deprecatedIn)
        assertEquals(1, db.classLevel("android.app.Activity")?.since)
        assertTrue(db.methodLevel("java.util.Objects", "requireNonNull", null)!!.desugared)
    }

    @Test
    fun desugaredMethodsListsTheBackportedOnes() {
        val db = db(desugared = setOf("java/util/List#of()Ljava/util/List;"))
        assertEquals(setOf("java/util/List#of()Ljava/util/List;"), db.desugaredMethods())
        assertTrue(AndroidApiDatabase.bundled()!!.desugaredMethods().any { it.startsWith("java/util/Objects#requireNonNull(") })
    }

    @Test
    fun aNewerPlatformAnswersFromItsOwnApiVersions() {
        val bundled = AndroidApiDatabase.bundled()!!
        val root = java.nio.file.Files.createTempDirectory("platforms")
        fun platform(name: String, apiXml: String?): java.nio.file.Path {
            val dir = java.nio.file.Files.createDirectories(root.resolve(name))
            apiXml?.let { java.nio.file.Files.createDirectories(dir.resolve("data")); java.nio.file.Files.writeString(dir.resolve("data/api-versions.xml"), it) }
            return java.nio.file.Files.write(dir.resolve("android.jar"), byteArrayOf(1))
        }
        val newer = bundled.platformLevel + 1
        val apiXml = """
            <api version="3">
                <class name="android/app/Brand${'$'}New" since="$newer"/>
                <class name="java/util/Objects" since="19">
                    <method name="requireNonNull(Ljava/lang/Object;)Ljava/lang/Object;"/>
                </class>
            </api>
        """.trimIndent()
        val db = AndroidApiDatabase.forPlatform(platform("android-$newer", apiXml))!!
        assertEquals(newer, db.classLevel("android.app.Brand.New")?.since)
        // D8's backports come over from the bundled table, so they stay exempt.
        assertTrue(db.methodLevel("java.util.Objects", "requireNonNull", null)!!.desugared)
        // An older platform, a platform without the file, and no platform at all keep the bundled table.
        assertTrue(AndroidApiDatabase.forPlatform(platform("android-34", apiXml)) === bundled)
        assertTrue(AndroidApiDatabase.forPlatform(platform("android-${newer + 1}", null)) === bundled)
        assertTrue(AndroidApiDatabase.forPlatform(null) === bundled)
    }
}
