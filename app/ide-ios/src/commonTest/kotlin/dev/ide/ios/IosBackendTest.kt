@file:OptIn(ExperimentalForeignApi::class)

package dev.ide.ios

import dev.ide.deps.impl.ArtifactFetcher
import dev.ide.ios.store.IosPreferences
import dev.ide.ui.backend.NodeKind
import dev.ide.ui.backend.TreeViewMode
import dev.ide.ui.backend.UiHighlightModifier
import dev.ide.ui.backend.UiInlayKind
import dev.ide.ui.backend.UiNavKind
import dev.ide.ui.backend.UiSearchOptions
import dev.ide.ui.backend.UiSemanticToken
import dev.ide.ui.backend.UiSeverity
import dev.ide.ui.backend.UiTextEdit
import kotlinx.cinterop.ExperimentalForeignApi
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUserDefaults
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertContentEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [IosBackend] against the real filesystem, on the simulator.
 *
 * The backend is pointed at a temporary directory rather than the Documents container, so a test run can
 * never disturb real projects.
 */
class IosBackendTest {

    private val root = IosFiles.join(NSTemporaryDirectory().trimEnd('/'), "ios-backend-test-${nowSuffix()}")

    // A private defaults suite, for the same reason the projects root is a temporary directory: the backend
    // writes real preferences, and the standard suite belongs to whatever process is running the tests.
    private val suite = "ios-backend-test-${nowSuffix()}"
    private val prefs = IosPreferences(NSUserDefaults(suiteName = suite))
    // Offline by construction: resolution downloads, and a test must not. The fixture jar is written
    // straight into the resolver's cache instead (see `seedStdlib`).
    private val backend = IosBackend(root, prefs).apply {
        dependenciesFor = { IosDependencies(it, ArtifactFetcher { null }) }
    }

    /**
     * The template these tests create with.
     *
     * A real one from the registry, not a host-specific "empty project": that is the point of the model
     * being here. `kotlin-console` is what a new Kotlin project is on every host.
     */
    private val template = "kotlin-console"

    /**
     * Where [template] puts its entry point. The default package is `TemplateArgs`' own, for the tests
     * that name none.
     */
    private fun mainKt(root: String, pkg: String = "com.example.app") =
        IosFiles.join(root, "app/src/main/kotlin/${pkg.replace('.', '/')}/Main.kt")

    @AfterTest
    fun cleanUp() {
        IosFiles.delete(root)
        NSUserDefaults.standardUserDefaults.removePersistentDomainForName(suite)
    }

    // ---- Kotlin outline and folding, which is the first language intelligence this host has ever had ----

    private val kotlinSource = """
        package demo

        import kotlin.math.max

        class Holder {
            fun render(prefix: String): String {
                return prefix
            }
        }
    """.trimIndent()

    @Test
    fun aKotlinFileHasAnOutline() = runTest {
        val outline = backend.fileStructure("/p/Holder.kt", kotlinSource)
        assertEquals(listOf("Holder", "render"), outline.map { it.name })
        assertEquals(listOf("class", "method"), outline.map { it.kind })
        assertEquals(listOf(0, 1), outline.map { it.depth }, "the function is a member of the class")
        assertEquals(
            "render",
            kotlinSource.substring(outline[1].nameOffset, outline[1].nameOffset + 6),
            "the offset must land on the name, since that is where navigation puts the caret",
        )
    }

    @Test
    fun aKotlinFileHasFoldableRegions() = runTest {
        val kinds = backend.codeFolds("/p/Holder.kt", kotlinSource).map { it.kind }.toSet()
        assertTrue("classBody" in kinds, kinds.toString())
        assertTrue("functionBody" in kinds, kinds.toString())
    }

    @Test
    fun halfTypedKotlinStillAnswers() = runTest {
        // What an editor actually holds most of the time. It must not throw and must not go blank.
        val outline = backend.fileStructure("/p/Broken.kt", "class A {\n    fun good() {}\n    fun \n}")
        assertTrue(outline.any { it.name == "A" }, "the class survives: $outline")
        assertTrue(outline.any { it.name == "good" }, "and the complete member does too: $outline")
    }

    @Test
    fun aNonKotlinFileIsLeftAlone() = runTest {
        // The other hosts answer for Java and XML through backends that do not exist here. Returning nothing
        // is honest; guessing would put a Kotlin outline on a Java file.
        assertTrue(backend.fileStructure("/p/Thing.java", "class Thing {}").isEmpty())
        assertTrue(backend.codeFolds("/p/layout.xml", "<a>\n</a>").isEmpty())
    }

    @Test
    fun createProjectScaffoldsAFolderAndOpensIt() = runTest {
        val result = backend.projects.createProject(template, mapOf("name" to "My App", "packageName" to "com.example"))
        assertTrue(result.success, result.message)

        val created = assertNotNull(result.rootPath)
        // "My App" is not a safe folder name; the space is replaced rather than rejected.
        assertEquals("My_App", IosFiles.nameOf(created))

        // The template's layout, not a flat `src/`: this is the same tree the desktop host scaffolds.
        val main = mainKt(created, "com.example")
        assertTrue(IosFiles.exists(main), "expected the entry point at $main")
        assertTrue(backend.files.readFile(main).startsWith("package com.example"))

        // And the MODEL, which is what makes this a project rather than a folder of Kotlin files. Without
        // it the same directory opens on another host as an unrecognised folder.
        assertTrue(IosFiles.exists(IosFiles.join(created, ".platform/workspace.json")))
        assertEquals(listOf("app"), backend.modules.configurableModules().map { it.name })

        // Creating a project also makes it the active one, so the tree is immediately populated.
        assertEquals(created, backend.project.rootPath)
    }

    @Test
    fun theTemplateGalleryIsTheRealRegistry() = runTest {
        val ids = backend.projects.projectTemplates().map { it.id }
        assertEquals(listOf("kotlin-console", "kotlin-library"), ids)
        val console = backend.projects.projectTemplates().first { it.id == "kotlin-console" }
        assertEquals("Kotlin", console.category)
        // The Create-Project form supplies name + package itself; neither template asks for anything more.
        assertTrue(console.parameters.isEmpty(), console.parameters.toString())
    }

    @Test
    fun anUnknownTemplateIsRefusedAndLeavesNothingBehind() = runTest {
        val result = backend.projects.createProject("no.such.template", mapOf("name" to "Ghost"))
        assertFalse(result.success)
        // The directory is made before the template runs, so a failure that left it there would make the
        // name unusable for good — the second attempt would be refused as a duplicate.
        assertFalse(IosFiles.exists(IosFiles.join(root, "Ghost")))
        assertTrue(backend.projects.projects().none { it.name == "Ghost" })
    }

    @Test
    fun theLibraryTemplateScaffoldsItsOwnModuleAndType() = runTest {
        val created = assertNotNull(
            backend.projects.createProject("kotlin-library", mapOf("name" to "Lib", "packageName" to "demo")).rootPath,
        )
        // A library is a `lib` module with a class named after the project, and no entry point.
        assertEquals(listOf("lib"), backend.modules.configurableModules().map { it.name })
        val source = IosFiles.join(created, "lib/src/main/kotlin/demo/Lib.kt")
        assertTrue(IosFiles.exists(source), "expected $source")
        assertFalse(backend.files.readFile(source).contains("fun main("))
    }

    @Test
    fun aSecondProjectWithTheSameNameIsRefused() = runTest {
        assertTrue(backend.projects.createProject(template, mapOf("name" to "Dup")).success)
        val again = backend.projects.createProject(template, mapOf("name" to "Dup"))
        assertTrue(!again.success)
        assertTrue(again.message.contains("already exists"), again.message)
    }

    @Test
    fun anUnnamedProjectIsRefused() = runTest {
        val result = backend.projects.createProject(template, mapOf("name" to "   "))
        assertTrue(!result.success)
    }

    @Test
    fun projectsListsWhatIsOnDisk() = runTest {
        backend.projects.createProject(template, mapOf("name" to "Alpha"))
        backend.projects.createProject(template, mapOf("name" to "Beta"))
        assertEquals(setOf("Alpha", "Beta"), backend.projects.projects().map { it.name }.toSet())
    }

