package dev.ide.core

import dev.ide.model.ContentRole
import dev.ide.model.MODULE_RESOURCES
import dev.ide.model.MODULE_SOURCES
import dev.ide.model.ModuleResources
import dev.ide.model.Module
import dev.ide.model.ResourceConflict
import dev.ide.model.ResourceFilter
import dev.ide.model.ResourceWrite
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * `platform.moduleResources`, the published slice of the engine's Android resource service: a plugin queries
 * what a module declares and adds to it, over the demo project (`app` depends on `feature`, both Android;
 * `core` is a plain Java module with no `res/`).
 *
 * The point of the promotion is that a plugin reaches the LIVE service rather than a parallel one, so the
 * alias identity is asserted first; everything after it is the contract the alias makes reachable.
 */
class ModuleResourcesSpiTest {

    private val root = createTempDirectory("module-resources-spi")
    private val services: IdeServices = IdeServices.bootstrapDemo(root)

    @AfterTest
    fun tearDown() {
        services.close()
        root.toFile().deleteRecursively()
    }

    private val resources: ModuleResources
        get() = services.store.workspace.service(MODULE_RESOURCES)

    private fun module(name: String): Module = services.modules().first { it.name == name }

    /**
     * The plain JVM module, given a `src/main/resources` root the way a plugin would give it one: through the
     * other published service. The demo's `core` declares only `src/main/java`, which is the ordinary state of
     * a module whose type has no opinion about resources, and is exactly the case a plugin has to handle.
     */
    private fun jvmModuleWithResources(): Module {
        assertNotNull(
            services.store.workspace.service(MODULE_SOURCES)
                .addSourceRoot("core", "main", "resources", setOf(ContentRole.RESOURCE)),
            "MODULE_SOURCES declares the root, MODULE_RESOURCES writes into it",
        )
        return module("core")
    }

    private fun stringsXml() = root.resolve("app/src/main/res/values/strings.xml").n()

    /** Absolute + normalized, the form the VFS hands back, so a path assertion compares like with like. */
    private fun Path.n(): Path = toAbsolutePath().normalize()

    @Test
    fun `the SPI key resolves the same instance as the engine service`() {
        val viaSpi = resources
        val viaEngine = services.store.workspaceContainer.getService(ANDROID_RESOURCE_SERVICE)
        assertSame(
            viaEngine, viaSpi,
            "the published alias must resolve the engine's own service, not a second instance: a plugin and " +
                "the IDE have to act on the same resources",
        )
    }

    @Test
    fun `resourceRoots put main first so an authored resource is not variant-only`() {
        val roots = resources.resourceRoots(module("app"), ContentRole.ANDROID_RES).map { it.n() }
        assertTrue(roots.size > 1, "the android-app type declares a res root per source set: $roots")
        assertEquals(
            root.resolve("app/src/main/res").n(), roots.first(),
            "main leads, ahead of the variant roots that come before it in declaration order: a resource " +
                "written into src/debug/res is missing from the release build",
        )
        assertTrue(
            resources.resourceRoots(module("core"), ContentRole.ANDROID_RES).isEmpty(),
            "a java-lib declares no res/",
        )
    }

    @Test
    fun `types and names report what the module can name`() {
        val app = module("app")
        val types = resources.types(app)
        assertTrue("string" in types && "color" in types, "the demo app declares strings and colors: $types")
        val strings = resources.names(app, "string")
        assertTrue("app_name" in strings && "greeting" in strings, "app's own strings: $strings")
        assertTrue(
            "feature_title" in strings,
            "a dependency module's strings are nameable from app, so they are part of its resource set",
        )
        assertTrue(resources.names(app, "not_a_type").isEmpty(), "an unknown R class is empty, not an error")
    }

    @Test
    fun `has answers for the merged set and reports false for an unknown type`() {
        val app = module("app")
        assertTrue(resources.has(app, "string", "app_name"))
        assertTrue(resources.has(app, "string", "feature_title"), "declared by the dependency module")
        assertFalse(resources.has(app, "string", "nope"))
        assertFalse(resources.has(app, "not_a_type", "app_name"))
    }

    @Test
    fun `find filters by type, prefix, substring and limit`() {
        val app = module("app")
        val colors = resources.find(app, ResourceFilter(rClass = "color"))
        assertTrue(colors.isNotEmpty() && colors.all { it.rClass == "color" })
        assertTrue(colors.any { it.name == "primary" && it.value == "#FF6200EE" }, "value resources carry their text")

        assertEquals(
            listOf("on_primary"),
            resources.find(app, ResourceFilter(rClass = "color", namePrefix = "on_")).map { it.name },
        )
        assertTrue(
            resources.find(app, ResourceFilter(rClass = "string", nameContains = "NAME")).any { it.name == "app_name" },
            "nameContains is case-insensitive",
        )
        assertEquals(2, resources.find(app, ResourceFilter(limit = 2)).size)
        assertTrue(resources.find(app, ResourceFilter(limit = 0)).isEmpty())

        val exact = resources.find(app, ResourceFilter(rClass = "string", name = "greeting"))
        assertEquals(1, exact.size)
        assertEquals("Hello, Android", exact.single().value)
        assertEquals(stringsXml(), exact.single().file?.n())
    }

