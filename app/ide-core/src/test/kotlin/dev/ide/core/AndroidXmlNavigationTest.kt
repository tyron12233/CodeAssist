package dev.ide.core

import dev.ide.lang.kotlin.NavKind
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Android Go to / Usages / Related / Quick Doc / swatches / rename through the real engine, on the demo
 * project: `app` (namespace `com.example.app`) with `values/strings.xml` (`greeting`), `colors.xml`
 * (`primary`), `attrs.xml` (`MyChart` styleable with `barColor`), `layout/activity_main.xml`, the manifest's
 * `.MainActivity`, and the `MyChart` custom view.
 */
class AndroidXmlNavigationTest {

    private val root = createTempDirectory("android-xml-nav")
    private var services: IdeServices? = null

    @AfterTest
    fun tearDown() {
        services?.close()
        root.toFile().deleteRecursively()
    }

    private val app get() = root.resolve("app/src/main")
    private val layout get() = app.resolve("res/layout/activity_main.xml")
    private val strings get() = app.resolve("res/values/strings.xml")
    private val home get() = app.resolve("java/com/example/app/Home.kt")

    /** Bootstraps the demo, adds a Spanish `greeting` and a Kotlin class that inflates the layout, and waits
     *  until the index has both. */
    private fun boot(): IdeServices {
        val s = IdeServices.bootstrapDemo(root).also { services = it }
        awaitIndexed(s)
        // Usages/Related read the reference index once it's ready (else they sweep the project): test that path.
        assertTrue(s.indexService.status.ready, "index ready")
        s.save(app.resolve("res/values-es/strings.xml"), """<resources><string name="greeting">Hola</string></resources>""")
        s.save(home, "package com.example.app\n\nclass Home {\n    val id = R.layout.activity_main\n    val c = R.color.primary\n}\n")
        eventually("the Spanish string is indexed") {
            val text = Files.readString(layout)
            s.navigationTargets(layout, text, text.indexOf("greeting"), NavKind.DECLARATION).size == 2
        }
        return s
    }

    @Test
    fun goToDeclarationUsagesAndRelated() {
        val s = boot()
        val layoutText = Files.readString(layout)

        // Every variant of @string/greeting, the default configuration first.
        val decls = s.navigationTargets(layout, layoutText, layoutText.indexOf("greeting"), NavKind.DECLARATION)
        assertEquals(listOf("values/strings.xml", "values-es/strings.xml"), decls.map { it.label })
        assertTrue(Files.readString(Path.of(decls[0].file.path)).startsWith("greeting", decls[0].offset), "lands on the name")

        // A class named in XML: the manifest's `.MainActivity`, and the custom view tag.
        val manifest = app.resolve("AndroidManifest.xml")
        val manifestText = Files.readString(manifest)
        val activity = s.navigationTargets(manifest, manifestText, manifestText.indexOf("MainActivity"), NavKind.DECLARATION)
        assertTrue(activity.single().file.path.endsWith("MainActivity.java"), "got $activity")
        val chart = s.navigationTargets(layout, layoutText, layoutText.indexOf("MyChart") + 2, NavKind.DECLARATION)
        assertTrue(chart.single().file.path.endsWith("MyChart.java"), "got $chart")

        // A custom attribute name goes to its <attr>.
        val attr = s.navigationTargets(layout, layoutText, layoutText.indexOf("barColor") + 1, NavKind.DECLARATION)
        assertTrue(attr.single().file.path.endsWith("attrs.xml"), "got $attr")

        // Usages of the declaration: the layout's @string/greeting and MainActivity's R.string.greeting.
        val stringsText = Files.readString(strings)
        val usages = s.navigationTargets(strings, stringsText, stringsText.indexOf("greeting"), NavKind.USAGES)
        val usedIn = usages.map { Path.of(it.file.path).fileName.toString() }.toSet()
        assertEquals(setOf("activity_main.xml", "MainActivity.java"), usedIn)

        // Related: the layout and the class that inflates it, both ways.
        val fromLayout = s.navigationTargets(layout, layoutText, 1, NavKind.RELATED)
        assertTrue(fromLayout.any { it.file.path.endsWith("Home.kt") }, "got $fromLayout")
        val homeText = Files.readString(home)
        val fromClass = s.navigationTargets(home, homeText, 0, NavKind.RELATED)
        assertTrue(fromClass.any { it.file.path.endsWith("activity_main.xml") }, "got $fromClass")

        // The Go to menu offers what applies.
        val kinds = s.navigationOptions(strings, stringsText, stringsText.indexOf("greeting")).map { it.first }
        assertTrue(NavKind.USAGES in kinds, "got $kinds")
    }