    @Test
    fun fileTreeShowsDirectoriesBeforeFilesAndHidesDotEntries() = runTest {
        val created = assertNotNull(backend.projects.createProject(template, mapOf("name" to "Tree")).rootPath)
        IosFiles.writeText(IosFiles.join(created, "README.md"), "hi")
        IosFiles.writeText(IosFiles.join(created, ".hidden"), "x")

        val tree = backend.files.fileTree(TreeViewMode.Project)
        assertEquals(NodeKind.Workspace, tree.kind)
        // `.platform` is the model's own directory and a dot-entry like any other: hidden, which is also
        // what keeps it out of find-in-files.
        assertEquals(listOf("app", "README.md"), tree.children.map { it.name })
        assertEquals(NodeKind.Folder, tree.children[0].kind)
        assertEquals("markdown", tree.children[1].iconId)

        // The module dir holds the model's own `module.toml` beside the sources, and it is NOT hidden:
        // it is the file a user edits to change the module, so the tree has to show it.
        assertEquals(listOf("src", "module.toml"), tree.children[0].children.map { it.name })

        // app/src/main/kotlin/com/example/app/Main.kt
        var node = tree.children[0]
        for (segment in listOf("src", "main", "kotlin", "com", "example", "app")) {
            node = assertNotNull(node.children.firstOrNull { it.name == segment }, "no $segment under ${node.name}")
        }
        val main = node.children.single()
        assertEquals("Main.kt", main.name)
        assertEquals("kotlin", main.iconId)
        assertEquals(mainKt(created), main.filePath)
    }

    @Test
    fun withNoProjectOpenTheTreeIsEmptyRatherThanBroken() {
        val tree = IosBackend(root, prefs).files.fileTree(TreeViewMode.Project)
        assertEquals(NodeKind.Workspace, tree.kind)
        assertTrue(tree.children.isEmpty())
    }

    @Test
    fun saveFileWritesThroughToDisk() = runTest {
        val created = assertNotNull(backend.projects.createProject(template, mapOf("name" to "Save")).rootPath)
        val main = mainKt(created)
        backend.editor.saveFile(main, "fun main() = Unit\n")
        assertEquals("fun main() = Unit\n", backend.files.readFile(main))
    }

    @Test
    fun createAndDeleteBumpTheFilesystemEpoch() = runTest {
        val created = assertNotNull(backend.projects.createProject(template, mapOf("name" to "Epoch")).rootPath)
        val before = backend.files.fileSystemEpoch.value

        val made = assertNotNull(backend.files.createFile(created, "Extra.kt", "// x"))
        assertTrue(backend.files.fileSystemEpoch.value > before)

        assertTrue(backend.files.deletePath(made))
        assertTrue(!IosFiles.exists(made))
    }

    @Test
    fun createFileRefusesToOverwriteAnExistingOne() = runTest {
        val created = assertNotNull(backend.projects.createProject(template, mapOf("name" to "NoClobber")).rootPath)
        assertNotNull(backend.files.createFile(created, "A.kt", "first"))
        assertNull(backend.files.createFile(created, "A.kt", "second"))
        assertEquals("first", backend.files.readFile(IosFiles.join(created, "A.kt")))
    }

    @Test
    fun createFileSmartMakesIntermediateDirectories() = runTest {
        val created = assertNotNull(backend.projects.createProject(template, mapOf("name" to "Nested")).rootPath)
        val made = assertNotNull(backend.files.createFileSmart(created, "ui/screens/Home.kt"))
        assertTrue(IosFiles.exists(made))
        assertTrue(IosFiles.isDirectory(IosFiles.join(created, "ui/screens")))
    }

    @Test
    fun deletingTheOpenProjectClosesIt() = runTest {
        val created = assertNotNull(backend.projects.createProject(template, mapOf("name" to "Gone")).rootPath)
        assertEquals(created, backend.project.rootPath)
        assertTrue(backend.projects.deleteProject(created))
        assertEquals("", backend.project.rootPath)
    }

    @Test
    fun moduleNameIsTheProjectForPathsInsideItAndNullOutside() = runTest {
        val created = assertNotNull(backend.projects.createProject(template, mapOf("name" to "Mod")).rootPath)
        assertEquals("Mod", backend.files.moduleNameForFile(mainKt(created)))
        assertNull(backend.files.moduleNameForFile("/elsewhere/Other.kt"))
    }

    /**
     * Every picture the UI draws goes through [IosBackend.imageBytes]: a store listing's icon, a
     * screenshot gallery, a publisher's avatar. The shared components decode bytes rather than resolve
     * paths, so a host that does not answer this draws a flat plate everywhere and reports no error at
     * all — which is exactly what the store looked like here before it was implemented.
     */
    @Test
    fun readsTheBytesBehindAnImage() = runTest {
        val backend = IosBackend(projectsRoot = root, preferences = prefs)
        val file = IosFiles.join(root, "icon.png")
        val bytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        IosFiles.writeBytes(file, bytes)

        assertContentEquals(bytes, backend.imageBytes(file))
        assertNull(backend.imageBytes(IosFiles.join(root, "missing.png")), "a file that is not there is null")
    }

    /** Decoding happens in memory on a phone, so a file too large to be a picture is refused outright. */
    @Test
    fun refusesAnImageTooLargeToBeOne() = runTest {
        val backend = IosBackend(projectsRoot = root, preferences = prefs)
        val file = IosFiles.join(root, "huge.png")
        IosFiles.writeBytes(file, ByteArray(9 * 1024 * 1024))

        assertNull(backend.imageBytes(file), "past the ceiling, nothing is decoded")
    }

    // ---- preferences: the flags that remember a launch already happened ----

    /**
     * Inherit [dev.ide.ui.StubBackend]'s no-op preferences and every first-launch sheet reads "never seen"
     * on every cold start, so the picker opens under the onboarding tour each time the app is opened. That
     * is what this host shipped with.
     */
    @Test
    fun aPreferenceSurvivesTheBackendThatWroteIt() {
        assertNull(backend.settings.preference("onboarding.seen"), "nothing is set to begin with")

        backend.settings.setPreference("onboarding.seen", "true")

        assertEquals("true", backend.settings.preference("onboarding.seen"))
        assertEquals(
            "true",
            IosBackend(root, prefs).settings.preference("onboarding.seen"),
            "a later launch is a new backend over the same defaults, and must see the flag",
        )
    }

    /** There is no earlier CodeAssist on iOS, so the build-system migration notice has nothing to warn about. */
    @Test
    fun theMigrationNoticeIsAcknowledgedBeforeItIsEverShown() {
        assertEquals("true", backend.settings.preference("migration.acknowledged"))
    }

    // ---- code completion, which is the first real language intelligence this host has ----------------

    /** A project with a second file in it, opened, so the backend's analysis is bound to that source tree. */
    private suspend fun openTwoFileProject(): String {
        val created = backend.createProject(template, mapOf("name" to "Comp", "packageName" to "demo"))
        val projectRoot = assertNotNull(created.rootPath, created.message)
        IosFiles.writeText(
            IosFiles.join(projectRoot, "app/src/main/kotlin/demo/Greeter.kt"),
            """
            package demo

            class Greeter(val who: String) {
                fun greet(): String = "hi"
                fun times(n: Int): Int = n * 2
                private fun hidden() = 1
            }
            """.trimIndent(),
        )
        assertTrue(backend.openProject(projectRoot))
        return projectRoot
    }

    @Test
    fun completingAfterADotOffersACrossFileTypesMembers() = runTest {
        val projectRoot = openTwoFileProject()
        val text = "package demo\n\nfun use(): String {\n    val g = Greeter(\"w\")\n    return g.\n}\n"
        val offset = text.indexOf("g.") + 2

        val result = backend.complete(IosFiles.join(projectRoot, "src/Use.kt"), text, offset)
        val names = result.items.map { it.label.substringBefore('(') }

        assertTrue("greet" in names, "a member of a type declared in ANOTHER file; got ${names.take(20)}")
        assertTrue("times" in names, "got ${names.take(20)}")
        assertTrue("hidden" !in names, "a private member is not offered from another file; got $names")
    }

    /** The overlay: a declaration typed in one buffer and never saved must still complete in another. */
    @Test
    fun aDeclarationTypedInAnotherTabCompletesBeforeItIsSaved() = runTest {
        val projectRoot = openTwoFileProject()
        backend.updateDocument(
            IosFiles.join(projectRoot, "app/src/main/kotlin/demo/Greeter.kt"),
            """
            package demo

            class Greeter(val who: String) {
                fun greet(): String = "hi"
                fun shout(): String = "HI"
            }
            """.trimIndent(),
        )

        val text = "package demo\n\nfun use(): String {\n    val g = Greeter(\"w\")\n    return g.\n}\n"
        val result = backend.complete(IosFiles.join(projectRoot, "src/Use.kt"), text, text.indexOf("g.") + 2)
        val names = result.items.map { it.label.substringBefore('(') }

        assertTrue("shout" in names, "an UNSAVED declaration in another buffer must complete; got $names")
    }

    /** Only Kotlin, and only inside the open project: anything else answers empty rather than guessing. */
    @Test
    fun completionIsScopedToKotlinFilesOfTheOpenProject() = runTest {
        val projectRoot = openTwoFileProject()
        val java = backend.complete(IosFiles.join(projectRoot, "src/Thing.java"), "class Thing {}", 5)
        assertTrue(java.items.isEmpty(), "this host has no Java backend")

        val noProject = IosBackend(IosFiles.join(root, "empty-${nowSuffix()}"), prefs)
            .apply { dependenciesFor = { IosDependencies(it, ArtifactFetcher { null }) } }
        val loose = noProject.complete("/tmp/Loose.kt", "fun f() { }", 5)
        assertTrue(loose.items.isEmpty(), "with no project open there is no source tree to complete against")
    }