    @Test
    fun `moduleOnly drops what a dependency declares`() {
        val app = module("app")
        assertTrue(
            resources.find(app, ResourceFilter(rClass = "string")).any { it.name == "feature_title" },
            "the merged view is the default, because that is the set the module's code can name",
        )
        assertFalse(
            resources.find(app, ResourceFilter(rClass = "string", moduleOnly = true))
                .any { it.name == "feature_title" },
            "moduleOnly is the question a write asks: what does THIS module declare",
        )
        assertTrue(
            resources.find(module("feature"), ResourceFilter(rClass = "string", moduleOnly = true))
                .any { it.name == "feature_title" },
        )
    }

    @Test
    fun `allConfigs and qualifier separate a night override from the default`() {
        val app = module("app")
        // The demo writes themes.xml under both values/ and values-night/, so AppTheme is declared twice.
        val collapsed = resources.find(app, ResourceFilter(rClass = "style", nameContains = "Theme"))
        val everyConfig = resources.find(
            app, ResourceFilter(rClass = "style", nameContains = "Theme", allConfigs = true),
        )
        assertTrue(
            everyConfig.size > collapsed.size,
            "allConfigs keeps every definition; the default collapses to one per name",
        )
        assertTrue(
            everyConfig.any { it.qualifier == "night" },
            "the night config is reachable: ${everyConfig.map { it.qualifier }}",
        )
        assertTrue(
            resources.find(app, ResourceFilter(qualifier = "night", allConfigs = true))
                .all { it.qualifier == "night" },
        )
    }

    @Test
    fun `putValueResource writes a string that immediately resolves`() {
        val app = module("app")
        val write = resources.putValueResource(app, "string", "spi_added", "Hi")
        val written = assertIs<ResourceWrite.Written>(write)
        assertEquals("spi_added", written.name)
        assertEquals(stringsXml(), written.file.n())
        assertTrue(stringsXml().readText().contains("""<string name="spi_added">Hi</string>"""))
        assertTrue(
            resources.has(app, "string", "spi_added"),
            "the write refreshes the resource model, so the new string resolves with no rebuild",
        )
    }

    /**
     * The resource repository is buffer-aware, and the overlay outlives the tab that created it, so
     * `strings.xml` having been OPEN once was enough to hide every later write behind it: the string was on
     * disk and nowhere the IDE looked, until a restart cleared the overlay. The write publishes, and the
     * reaction re-reads the overlay before anything consumes it.
     */
    @Test
    fun `a write behind an open strings xml buffer still resolves`() {
        val app = module("app")
        services.updateDocument(stringsXml(), stringsXml().readText()) // the user opened it to check

        val written = assertIs<ResourceWrite.Written>(
            resources.putValueResource(app, "string", "behind_a_buffer", "Hi"),
        )
        assertEquals("behind_a_buffer", written.name)
        assertTrue(
            resources.has(app, "string", "behind_a_buffer"),
            "the open buffer still wins over disk in the resource repository",
        )
        assertTrue(
            "behind_a_buffer" in services.readCurrentText(stringsXml()),
            "the buffer the editor is showing has to catch up with what was written under it",
        )
    }

    @Test
    fun `putValueResource escapes the value`() {
        val app = module("app")
        assertIs<ResourceWrite.Written>(resources.putValueResource(app, "string", "spi_escaped", "a & b < c"))
        assertTrue(stringsXml().readText().contains(">a &amp; b &lt; c<"))
    }

    @Test
    fun `putValueResource creates the values file a type lives in`() {
        val app = module("app")
        val dimens = root.resolve("app/src/main/res/values/dimens.xml")
        assertFalse(Files.exists(dimens))
        val written = assertIs<ResourceWrite.Written>(resources.putValueResource(app, "dimen", "gap", "16dp"))
        assertEquals(dimens.n(), written.file.n())
        assertTrue(dimens.readText().contains("""<dimen name="gap">16dp</dimen>"""))
        assertTrue(dimens.readText().startsWith("<?xml"), "a created values file is a whole document")
    }

    @Test
    fun `a name the module already declares fails by default`() {
        val app = module("app")
        val existing = assertIs<ResourceWrite.AlreadyExists>(
            resources.putValueResource(app, "string", "app_name", "Other"),
        )
        assertEquals(stringsXml(), existing.file?.n())
        assertTrue(
            stringsXml().readText().contains(">Android Demo<"),
            "FAIL leaves the existing definition alone",
        )
    }