    @Test
    fun quickDocAndSwatches() {
        val s = boot()
        val layoutText = Files.readString(layout)

        val attrDoc = assertNotNull(s.quickDocAt(layout, layoutText, layoutText.indexOf("textSize") + 2))
        assertEquals("android:textSize", attrDoc.signature)
        assertTrue(attrDoc.doc.orEmpty().contains("dimension"), "got ${attrDoc.doc}")

        val resDoc = assertNotNull(s.quickDocAt(layout, layoutText, layoutText.indexOf("greeting")))
        assertEquals("@string/greeting", resDoc.signature)
        assertTrue(resDoc.doc.orEmpty().contains("Hello, Android") && resDoc.doc.orEmpty().contains("Hola"), "got ${resDoc.doc}")

        val swatches = s.resourceSwatches(layout, layoutText)
        val primary = swatches.firstOrNull { it.offset == layoutText.indexOf("@color/primary") }
        assertEquals(0xFF6200EEL, primary?.argb, "got ${swatches.map { it.offset to it.argb }}")
        assertTrue(primary!!.target!!.first.toString().endsWith("colors.xml"))
        val homeSwatch = s.resourceSwatches(home, Files.readString(home)).single()
        assertEquals(0xFF6200EEL, homeSwatch.argb)
    }

    @Test
    fun renameAValueResourceAndALayout() {
        val s = boot()
        val layoutText = Files.readString(layout)

        val info = assertNotNull(s.prepareRename(layout, layoutText, layoutText.indexOf("greeting")))
        assertEquals("greeting", info.oldName)
        val outcome = runBlocking { s.rename(layout, layoutText, layoutText.indexOf("greeting"), "salutation") }
        assertTrue(outcome.success, outcome.message)
        assertTrue(Files.readString(strings).contains("name=\"salutation\""))
        assertTrue(Files.readString(app.resolve("res/values-es/strings.xml")).contains("name=\"salutation\""))
        assertTrue(Files.readString(layout).contains("@string/salutation"))
        assertTrue(Files.readString(app.resolve("java/com/example/app/MainActivity.java")).contains("R.string.salutation"))

        // A file resource renames its file too, from a code reference.
        val homeText = Files.readString(home)
        val layoutRename = runBlocking { s.rename(home, homeText, homeText.indexOf("activity_main"), "home_screen") }
        assertTrue(layoutRename.success, layoutRename.message)
        assertTrue(Files.exists(app.resolve("res/layout/home_screen.xml")))
        assertTrue(!Files.exists(layout))
        assertTrue(Files.readString(home).contains("R.layout.home_screen"))
    }

    @Test
    fun renamingAnIdFollowsItsBindingFieldAndUnsavedBuffersCount() {
        val s = boot()
        // Give the TextView an id, and a class that reads it through the layout's binding.
        val withId = Files.readString(layout).replace("<TextView", "<TextView\n                android:id=\"@+id/header_title\"")
        s.save(layout, withId)
        val screen = app.resolve("java/com/example/app/Screen.kt")
        s.save(screen, "package com.example.app\n\nclass Screen(val binding: ActivityMainBinding) {\n    fun show() { binding.headerTitle.text = \"x\" }\n}\n")
        eventually("the id and the binding use are indexed") {
            val t = Files.readString(layout)
            s.navigationTargets(layout, t, t.indexOf("header_title"), NavKind.USAGES).any { it.file.path.endsWith("Screen.kt") }
        }

        // An open, unsaved buffer isn't indexed yet, but its use still counts.
        val draft = app.resolve("java/com/example/app/Draft.kt")
        Files.writeString(draft, "package com.example.app\n")
        s.updateDocument(draft, "package com.example.app\n\nval d = R.id.header_title\n")
        val text = Files.readString(layout)
        val usages = s.navigationTargets(layout, text, text.indexOf("header_title"), NavKind.USAGES)
        assertTrue(usages.any { it.file.path.endsWith("Draft.kt") }, "got ${usages.map { it.label }}")

        val outcome = runBlocking { s.rename(layout, text, text.indexOf("header_title"), "page_title") }
        assertTrue(outcome.success, outcome.message)
        assertTrue(Files.readString(layout).contains("@+id/page_title"))
        assertTrue(Files.readString(screen).contains("binding.pageTitle"), Files.readString(screen))
        assertTrue(Files.readString(draft).contains("R.id.page_title"))
    }

    private fun eventually(what: String, timeoutMs: Long = 60_000, check: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (runCatching(check).getOrDefault(false)) return
            Thread.sleep(100)
        }
        throw AssertionError("timed out waiting until $what")
    }

    private fun awaitIndexed(ide: IdeServices, timeoutMs: Long = 180_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (ide.indexStatus.value.message != "Indexed" && System.currentTimeMillis() < deadline) Thread.sleep(50)
    }
}