    // ---- navigation, quick doc and the code-action menu ----------------------------------------------

    /**
     * Go to Declaration across files, which is the first navigation this host has ever had.
     *
     * The engine is `KotlinEditorFeatures`, shared with the desktop and Android hosts. It used to be a
     * member of `KotlinSourceAnalyzer`, the adapter onto a build, and that is the only reason an editor
     * with working completion here could not navigate.
     */
    @Test
    fun goToDeclarationLandsOnTheDeclarationInAnotherFile() = runTest {
        val projectRoot = openTwoFileProject()
        val text = "package demo\n\nfun use(): String {\n    val g = Greeter(\"w\")\n    return g.greet()\n}\n"

        val target = assertNotNull(
            backend.editor.definitionAt(IosFiles.join(projectRoot, "src/Use.kt"), text, text.indexOf("Greeter") + 1),
            "Greeter is declared in this project and must be navigable",
        )
        assertEquals(IosFiles.join(projectRoot, "app/src/main/kotlin/demo/Greeter.kt"), target.path)

        // The offset lands on the NAME, which is where navigation puts the caret.
        val declared = backend.files.readFile(target.path)
        assertEquals("Greeter", declared.substring(target.offset, target.offset + 7))
    }

    @Test
    fun theGoToMenuOffersOnlyTheKindsThatResolve() = runTest {
        val projectRoot = openTwoFileProject()
        val text = "package demo\n\nfun use(): String {\n    val g = Greeter(\"w\")\n    return g.greet()\n}\n"

        val options = backend.editor.navigationOptions(
            IosFiles.join(projectRoot, "src/Use.kt"), text, text.indexOf("Greeter") + 1,
        )
        assertTrue(options.any { it.kind == UiNavKind.DECLARATION }, "got ${options.map { it.kind }}")
        assertTrue(options.all { it.targets.isNotEmpty() }, "a listed kind must have somewhere to go")
        // Nothing may point at a `library://` tab: that is a decompiled view this host cannot render.
        assertTrue(options.none { o -> o.targets.any { it.path.startsWith("library://") } })
    }

    /**
     * Quick documentation for the declaration the caret is ON, KDoc included.
     *
     * The other path through `quickDoc` resolves the REFERENCE under the caret, and it is exercised by the
     * same engine's JVM suites; what this asserts is that the pass runs on this host at all and reads the
     * live buffer rather than the file on disk.
     */
    @Test
    fun quickDocDescribesTheDeclarationUnderTheCaret() = runTest {
        val projectRoot = openTwoFileProject()
        val text = """
            package demo

            /** Greets [who]. */
            fun greetAll(who: String): String = who
        """.trimIndent()

        val doc = assertNotNull(
            backend.editor.quickDocAt(IosFiles.join(projectRoot, "src/Use.kt"), text, text.indexOf("greetAll") + 1),
        )
        assertEquals("greetAll", doc.name)
        // The signature renders parameter TYPES, not `name: Type` — what the shared renderer has always
        // produced, and what the structure view shows beside it.
        assertEquals("fun greetAll(String): String", doc.signature)
        assertTrue(doc.doc?.contains("Greets") == true, doc.doc.toString())
    }

    /**
     * The lightbulb on an unresolved reference, and applying it.
     *
     * This is the pairing that was missing rather than either half: the host has reported unresolved
     * references since the index was wired, and offered no way to fix one.
     */
    @Test
    fun anUnresolvedReferenceOffersAnImportFixThatApplies() = runTest {
        val projectRoot = openTwoFileProject()
        // A fix is offered FOR a diagnostic, and the unresolved checks are withheld until there is an index
        // to back them (see `anUnresolvableNameIsNotReportedWhileThereIsNoIndex`). So the classpath comes
        // first: without it this asserts nothing, because there is no error to fix.
        seedStdlib(projectRoot)
        IosFiles.writeText(
            IosFiles.join(projectRoot, "app/src/main/kotlin/demo/util/Formatting.kt"),
            """
            package demo.util

            fun formatValue(value: String): String = value
            """.trimIndent(),
        )
        assertTrue(backend.openProject(projectRoot))

        // A top-level callable in ANOTHER package: unreachable by bare name, and reachable by importing it.
        val path = IosFiles.join(projectRoot, "src/Use.kt")
        val text = "package demo\n\nfun use(): String = formatValue(\"x\")\n"
        val offset = text.indexOf("formatValue") + 1

        val problems = backend.analyze(path, text)
        assertTrue(
            problems.any { "formatValue" in it.message },
            "the fix is offered for this report; got ${problems.map { it.message }}",
        )

        val actions = backend.editor.actionsAt(path, text, offset, offset)
        val importFix = assertNotNull(
            actions.firstOrNull { it.title == "Import demo.util.formatValue" },
            "got ${actions.map { it.title }}",
        )

        val edits = backend.editor.applyAction(path, text, offset, offset, importFix.id)
        assertTrue(edits.others.isEmpty(), "an import edits only the file it was invoked in")
        val applied = edits.focal.fold(text) { acc, e -> acc.substring(0, e.start) + e.newText + acc.substring(e.end) }
        assertTrue(applied.contains("import demo.util.formatValue"), applied)
    }

    /**
     * Expand Selection, one press at a time.
     *
     * The walk is the DOM's, shared with the other hosts; what this proves is that the iOS editor is wired
     * to it at all — it answered nothing here until the walk moved off the JVM host's service layer.
     */
    @Test
    fun expandSelectionGrowsToTheEnclosingSyntax() = runTest {
        val projectRoot = openTwoFileProject()
        val path = IosFiles.join(projectRoot, "src/Use.kt")
        val text = "package demo\n\nfun use(): Int {\n    return 1 + 2\n}\n"
        val caret = text.indexOf("1 + 2")

        val first = assertNotNull(backend.editor.expandSelection(path, text, caret, caret), "a caret expands")
        assertTrue(first.end > first.start, "to a real range: $first")

        // Each press encloses the last, and the walk terminates rather than returning the same range.
        val second = assertNotNull(backend.editor.expandSelection(path, text, first.start, first.end))
        assertTrue(
            second.start <= first.start && second.end >= first.end && second != first,
            "the second press is strictly wider: $first then $second",
        )
    }

    @Test
    fun expandSelectionIsSilentOnAFileThisHostDoesNotAnalyze() = runTest {
        openTwoFileProject()
        assertNull(backend.editor.expandSelection("/p/Thing.java", "class Thing {}", 6, 6))
    }

    @Test
    fun aNonKotlinFileOffersNoActionsAndNoDocumentation() = runTest {
        openTwoFileProject()
        assertTrue(backend.editor.actionsAt("/p/Thing.java", "class Thing {}", 5, 5).isEmpty())
        assertNull(backend.editor.quickDocAt("/p/Thing.java", "class Thing {}", 5))
        assertNull(backend.editor.definitionAt("/p/Thing.java", "class Thing {}", 5))
    }

    // ---- diagnostics ---------------------------------------------------------------------------------

    /** The engine must not cry wolf: correct project-only code reports nothing. */
    @Test
    fun correctCodeReportsNoDiagnostics() = runTest {
        val projectRoot = openTwoFileProject()
        val text = "package demo\n\nfun use(): String {\n    val g = Greeter(\"w\")\n    return g.greet()\n}\n"

        val problems = backend.analyze(IosFiles.join(projectRoot, "src/Use.kt"), text)

        assertTrue(problems.isEmpty(), "clean code must analyze clean; got ${problems.map { it.message }}")
    }

    /**
     * A syntactic diagnostic still reports, which is how we know the pass runs at all.
     *
     * Modifier conflicts need no classpath — they are decided from the declaration itself — so they are not
     * among the checks this host withholds.
     */
    @Test
    fun aSyntacticProblemIsReported() = runTest {
        val projectRoot = openTwoFileProject()
        val text = "package demo\n\nprivate public fun f() {}\n"

        val problems = backend.analyze(IosFiles.join(projectRoot, "src/Use.kt"), text)

        val hit = problems.firstOrNull() ?: error("a conflicting modifier pair must be reported")
        // The range has to land inside the file, or the marker is drawn nowhere useful.
        assertTrue(hit.startOffset in 0..text.length && hit.endOffset in hit.startOffset..text.length, hit.message)
    }