    @Test
    fun `a name only a dependency declares is not a conflict`() {
        // Shadowing a library's resource is what an override IS, so it must not be refused.
        val app = module("app")
        val written = assertIs<ResourceWrite.Written>(
            resources.putValueResource(app, "string", "feature_title", "Overridden"),
        )
        assertEquals("feature_title", written.name, "no suffix: app was not redeclaring its own resource")
    }

    @Test
    fun `RENAME suffixes past every taken name`() {
        val app = module("app")
        assertEquals(
            "app_name_1",
            assertIs<ResourceWrite.Written>(
                resources.putValueResource(app, "string", "app_name", "A", ResourceConflict.RENAME),
            ).name,
        )
        assertEquals(
            "app_name_2",
            assertIs<ResourceWrite.Written>(
                resources.putValueResource(app, "string", "app_name", "B", ResourceConflict.RENAME),
            ).name,
            "the second rename must see the first, which the repository fingerprint alone would not guarantee",
        )
    }

    @Test
    fun `REPLACE rewrites in place rather than declaring the name twice`() {
        val app = module("app")
        val written = assertIs<ResourceWrite.Written>(
            resources.putValueResource(app, "string", "greeting", "Goodbye, Android", ResourceConflict.REPLACE),
        )
        assertEquals("greeting", written.name)
        val text = stringsXml().readText()
        assertTrue(text.contains("""<string name="greeting">Goodbye, Android</string>"""), text)
        assertEquals(1, Regex("""name="greeting"""").findAll(text).count(), "one declaration, not two")
    }

    @Test
    fun `REPLACE on an absent name simply writes it`() {
        val app = module("app")
        val written = assertIs<ResourceWrite.Written>(
            resources.putValueResource(app, "string", "spi_upsert", "V", ResourceConflict.REPLACE),
        )
        assertEquals("spi_upsert", written.name)
    }

    @Test
    fun `createResourceFile stubs a layout and then reports the collision`() {
        val app = module("app")
        val written = assertIs<ResourceWrite.Written>(resources.createResourceFile(app, "layout", "spi_screen"))
        assertEquals(root.resolve("app/src/main/res/layout/spi_screen.xml").n(), written.file.n())
        assertTrue(written.file.readText().contains("<FrameLayout"), "the default stub is valid for the type")

        assertEquals(
            written.file.n(),
            assertIs<ResourceWrite.AlreadyExists>(
                resources.createResourceFile(app, "layout", "spi_screen"),
            ).file?.n(),
        )
        assertEquals(
            "spi_screen_1",
            assertIs<ResourceWrite.Written>(
                resources.createResourceFile(app, "layout", "spi_screen", onConflict = ResourceConflict.RENAME),
            ).name,
        )
    }

    @Test
    fun `createResourceFile takes content of the caller's own`() {
        val app = module("app")
        val body = "<vector xmlns:android=\"http://schemas.android.com/apk/res/android\" />"
        val written = assertIs<ResourceWrite.Written>(
            resources.createResourceFile(app, "drawable", "spi_icon", content = body),
        )
        assertEquals(body, written.file.readText())
    }

    @Test
    fun `the Android half answers a non-Android module rather than throwing at it`() {
        // A plugin sweeping a mixed project must not have to ask what kind of module it landed on first.
        val core = module("core")
        assertEquals(ResourceWrite.NoResourceRoot, resources.putValueResource(core, "string", "x", "y"))
        assertEquals(ResourceWrite.NoResourceRoot, resources.createResourceFile(core, "layout", "x"))
        assertTrue(resources.types(core).isEmpty())
        assertTrue(resources.names(core, "string").isEmpty())
        assertFalse(resources.has(core, "string", "anything"))
        assertTrue(resources.find(core).isEmpty())
    }

    // ---- the module-type-neutral half ---------------------------------------------------------------

    @Test
    fun `putResourceFile writes into a plain JVM module's resources root`() {
        val core = jvmModuleWithResources()
        val roots = resources.resourceRoots(core, ContentRole.RESOURCE)
        assertTrue(roots.isNotEmpty(), "a java-lib declares src/main/resources: $roots")

        val written = assertIs<ResourceWrite.Written>(
            resources.putResourceFile(core, ContentRole.RESOURCE, "app.properties", "a=b"),
        )
        assertEquals(roots.first().resolve("app.properties").n(), written.file.n())
        assertEquals("a=b", written.file.readText())
    }

    @Test
    fun `putResourceFile creates intermediate directories and takes bytes`() {
        val core = jvmModuleWithResources()
        val written = assertIs<ResourceWrite.Written>(
            resources.putResourceFile(
                core, ContentRole.RESOURCE, "META-INF/services/dev.ide.Thing", byteArrayOf(1, 2, 3),
            ),
        )
        assertTrue(written.file.toString().endsWith("META-INF/services/dev.ide.Thing"))
        assertContentEquals(byteArrayOf(1, 2, 3), Files.readAllBytes(written.file))
    }

    @Test
    fun `putResourceFile honours the conflict policy, suffixing before the extension`() {
        val core = jvmModuleWithResources()
        assertIs<ResourceWrite.Written>(resources.putResourceFile(core, ContentRole.RESOURCE, "cfg.json", "{}"))
        assertIs<ResourceWrite.AlreadyExists>(
            resources.putResourceFile(core, ContentRole.RESOURCE, "cfg.json", "{}"),
        )
        assertEquals(
            "cfg_1.json",
            assertIs<ResourceWrite.Written>(
                resources.putResourceFile(core, ContentRole.RESOURCE, "cfg.json", "{}", ResourceConflict.RENAME),
            ).name,
            "the extension decides how a file is read, so the suffix goes on the base name",
        )
        assertIs<ResourceWrite.Written>(
            resources.putResourceFile(core, ContentRole.RESOURCE, "cfg.json", "{\"v\":2}", ResourceConflict.REPLACE),
        )
        assertEquals("{\"v\":2}", resources.resourceRoots(core, ContentRole.RESOURCE).first()
            .resolve("cfg.json").readText())
    }

    @Test
    fun `putResourceFile refuses a path that leaves the module`() {
        val core = jvmModuleWithResources()
        val escape = assertIs<ResourceWrite.Failed>(
            resources.putResourceFile(core, ContentRole.RESOURCE, "../../../../evil.txt", "x"),
        )
        assertTrue("does not stay inside" in escape.reason, escape.reason)
        assertFalse(Files.exists(root.resolve("evil.txt")))
        assertIs<ResourceWrite.Failed>(resources.putResourceFile(core, ContentRole.RESOURCE, "  ", "x"))
    }

    @Test
    fun `putResourceFile reports a role the module type has no notion of`() {
        assertEquals(
            ResourceWrite.NoResourceRoot,
            resources.putResourceFile(module("core"), ContentRole.ANDROID_RES, "values/x.xml", "<resources/>"),
        )
        assertEquals(
            ResourceWrite.NoResourceRoot,
            resources.putResourceFile(module("core"), ContentRole("a-role-no-module-type-declares"), "x", "y"),
        )
    }

    @Test
    fun `putResourceFile works on an Android module too, for the roles res is not`() {
        val app = module("app")
        val written = assertIs<ResourceWrite.Written>(
            resources.putResourceFile(app, ContentRole.ANDROID_RES, "raw/seed.json", "[]"),
        )
        assertEquals(root.resolve("app/src/main/res/raw/seed.json").n(), written.file.n())
        assertEquals("[]", written.file.readText())
    }

    @Test
    fun `the two write halves refuse each other's types, and an unknown type at all`() {
        val app = module("app")
        assertIs<ResourceWrite.Failed>(resources.putValueResource(app, "layout", "x", "y"))
        assertIs<ResourceWrite.Failed>(resources.createResourceFile(app, "string", "x"))
        assertIs<ResourceWrite.Failed>(resources.putValueResource(app, "not_a_type", "x", "y"))
        assertIs<ResourceWrite.Failed>(resources.createResourceFile(app, "not_a_type", "x"))
        assertIs<ResourceWrite.Failed>(resources.putValueResource(app, "string", "  ", "y"))
    }

    @Test
    fun `isValueType and isFileType say which half a type belongs to`() {
        assertTrue(resources.isValueType("string") && resources.isValueType("color"))
        assertFalse(resources.isValueType("layout") || resources.isValueType("not_a_type"))
        assertTrue(resources.isFileType("layout") && resources.isFileType("drawable"))
        assertFalse(resources.isFileType("string") || resources.isFileType("not_a_type"))
    }

    @Test
    fun `the editor's create-string quick fix runs on the same members`() {
        // The engine's own resource authoring is no longer a private variant of this: the XML lint host's
        // appendValueResource delegates to putValueResource, so the fix and a plugin cannot drift apart.
        val host = services.xmlResourceHost
        val layout = services.store.vfs.fileFor(root.resolve("app/src/main/res/layout/activity_main.xml"))
        assertEquals("fix_added", host.appendValueResource(layout, "string", "fix_added", "Added"))
        assertTrue(stringsXml().readText().contains("""<string name="fix_added">Added</string>"""))
        assertEquals(
            "app_name_1", host.appendValueResource(layout, "string", "app_name", "X"),
            "the fix always produces a name to point the reference at, so it renames rather than failing",
        )
        assertNotNull(host.createResourceFile(layout, "layout", "fix_created"))
    }
}