    /**
     * And what this host deliberately does NOT say, until it has an index.
     *
     * Calling a name unresolved requires being able to look a name up, and a jar alone cannot: it resolves a
     * type by FQN and its members, not `println` -> the callables that could be it. Reporting anyway put an
     * error in the app's own starter file. Withholding is the same thing the other hosts do while their
     * index builds — a missing diagnostic, never a wrong one. **Wiring the index is what should make this
     * test change.**
     */
    @Test
    fun anUnresolvableNameIsNotReportedWhileThereIsNoIndex() = runTest {
        val projectRoot = openTwoFileProject()
        val text = "package demo\n\nfun use() {\n    println(\"hi\")\n    thisDoesNotExist()\n}\n"

        val problems = backend.analyze(IosFiles.join(projectRoot, "src/Use.kt"), text)

        assertTrue(
            problems.none { "Unresolved" in it.message },
            "no unresolved-name claims without an index to back them; got ${problems.map { it.message }}",
        )
    }

    // ---- the library classpath -----------------------------------------------------------------------

    /**
     * Seed the resolver's cache with the fixture jar, at exactly the Maven path a real resolve would write
     * it to — so the code under test finds it the same way, and the suite never goes near the network.
     */
    @OptIn(ExperimentalEncodingApi::class)
    private fun seedStdlib(projectRoot: String) {
        val relative = "org/jetbrains/kotlin/kotlin-stdlib/2.4.0/kotlin-stdlib-2.4.0.jar"
        val jar = IosFiles.join(projectRoot, ".platform/caches/resolved-deps/$relative")
        assertTrue(IosFiles.mkdirs(IosFiles.parentOf(jar)!!))
        assertTrue(IosFiles.writeBytes(jar, Base64.decode(StdlibFixture.JAR_BASE64)))
    }

    @Test
    fun aCachedClasspathIsFoundWithNoNetwork() = runTest {
        val projectRoot = openTwoFileProject()
        assertTrue(IosDependencies(projectRoot).cachedJars().isEmpty(), "nothing is cached for a fresh project")

        seedStdlib(projectRoot)
        val cached = IosDependencies(projectRoot).cachedJars()

        assertEquals(1, cached.size, "the seeded jar must be found at its Maven path")
        assertTrue(cached.single().endsWith("kotlin-stdlib-2.4.0.jar"), cached.single())
    }

    /**
     * The difference a classpath makes: a library type's members.
     *
     * `AnnotationTarget` ships ONLY as a `.kotlin_builtins` fragment inside the jar — there is no `.class`
     * file for it anywhere — so its entries coming back is the whole read path working on this device: open
     * the archive, inflate it, decode the metadata protobuf, bind the result into the symbol model.
     */
    @Test
    fun aLibraryTypesMembersCompleteThroughAnImport() = runTest {
        val projectRoot = openTwoFileProject()
        seedStdlib(projectRoot)
        // Reopen: opening is where the analysis is rebuilt over the now-cached jar.
        assertTrue(backend.openProject(projectRoot))

        val text = "package demo\n\nimport kotlin.annotation.AnnotationTarget\n\nval t = AnnotationTarget.\n"
        val names = backend.complete(IosFiles.join(projectRoot, "src/Use.kt"), text, text.length - 1)
            .items.map { it.label.substringBefore('(') }

        assertTrue("CLASS" in names, "a builtin enum's entries come from the jar; got ${names.take(20)}")
        assertTrue("FUNCTION" in names, "got ${names.take(20)}")
    }

    /** The same type by its fully-qualified name, which needs no import at all. */
    @Test
    fun aLibraryTypeCompletesByItsFullyQualifiedName() = runTest {
        val projectRoot = openTwoFileProject()
        seedStdlib(projectRoot)
        assertTrue(backend.openProject(projectRoot))

        val text = "package demo\n\nval t = kotlin.annotation.AnnotationTarget.\n"
        val names = backend.complete(IosFiles.join(projectRoot, "src/Use.kt"), text, text.length - 1)
            .items.map { it.label.substringBefore('(') }

        assertTrue("PROPERTY" in names, "got ${names.take(20)}")
    }

    /** With no classpath there is nothing to resolve a library type against, and that is not an error. */
    @Test
    fun aProjectWithNoClasspathStillCompletesItsOwnCode() = runTest {
        val projectRoot = openTwoFileProject()
        assertTrue(IosDependencies(projectRoot).cachedJars().isEmpty(), "nothing was seeded")

        val text = "package demo\n\nfun use(): String {\n    val g = Greeter(\"w\")\n    return g.\n}\n"
        val names = backend.complete(IosFiles.join(projectRoot, "src/Use.kt"), text, text.indexOf("g.") + 2)
            .items.map { it.label.substringBefore('(') }

        assertTrue("greet" in names, "project code resolves with or without a classpath; got ${names.take(20)}")
    }


    /** The file the Empty-project template writes, analyzed exactly as a first run would. */
    @Test
    fun theStarterFileAnalyzesCleanly() = runTest {
        val created = backend.createProject(template, mapOf("name" to "First", "packageName" to "demo"))
        val projectRoot = assertNotNull(created.rootPath, created.message)
        val main = mainKt(projectRoot, "demo")
        val text = IosFiles.readText(main)

        val problems = backend.analyze(main, text)

        assertTrue(
            problems.isEmpty(),
            "the project this host CREATES must not open with errors in it; got ${problems.map { it.message }}",
        )
    }


    // ---- the index: library code becoming DISCOVERABLE rather than merely resolvable -------------------

    /**
     * `println` completing is the whole point of the index.
     *
     * It is a top-level callable in `kotlin/io/ConsoleKt` whose signature exists only in that class's
     * `@kotlin.Metadata`. Answering it means a name was looked UP — the question a jar cannot answer and an
     * inverted index can, and the reason this host offered nothing from a library before.
     */
    @Test
    fun aStdlibCallableCompletesByName() = runTest {
        val projectRoot = openTwoFileProject()
        seedStdlib(projectRoot)
        assertTrue(backend.openProject(projectRoot))

        val text = "package demo\n\nfun use() {\n    printl\n}\n"
        val names = backend.complete(IosFiles.join(projectRoot, "src/Use.kt"), text, text.indexOf("printl") + 6)
            .items.map { it.label.substringBefore('(') }

        assertTrue("println" in names, "a stdlib callable must complete by name; got ${names.take(20)}")
    }

    /** And with the index there, an unresolved name is reported again — the check it had to withhold. */
    @Test
    fun anUnresolvedNameIsReportedOnceTheIndexExists() = runTest {
        val projectRoot = openTwoFileProject()
        seedStdlib(projectRoot)
        assertTrue(backend.openProject(projectRoot))

        val text = "package demo\n\nfun use() {\n    println(\"hi\")\n    thisDoesNotExist()\n}\n"
        val problems = backend.analyze(IosFiles.join(projectRoot, "src/Use.kt"), text)

        assertTrue(
            problems.none { "println" in it.message },
            "a stdlib call resolves through the index; got ${problems.map { it.message }}",
        )
        assertTrue(
            problems.any { "thisDoesNotExist" in it.message },
            "and a name that really is missing is reported; got ${problems.map { it.message }}",
        )
    }

    /** The segment is written once and reopened: a second open must not rebuild it. */
    @Test
    fun theIndexIsCachedOnDiskAcrossOpens() = runTest {
        val projectRoot = openTwoFileProject()
        seedStdlib(projectRoot)
        assertTrue(backend.openProject(projectRoot))
        backend.complete(IosFiles.join(projectRoot, "src/Use.kt"), "package demo\n", 13)

        val cacheDir = IosFiles.join(projectRoot, ".platform/caches/index")
        val written = IosFiles.list(cacheDir).filter { it.endsWith(".seg") }
        assertTrue(written.isNotEmpty(), "the index must persist; found ${IosFiles.list(cacheDir)}")
        assertTrue(
            written.any { it.startsWith("kotlin.callables-v") },
            "keyed by extension and version; got $written",
        )
    }

    // ---- dependencies: the Dependencies screen, and what it puts on the classpath --------------------

    /**
     * A fixture Maven repository, served at the REAL repository URLs this host resolves from.
     *
     * Serving Maven Central's own base rather than an invented one keeps [IosDependencies.REPOSITORIES]
     * under test: a typo in a repository URL would make every one of these miss, which is exactly the
     * failure a fixture at `https://fixture.invalid` would hide.
     */
    private class FixtureMaven : ArtifactFetcher {
        private val byUrl = HashMap<String, ByteArray>()

        /**
         * Whether there is a network at all.
         *
         * False makes [fetch] THROW, which is what a real fetcher does when the socket fails — and is the
         * distinction that matters here: a 404 returns null and is evidence the artifact does not exist (and
         * is negative-cached for a week), while a dead socket is evidence of nothing and must not be.
         */
        var reachable: Boolean = true

        override fun fetch(url: String): ByteArray? {
            if (!reachable) throw IllegalStateException("The Internet connection appears to be offline")
            return byUrl[url]
        }

        /** Publish `group:name:version`, optionally with the given jar bytes and transitive dependencies. */
        fun publish(
            group: String,
            name: String,
            version: String,
            jar: ByteArray = "not-really-a-jar".encodeToByteArray(),
            deps: List<Triple<String, String, String>> = emptyList(),
        ) {
            val rel = "${group.replace('.', '/')}/$name/$version/$name-$version"
            byUrl["$CENTRAL/$rel.pom"] = buildString {
                append("<?xml version=\"1.0\"?>\n<project>\n")
                append("<groupId>$group</groupId><artifactId>$name</artifactId><version>$version</version>\n")
                append("<packaging>jar</packaging>\n")
                if (deps.isNotEmpty()) {
                    append("<dependencies>\n")
                    for ((g, a, v) in deps) {
                        append("<dependency><groupId>$g</groupId><artifactId>$a</artifactId>")
                        append("<version>$v</version></dependency>\n")
                    }
                    append("</dependencies>\n")
                }
                append("</project>\n")
            }.encodeToByteArray()
            byUrl["$CENTRAL/$rel.jar"] = jar
        }

        /** The real 2.6KB stdlib fixture, published where a real resolve would look for it. */
        @OptIn(ExperimentalEncodingApi::class)
        fun publishStdlib() = publish(
            "org.jetbrains.kotlin", "kotlin-stdlib", "2.4.0", Base64.decode(StdlibFixture.JAR_BASE64),
        )

        private companion object {
            const val CENTRAL = "https://repo1.maven.org/maven2"
        }
    }

    /** A backend resolving against [repo] instead of the network. */
    private fun backendOver(repo: ArtifactFetcher) =
        IosBackend(root, prefs).apply { dependenciesFor = { IosDependencies(it, repo) } }

    @Test
    fun theOpenProjectIsTheOneModuleTheDependencyScreenCanEdit() = runTest {
        assertEquals(emptyList(), backend.modules.configurableModules(), "no project is open yet")

        openTwoFileProject()

        val modules = backend.modules.configurableModules()
        assertEquals(listOf("app"), modules.map { it.name }, "the model's module, as the tree shows it")
        assertEquals(listOf("app"), backend.deps.dependencyModules().map { it.name }, "and the two agree")
        assertEquals(0, backend.deps.dependencyModules().single().dependencyCount, "nothing declared yet")
    }

    /**
     * A fresh project declares nothing, and the standard library is still on the resolved classpath.
     *
     * The distinction is the whole design: the stdlib is the host's, so it is never a row with a remove
     * button that would have to refuse.
     */
    @Test
    fun aFreshProjectDeclaresNothingYetResolvesTheStandardLibrary() = runTest {
        val projectRoot = openTwoFileProject()
        seedStdlib(projectRoot)

        val deps = assertNotNull(backend.deps.moduleDependencies("Comp"))

        assertEquals(emptyList(), deps.declared.map { it.coordinate }, "the user declared nothing")
        assertTrue(
            deps.nodes.any { it.name == "kotlin-stdlib" && it.version == "2.4.0" },
            "and the stdlib is on the classpath regardless; got ${deps.nodes.map { it.coordinate }}",
        )
        assertTrue(
            deps.nodes.none { it.name == "kotlin-stdlib" && it.declared },
            "but it is not presented as the user's declaration",
        )
    }

    @Test
    fun addingADependencyResolvesItAndWritesItToTheCache() = runTest {
        val repo = FixtureMaven()
        repo.publishStdlib()
        repo.publish("com.example", "widget", "1.2.0", deps = listOf(Triple("com.example", "core", "0.9")))
        repo.publish("com.example", "core", "0.9")
        val backend = backendOver(repo)
        val projectRoot = assertNotNull(
            backend.createProject(template, mapOf("name" to "Dep", "packageName" to "demo")).rootPath,
        )

        val added = backend.deps.addDependency("Dep", "com.example:widget:1.2.0", "implementation")

        assertTrue(added.success, added.message)
        val deps = assertNotNull(backend.deps.moduleDependencies("Dep"))
        assertEquals(listOf("com.example:widget:1.2.0"), deps.declared.map { it.coordinate })
        assertEquals("implementation", deps.declared.single().scope)
        assertTrue(
            deps.nodes.any { it.coordinate == "com.example:core:0.9" && !it.declared },
            "the transitive comes with it; got ${deps.nodes.map { it.coordinate }}",
        )
        assertContentEquals(
            listOf("com.example:core:0.9"),
            deps.nodes.single { it.coordinate == "com.example:widget:1.2.0" }.children,
            "the root carries the edge the tree view expands through",
        )
        assertTrue(
            IosFiles.exists(
                IosFiles.join(
                    projectRoot,
                    ".platform/caches/resolved-deps/com/example/widget/1.2.0/widget-1.2.0.jar",
                ),
            ),
            "and the artifact is on disk in Maven layout",
        )
    }

    /** Declarations outlive the process: they are a file in the project, not session state. */
    @Test
    fun aDeclarationSurvivesAReopen() = runTest {
        val repo = FixtureMaven()
        repo.publishStdlib()
        repo.publish("com.example", "widget", "1.2.0")
        val first = backendOver(repo)
        val projectRoot = assertNotNull(
            first.createProject(template, mapOf("name" to "Keep", "packageName" to "demo")).rootPath,
        )
        assertTrue(first.deps.addDependency("Keep", "com.example:widget:1.2.0", "api").success)

        val second = backendOver(repo)
        assertTrue(second.openProject(projectRoot))

        val deps = assertNotNull(second.deps.moduleDependencies("Keep"))
        assertEquals(listOf("com.example:widget:1.2.0"), deps.declared.map { it.coordinate })
        assertEquals("api", deps.declared.single().scope, "the scope is persisted too")
    }

    @Test
    fun removingADependencyUndeclaresIt() = runTest {
        val repo = FixtureMaven()
        repo.publishStdlib()
        repo.publish("com.example", "widget", "1.2.0")
        val backend = backendOver(repo)
        assertNotNull(backend.createProject(template, mapOf("name" to "Drop", "packageName" to "demo")).rootPath)
        assertTrue(backend.deps.addDependency("Drop", "com.example:widget:1.2.0", "implementation").success)

        assertTrue(backend.deps.removeDependency("Drop", "com.example:widget:1.2.0"))

        assertEquals(emptyList(), assertNotNull(backend.deps.moduleDependencies("Drop")).declared)
        assertFalse(
            backend.deps.removeDependency("Drop", "com.example:widget:1.2.0"),
            "removing what is not declared reports so rather than pretending",
        )
    }

    @Test
    fun theSameArtifactIsNotDeclaredTwice() = runTest {
        val repo = FixtureMaven()
        repo.publishStdlib()
        repo.publish("com.example", "widget", "1.2.0")
        repo.publish("com.example", "widget", "2.0.0")
        val backend = backendOver(repo)
        assertNotNull(backend.createProject(template, mapOf("name" to "Once", "packageName" to "demo")).rootPath)
        assertTrue(backend.deps.addDependency("Once", "com.example:widget:1.2.0", "implementation").success)

        val again = backend.deps.addDependency("Once", "com.example:widget:2.0.0", "implementation")

        assertFalse(again.success, "a second version of one artifact is a change, not an addition")
        assertTrue("already a dependency" in again.message, again.message)
    }

    /** The version picker's path: declare at one version, edit to another. */
    @Test
    fun updatingADependencyChangesItsVersion() = runTest {
        val repo = FixtureMaven()
        repo.publishStdlib()
        repo.publish("com.example", "widget", "1.2.0")
        repo.publish("com.example", "widget", "2.0.0")
        val backend = backendOver(repo)
        assertNotNull(backend.createProject(template, mapOf("name" to "Bump", "packageName" to "demo")).rootPath)
        assertTrue(backend.deps.addDependency("Bump", "com.example:widget:1.2.0", "implementation").success)

        val updated = backend.deps.updateDependency("Bump", "com.example:widget:1.2.0", "2.0.0", "api", emptyList())

        assertTrue(updated.success, updated.message)
        val declared = assertNotNull(backend.deps.moduleDependencies("Bump")).declared.single()
        assertEquals("com.example:widget:2.0.0", declared.coordinate)
        assertEquals("api", declared.scope)
    }

    /**
     * Offline, a declaration still lands and is reported unresolved.
     *
     * The alternative — refusing the add because the download failed — loses the user's intent the moment
     * they walk into a lift, and leaves nothing for the retry button to act on.
     */
    @Test
    fun aDependencyThatCannotBeDownloadedIsStillDeclared() = runTest {
        val backend = backendOver(ArtifactFetcher { null })
        assertNotNull(backend.createProject(template, mapOf("name" to "Off", "packageName" to "demo")).rootPath)

        val added = backend.deps.addDependency("Off", "com.example:widget:1.2.0", "implementation")

        assertTrue(added.success, "the declaration is the part that succeeded")
        val deps = assertNotNull(backend.deps.moduleDependencies("Off"))
        assertEquals(listOf("com.example:widget:1.2.0"), deps.declared.map { it.coordinate })
        assertTrue("com.example:widget:1.2.0" in deps.unresolved, "and it is reported unresolved: ${deps.unresolved}")
        assertTrue(deps.declared.single().declared, "it is still a declared root")
    }

    @Test
    fun aCoordinateThatIsNotACoordinateIsRefusedWithAReason() = runTest {
        val backend = backendOver(ArtifactFetcher { null })
        assertNotNull(backend.createProject(template, mapOf("name" to "Bad", "packageName" to "demo")).rootPath)

        val nonsense = backend.deps.addDependency("Bad", "not a coordinate", "implementation")
        assertFalse(nonsense.success)
        assertTrue("coordinate" in nonsense.message, nonsense.message)

        val versionless = backend.deps.addDependency("Bad", "com.example:widget", "implementation")
        assertFalse(versionless.success)
        assertTrue("needs a version" in versionless.message, versionless.message)
    }

    /** Both repositories are offered and neither can be removed; nothing here persists a custom one. */
    @Test
    fun theBuiltinRepositoriesAreReported() {
        val repos = backend.deps.repositories()
        assertEquals(listOf("Maven Central", "Google"), repos.map { it.name })
        assertTrue(repos.all { it.builtin })
        assertTrue(repos.any { it.url == "https://repo1.maven.org/maven2" }, repos.toString())
    }

    /** Deleting a cached version reclaims the disk it was using. */
    @Test
    fun aCachedVersionCanBeListedAndDeleted() = runTest {
        val projectRoot = openTwoFileProject()
        seedStdlib(projectRoot)

        val cached = backend.deps.cachedVersions("org.jetbrains.kotlin", "kotlin-stdlib")
        assertEquals(listOf("2.4.0"), cached.map { it.version })
        assertTrue(cached.single().bytes > 0, "the size on disk is real")

        assertTrue(backend.deps.deleteCachedVersion("org.jetbrains.kotlin", "kotlin-stdlib", "2.4.0"))
        assertEquals(emptyList(), backend.deps.cachedVersions("org.jetbrains.kotlin", "kotlin-stdlib"))
        assertEquals(emptyList(), IosDependencies(projectRoot).cachedJars(), "and it leaves the classpath")
    }

    /** With nothing reachable, the picker is told the index was unavailable rather than "no results". */
    @Test
    fun anUnreachableSearchIsNotReportedAsNoResults() = runTest {
        val backend = backendOver(ArtifactFetcher { null })
        assertNotNull(backend.createProject(template, mapOf("name" to "Find", "packageName" to "demo")).rootPath)

        val found = backend.deps.artifactSearch("widget", "Find")

        assertEquals(emptyList(), found.hits)
        assertTrue(found.indexUnavailable, "an unreachable index is not the same answer as an empty one")
    }

    /**
     * The whole chain, with nothing seeded: declared -> resolved -> downloaded -> indexed -> the editor.
     *
     * This is the one that says the Dependencies screen and the Kotlin editor are the same system. Opening
     * the project resolves the standard library over the fixture repository, and `println` — a top-level
     * callable that exists only inside that jar's `@kotlin.Metadata` — resolves in the editor with no jar
     * ever having been placed on disk by the test.
     */
    @Test
    fun aResolvedClasspathReachesTheEditorWithNothingSeeded() = runTest {
        val repo = FixtureMaven()
        repo.publishStdlib()
        val backend = backendOver(repo)
        val projectRoot = assertNotNull(
            backend.createProject(template, mapOf("name" to "Live", "packageName" to "demo")).rootPath,
        )

        val text = "package demo\n\nfun use() {\n    println(\"hi\")\n    thisDoesNotExist()\n}\n"
        val problems = backend.analyze(IosFiles.join(projectRoot, "src/Use.kt"), text)

        assertTrue(
            problems.none { "println" in it.message },
            "the downloaded stdlib resolves it; got ${problems.map { it.message }}",
        )
        assertTrue(
            problems.any { "thisDoesNotExist" in it.message },
            "and the checks are genuinely running; got ${problems.map { it.message }}",
        )
    }

    /**
     * Adding a library rebuilds the analysis, so the editor sees it without the project being reopened.
     *
     * The project is created with no signal — an unreachable [FixtureMaven] THROWS rather than answering,
     * which is what a real fetcher does when the socket fails, and is why the failure is not mistaken for
     * "this artifact does not exist" and negative-cached for a week.
     */
    @Test
    fun addingADependencyRebuildsTheEditorsClasspath() = runTest {
        val repo = FixtureMaven().apply { reachable = false }
        val backend = backendOver(repo)
        val projectRoot = assertNotNull(
            backend.createProject(template, mapOf("name" to "Grow", "packageName" to "demo")).rootPath,
        )
        val file = IosFiles.join(projectRoot, "src/Use.kt")
        val text = "package demo\n\nfun use() {\n    println(\"hi\")\n}\n"

        // Nothing was reachable when the project opened, so there is no classpath and no index: the checks
        // withhold judgement rather than calling every library name unresolved.
        assertEquals(emptyList(), IosDependencies(projectRoot).cachedJars())
        assertTrue(backend.analyze(file, text).none { "println" in it.message })

        // The signal comes back, and a dependency is added through the screen.
        repo.publishStdlib()
        repo.publish("com.example", "widget", "1.2.0")
        repo.reachable = true
        assertTrue(backend.deps.addDependency("Grow", "com.example:widget:1.2.0", "implementation").success)

        assertTrue(
            IosDependencies(projectRoot).cachedJars().any { it.endsWith("kotlin-stdlib-2.4.0.jar") },
            "the add resolved the whole set, not just the new coordinate",
        )
        val completed = backend.complete(file, text, text.indexOf("println") + 5)
        assertTrue(
            completed.items.any { it.label.startsWith("println") },
            "and the editor offers it immediately; got ${completed.items.take(10).map { it.label }}",
        )
    }

    /**
     * Retry re-probes what was recorded absent.
     *
     * A clean 404 is remembered for a week so that every open does not re-ask for the `-sources.jar` no
     * library publishes. That cache is also the one thing standing between a user and the artifact they are
     * pressing Retry for, so Retry has to clear it — without that this test resolves nothing the second time
     * and the button is decoration.
     */
    @Test
    fun retryReProbesAnArtifactThatWasAbsentAndHasSinceBeenPublished() = runTest {
        val repo = FixtureMaven()
        val backend = backendOver(repo)
        val projectRoot = assertNotNull(
            backend.createProject(template, mapOf("name" to "Retry", "packageName" to "demo")).rootPath,
        )
        // The repository genuinely did not carry it: a 404, recorded as a miss.
        assertEquals(emptyList(), IosDependencies(projectRoot).cachedJars())

        repo.publishStdlib()
        backend.deps.retryDependencyResolution()

        assertTrue(
            IosDependencies(projectRoot).cachedJars().any { it.endsWith("kotlin-stdlib-2.4.0.jar") },
            "Retry must forget the miss, or it re-reads the negative cache and resolves nothing",
        )
    }

    // ---- the editor passes beside completion: coloring, hints, parameter info, formatting -------------

    /** Apply [edits] the way the editor does: back to front, so an earlier edit's offsets stay valid. */
    private fun applyEdits(text: String, edits: List<UiTextEdit>): String =
        edits.sortedByDescending { it.start }
            .fold(text) { acc, e -> acc.substring(0, e.start) + e.newText + acc.substring(e.end) }

    /** Only Kotlin, for the same reason the outline is: there is no backend here to ask about a `.java` file. */
    @Test
    fun theEditorPassesAnswerForKotlinOnly() = runTest {
        val projectRoot = openTwoFileProject()
        val java = IosFiles.join(projectRoot, "src/Thing.java")
        val text = "class Thing { int x = 1; }"

        assertEquals(emptyList(), backend.hintsAt(java, text, 0, text.length))
        assertEquals(emptyList(), backend.semanticTokens(java, text))
        assertNull(backend.signatureHelp(java, text, text.length))
        assertEquals(emptyList(), backend.formatDocument(java, text))
        assertEquals(emptyList(), backend.optimizeImports(java, text))
    }

    /**
     * An inferred type is annotated, and only inside the window the editor asked for.
     *
     * The window matters more here than on a desktop: inference per `val` is what this pass costs, and a
     * phone shows a small fraction of a file. A hint for a declaration outside the requested range would mean
     * the pass had walked the whole file to produce something nothing would draw.
     */
    @Test
    fun anInferredTypeIsHintedAndOnlyInsideTheWindow() = runTest {
        val projectRoot = openTwoFileProject()
        val file = IosFiles.join(projectRoot, "src/Use.kt")
        val text = "package demo\n\nfun use() {\n    val g = Greeter(\"w\")\n}\n"
        val declaration = text.indexOf("val g")

        val hints = backend.hintsAt(file, text, 0, text.length)

        val hint = assertNotNull(
            hints.firstOrNull { it.kind == UiInlayKind.Type },
            "the type of `g` is inferred, so it is what a reader cannot see; got $hints",
        )
        assertTrue("Greeter" in hint.text, "the hint names the inferred type; got ${hint.text}")
        assertTrue(hint.offset > declaration, "it is anchored after the name it annotates")

        assertEquals(
            emptyList(),
            backend.hintsAt(file, text, 0, declaration),
            "a window that stops before the declaration must not carry its hint",
        )
    }

    /**
     * Coloring says what an identifier IS, which is the part the lexer can only guess at.
     *
     * This pass was the last one missing on this host, and for a compiler reason rather than a portability
     * one: referencing `KotlinSemanticHighlighter` at all overflowed Kotlin/Native 2.4.0's
     * `CastsOptimization` at link time. Kotlin 2.4.20 fixes that pass and the class links untouched.
     *
     * What it buys over the lexical layer is exactly what shape cannot tell you. `Greeter("w")` looks like a
     * type followed by a paren; it is a CONSTRUCTOR call, and knowing that at all means having resolved a
     * class declared in another file. `greet` is a METHOD though it sits where any call sits. And `g` is the
     * same five-pixel word in both lines, but the first is its DECLARATION and the second is not.
     *
     * `println` is deliberately not asserted: there is no library classpath in this fixture, so it does not
     * resolve and is simply omitted -- which is the contract, since an omitted range keeps the lexer's color.
     */
    @Test
    fun coloringTellsAConstructorFromATypeAndADeclarationFromAUse() = runTest {
        val projectRoot = openTwoFileProject()
        val file = IosFiles.join(projectRoot, "src/Color.kt")
        val text = "package demo\n\nfun use() {\n    val g = Greeter(\"w\")\n    println(g.greet())\n}\n"

        val tokens = backend.semanticTokens(file, text)

        fun tokenAt(name: String, from: Int = 0): UiSemanticToken? {
            val at = text.indexOf(name, from)
            return tokens.firstOrNull { it.startOffset == at && it.endOffset == at + name.length }
        }
        assertEquals("constructor", tokenAt("Greeter")?.kind, "resolved across files; got $tokens")
        assertEquals("method", tokenAt("greet")?.kind, "a member of Greeter, not a standalone function")

        val declaration = assertNotNull(tokenAt("g", text.indexOf("val g") + 4), "the local's declaration")
        assertEquals("localVariable", declaration.kind)
        assertTrue(UiHighlightModifier.Declaration in declaration.modifiers, "got ${declaration.modifiers}")

        val use = assertNotNull(tokenAt("g", text.indexOf("println")), "the local's use")
        assertEquals("localVariable", use.kind)
        assertTrue(
            UiHighlightModifier.Declaration !in use.modifiers,
            "the same word, and the second one defines nothing; got ${use.modifiers}",
        )
    }

    /** Parameter info for the call the caret is inside, resolved across files like the coloring is. */
    @Test
    fun parameterInfoNamesTheParameterTheCaretIsIn() = runTest {
        val projectRoot = openTwoFileProject()
        val text = "package demo\n\nfun use(g: Greeter): Int {\n    return g.times()\n}\n"
        val insideTheCall = text.indexOf("times(") + "times(".length

        val help = assertNotNull(
            backend.signatureHelp(IosFiles.join(projectRoot, "src/Use.kt"), text, insideTheCall),
            "the caret is inside a call whose callee resolves",
        )

        val signature = help.signatures[help.activeSignature]
        assertTrue("times" in signature.label, "the label is the signature as shown; got ${signature.label}")
        assertEquals(
            listOf("n"),
            signature.parameters.map { it.label.substringBefore(':').trim() },
            "the real parameter name, which is the point of the panel; got ${signature.parameters}",
        )
        assertEquals(0, help.activeParameter, "the caret is on the first argument")
    }

    /** Outside a call there is nothing to show, and saying so is what dismisses the panel. */
    @Test
    fun parameterInfoIsNullWhereThereIsNoCall() = runTest {
        val projectRoot = openTwoFileProject()
        val text = "package demo\n\nval x = 1\n"

        assertNull(backend.signatureHelp(IosFiles.join(projectRoot, "src/Use.kt"), text, text.indexOf("1")))
    }

    /**
     * Reformatting re-indents to the Kotlin official 4, and an already-formatted buffer produces NO edit.
     *
     * Idempotence is the property worth asserting: a formatter that always returns an edit makes every
     * reformat dirty the file and push an undo step, which on a host that autosaves is a write per press.
     */
    @Test
    fun reformattingReindentsAndThenChangesNothing() = runTest {
        val projectRoot = openTwoFileProject()
        val file = IosFiles.join(projectRoot, "src/Use.kt")
        val ragged = "package demo\n\nfun use(): Int {\nval x = 1\n        return x\n}\n"

        val formatted = applyEdits(ragged, backend.formatDocument(file, ragged))

        assertTrue("\n    val x = 1\n" in formatted, "re-indented to four spaces; got:\n$formatted")
        assertTrue("\n    return x\n" in formatted, "and the over-indented line came back too; got:\n$formatted")
        assertEquals(
            emptyList(),
            backend.formatDocument(file, formatted),
            "the second pass has nothing to do, or every reformat dirties the buffer",
        )
    }

    /** A range reformat touches the selection and nothing else. */
    @Test
    fun formattingARangeLeavesTheRestOfTheBufferAlone() = runTest {
        val projectRoot = openTwoFileProject()
        val file = IosFiles.join(projectRoot, "src/Use.kt")
        val ragged = "package demo\n\nfun a(): Int {\nval x = 1\n    return x\n}\n\nfun b(): Int {\nval y = 2\n    return y\n}\n"
        val secondFunction = ragged.indexOf("fun b")

        val formatted = applyEdits(
            ragged,
            backend.formatRange(file, ragged, secondFunction, ragged.length),
        )

        assertTrue("\n    val y = 2\n" in formatted, "the selected function was re-indented; got:\n$formatted")
        assertTrue("\nval x = 1\n" in formatted, "the unselected one was left exactly as written; got:\n$formatted")
    }

    /** "Optimize Imports": sorted, de-duplicated, and the unused one dropped. */
    @Test
    fun optimizeImportsSortsAndDropsWhatIsUnused() = runTest {
        val projectRoot = openTwoFileProject()
        val file = IosFiles.join(projectRoot, "src/Use.kt")
        val text = "package demo\n\nimport kotlin.math.min\nimport kotlin.math.max\nimport kotlin.math.max\n\n" +
            "fun use(a: Int, b: Int): Int = max(a, b)\n"

        val organized = applyEdits(text, backend.optimizeImports(file, text))

        assertEquals(
            listOf("import kotlin.math.max"),
            organized.lines().filter { it.startsWith("import ") },
            "one import, kept because it is used, and the duplicate and the unused one are gone",
        )
        assertEquals(
            emptyList(),
            backend.optimizeImports(file, organized),
            "and running it again changes nothing",
        )
    }


    // ---- search: the Search screen answered for the first time on this host ---------------------------

    /** A project with something to find in it: two sources, a resource, and a binary that must be skipped. */
    private suspend fun openSearchableProject(): String {
        val created = backend.createProject(template, mapOf("name" to "Find", "packageName" to "demo"))
        val projectRoot = assertNotNull(created.rootPath, created.message)
        IosFiles.writeText(
            IosFiles.join(projectRoot, "app/src/main/kotlin/demo/Greeter.kt"),
            "package demo\n\nclass Greeter {\n    fun greet() = \"hello\"\n}\n",
        )
        IosFiles.writeText(IosFiles.join(projectRoot, "notes.md"), "hello from a resource\n")
        // A file the walk must not open: the extension says binary, and its bytes say so too.
        IosFiles.writeBytes(IosFiles.join(projectRoot, "icon.png"), byteArrayOf(0, 104, 101, 108, 108, 111))
        assertTrue(backend.openProject(projectRoot))
        return projectRoot
    }

    @Test
    fun findInFilesReportsTheLineAndAnOffsetTheEditorCanNavigateTo() = runTest {
        val projectRoot = openSearchableProject()

        val matches = backend.search.findInFiles("hello")

        val hit = assertNotNull(
            matches.firstOrNull { it.fileName == "Greeter.kt" },
            "the source file matches; got ${matches.map { it.fileName }}",
        )
        assertEquals(4, hit.line, "1-based, counting from the top of the file")
        assertEquals(hit.lineText.indexOf("hello") + 1, hit.col, "1-based column into the line shown")
        assertEquals("hello", hit.lineText.substring(hit.matchStart, hit.matchEnd))
        val fileText = IosFiles.readText(IosFiles.join(projectRoot, "app/src/main/kotlin/demo/Greeter.kt"))
        assertEquals(
            "hello",
            fileText.substring(hit.offset, hit.offset + 5),
            "the absolute offset is what the editor opens at, so it must land on the match",
        )
        assertTrue(matches.any { it.fileName == "notes.md" }, "a resource is searched too")
    }

    /** A binary file is skipped by name, and would be skipped by content even if the name lied. */
    @Test
    fun findInFilesDoesNotOpenBinaries() = runTest {
        openSearchableProject()
        assertTrue(backend.search.findInFiles("hello").none { it.fileName == "icon.png" })
    }

    /**
     * The one file the user is looking at must be searched as it READS.
     *
     * Searching it as last written is the failure worth guarding: the results would be wrong about exactly
     * the buffer being edited, and right about every other one.
     */
    @Test
    fun findInFilesSearchesTheUnsavedBuffer() = runTest {
        val projectRoot = openSearchableProject()
        val file = IosFiles.join(projectRoot, "app/src/main/kotlin/demo/Greeter.kt")
        backend.editor.updateDocument(file, "package demo\n\nclass Greeter {\n    fun greet() = \"goodbye\"\n}\n")

        assertTrue(
            backend.search.findInFiles("goodbye").any { it.filePath == file },
            "the word exists only in the buffer, never on disk",
        )
        assertTrue(
            backend.search.findInFiles("hello").none { it.filePath == file },
            "and the word it replaced is gone, rather than being found in the stale file",
        )
    }

    /** This host's own caches sit in `.platform`, and searching them would bury the project's own hits. */
    @Test
    fun findInFilesSkipsThePlatformDirectory() = runTest {
        val projectRoot = openSearchableProject()
        val cached = IosFiles.join(projectRoot, ".platform/caches/note.txt")
        assertTrue(IosFiles.mkdirs(IosFiles.parentOf(cached)!!))
        IosFiles.writeText(cached, "hello from a cache\n")

        assertTrue(backend.search.findInFiles("hello").none { "/.platform/" in it.filePath })
    }

    @Test
    fun findInFilesHonoursTheOptionsAndTheLimit() = runTest {
        val projectRoot = openSearchableProject()
        IosFiles.writeText(IosFiles.join(projectRoot, "src/Case.kt"), "val a = \"Hello\"\nval b = \"hello\"\n")

        assertEquals(
            1,
            backend.search.findInFiles("Hello", UiSearchOptions(caseSensitive = true))
                .count { it.fileName == "Case.kt" },
            "case-sensitive matches one of the two",
        )
        assertEquals(
            2,
            backend.search.findInFiles("Hello").count { it.fileName == "Case.kt" },
            "and the default, case-insensitive, matches both",
        )
        assertTrue(
            backend.search.findInFiles("gree", UiSearchOptions(wholeWord = true)).isEmpty(),
            "whole-word does not match inside `greet`",
        )
        assertTrue(
            backend.search.findInFiles("h.llo", UiSearchOptions(regex = true)).isNotEmpty(),
            "a regex query is a regex",
        )
        assertTrue(
            backend.search.findInFiles("h.llo").isEmpty(),
            "and a literal query is not: the dot is a dot",
        )
        assertEquals(1, backend.search.findInFiles("hello", limit = 1).size, "the limit stops the walk")
    }

    /** A regex the user is still typing must show nothing, not throw. */
    @Test
    fun anUnfinishedRegexFindsNothingRatherThanFailing() = runTest {
        openSearchableProject()
        assertEquals(emptyList(), backend.search.findInFiles("hello(", UiSearchOptions(regex = true)))
    }

    @Test
    fun goToSymbolFindsAProjectDeclarationAndWhereItIs() = runTest {
        val projectRoot = openSearchableProject()

        val hits = backend.search.searchSymbols("greet")

        val hit = assertNotNull(hits.firstOrNull { it.name == "greet" }, "got ${hits.map { it.name }}")
        assertEquals("method", hit.kind)
        val file = assertNotNull(hit.filePath, "a hit has to be navigable, or the row does nothing")
        val offset = assertNotNull(hit.offset)
        assertEquals(
            "greet",
            IosFiles.readText(file).substring(offset, offset + 5),
            "the offset lands on the NAME, which is where navigation puts the caret",
        )
        assertTrue(hits.any { it.name == "Greeter" }, "the enclosing class matches the same query")
    }

    @Test
    fun goToSymbolMatchesCaseInsensitivelyAndSearchesUnsavedBuffersToo() = runTest {
        val projectRoot = openSearchableProject()
        assertTrue(backend.search.searchSymbols("GREETER").any { it.name == "Greeter" })

        val file = IosFiles.join(projectRoot, "app/src/main/kotlin/demo/Greeter.kt")
        backend.editor.updateDocument(file, "package demo\n\nclass Greeter {\n    fun farewell() = 1\n}\n")
        assertTrue(
            backend.search.searchSymbols("farewell").any { it.filePath == file },
            "a declaration typed but not saved is still a place to go to",
        )
    }

    /** With nothing open there is nothing to walk, and that is not an error. */
    @Test
    fun searchWithNoProjectOpenIsEmptyRatherThanBroken() = runTest {
        val fresh = IosBackend(root, prefs)
        assertEquals(emptyList(), fresh.search.findInFiles("hello"))
        assertEquals(emptyList(), fresh.search.searchSymbols("hello"))
    }

    /** The Members tab: unanswered on purpose, because no index here maps a member name to its owners. */
    @Test
    fun memberSearchIsEmptyOnThisHost() = runTest {
        openSearchableProject()
        assertEquals(emptyList(), backend.search.searchMembers("greet"))
    }


    // ---- threading + index status --------------------------------------------------------------------

    /**
     * Registering a buffer must not be what builds the index.
     *
     * It used to be: `updateDocument` reached the analysis object, so opening the first file constructed the
     * symbol model and read every jar on the classpath — on the editor's thread, which is the main one. The
     * index status is the observable proxy for that work having happened.
     */
    @Test
    fun recordingABufferDoesNotBuildTheIndex() = runTest {
        val projectRoot = openTwoFileProject()
        seedStdlib(projectRoot)
        assertTrue(backend.openProject(projectRoot))
        val file = IosFiles.join(projectRoot, "src/Use.kt")
        val text = "package demo\n\nfun use() {}\n"

        backend.editor.updateDocument(file, text)

        assertEquals(
            "",
            backend.search.indexStatus.value.message,
            "nothing has been indexed yet: a keystroke is a map write, not an index build",
        )

        // A real pass is what builds it, and the status says so afterwards.
        backend.editor.analyze(file, text)

        val status = backend.search.indexStatus.value
        assertFalse(status.building, "the build is finished by the time the pass returns")
        assertEquals("Indexed", status.message, "and the flow is wired, rather than StubBackend's default")
        assertEquals(1.0, status.fraction)
    }

    /**
     * Passes that run at once must not corrupt the analysis.
     *
     * The symbol service, the parse cache and the resolver memos are plain unsynchronised maps; they were
     * safe only because every pass ran on the one thread the editor called from. Now that they run off it,
     * the serialized dispatcher is what keeps that true, and this is the shape that would break without it:
     * the editor daemon fires diagnostics, hints and completion for the same buffer together.
     */
    @Test
    fun concurrentPassesOverTheSameBufferAllAnswer() = runTest {
        val projectRoot = openTwoFileProject()
        seedStdlib(projectRoot)
        assertTrue(backend.openProject(projectRoot))
        val file = IosFiles.join(projectRoot, "src/Use.kt")
        val text = "package demo\n\nfun use(): String {\n    val g = Greeter(\"w\")\n    return g.greet()\n}\n"

        val results = coroutineScope {
            List(8) { i ->
                async {
                    when (i % 4) {
                        0 -> { backend.editor.analyze(file, text); true }
                        1 -> { backend.editor.hintsAt(file, text, 0, text.length); true }
                        2 -> backend.editor.complete(file, text, text.indexOf("g.greet") + 2)
                            .items.any { it.label.startsWith("greet") }
                        else -> backend.editor.fileStructure(file, text).any { it.name == "use" }
                    }
                }
            }.awaitAll()
        }

        assertTrue(results.all { it }, "every pass answered, and answered correctly: $results")
    }

    /** Closing a tab drops its buffer, so the file reads from disk again. */
    @Test
    fun closingATabForgetsItsBuffer() = runTest {
        val projectRoot = openSearchableProject()
        val file = IosFiles.join(projectRoot, "app/src/main/kotlin/demo/Greeter.kt")
        backend.editor.updateDocument(file, "package demo\n\nclass Greeter {\n    fun farewell() = 1\n}\n")
        assertTrue(backend.search.findInFiles("farewell").any { it.filePath == file })

        backend.editor.onFileClosed(file)

        assertTrue(
            backend.search.findInFiles("farewell").isEmpty(),
            "the unsaved edit is gone with the tab, rather than outliving it and hiding the file on disk",
        )
        assertTrue(
            backend.search.findInFiles("hello").any { it.filePath == file },
            "and the file reads from disk again",
        )
    }

}

/** A per-instance suffix so concurrently-run test classes cannot share a directory. */
private var counter = 0
private fun nowSuffix(): String = "${++counter}-${IosFiles.modifiedMs(NSTemporaryDirectory())
}"
