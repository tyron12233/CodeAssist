package dev.ide.model.impl

import dev.ide.model.BuildSystemId
import dev.ide.model.Facet
import dev.ide.model.FacetCodec
import dev.ide.model.FacetCodecRegistry
import dev.ide.model.FacetData
import dev.ide.model.FacetKey
import dev.ide.model.ModuleTypeRegistry
import dev.ide.model.RESERVED_FACET_TABLES
import dev.ide.platform.PluginId
import dev.ide.platform.impl.PlatformCore
import dev.ide.platform.log.Log
import dev.ide.platform.log.LogRecord
import dev.ide.platform.log.LogSink
import dev.ide.testkit.registerTestTypes
import dev.ide.testkit.withTempDir
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Writing a facet without the class that declares it: what a project template scaffolding a module of
 * another plugin's type, or an importer reading a foreign build file, has to do.
 *
 * The facet class and its codec belong to one plugin and are not on a published artifact, so a second plugin
 * cannot name them. Before [dev.ide.model.ModifiableModule.putFacetData] the only way through was to declare
 * a duplicate facet and aim its codec at the same `module.toml` table, which takes that table's persistence
 * over for every module in every project.
 */
class FacetDataWriteTest {

    /** A second codec for the `java` table, as a plugin cloning another's facet would produce. */
    private object ImpostorFacet : Facet {
        val KEY = FacetKey<ImpostorFacet>("impostor")
        override val key: FacetKey<*> get() = KEY
    }

    private object ImpostorJavaCodec : FacetCodec<ImpostorFacet> {
        override val key: FacetKey<ImpostorFacet> = ImpostorFacet.KEY
        override val tomlTable: String = "java"
        override fun encode(facet: ImpostorFacet): Map<String, Any?> = emptyMap()
        override fun decode(values: Map<String, Any?>): ImpostorFacet = ImpostorFacet
    }

    @Test
    fun aCallerWithNoFacetClassConfiguresTheFacetThroughItsTable() = withTempDir("codeassist-facet-data") { dir ->
        val platform = PlatformCore()
        try {
            platform.registerTestTypes()
            val store = ProjectModel.open(dir, platform, FacetCodecRegistry().register(JavaFacetCodec))
            store.workspace.beginModification().apply {
                addProject("app", BuildSystemId.NATIVE, store.vfs.root())
                commit()
            }
            store.workspace.projects.single().beginModification().apply {
                addModule("app", ModuleTypeRegistry(platform.extensions).resolve("java-lib")).apply {
                    // All a template has: the table name and the value map the codec reads, never JavaFacet.
                    putFacetData(
                        FacetData(
                            JavaFacetCodec.tomlTable,
                            linkedMapOf("annotationProcessors" to listOf("dagger"), "preview" to true),
                        ),
                    )
                }
                commit()
            }
            store.save()

            val platform2 = PlatformCore()
            try {
                platform2.registerTestTypes()
                val store2 = ProjectModel.open(dir, platform2, FacetCodecRegistry().register(JavaFacetCodec))
                val facet = store2.workspace.projects.single().modules.single().facets.get(JavaFacet.KEY)
                assertEquals(
                    JavaFacet(listOf("dagger"), preview = true),
                    assertNotNull(facet),
                    "the plugin that owns the facet reads back a typed one",
                )
            } finally {
                platform2.dispose()
            }
        } finally {
            platform.dispose()
        }
    }

    @Test
    fun aTableNoCodecClaimsRoundTripsAsWritten() = withTempDir("codeassist-facet-unclaimed") { dir ->
        val platform = PlatformCore()
        try {
            platform.registerTestTypes()
            val store = ProjectModel.open(dir, platform, FacetCodecRegistry())
            store.workspace.beginModification().apply {
                addProject("app", BuildSystemId.NATIVE, store.vfs.root())
                commit()
            }
            store.workspace.projects.single().beginModification().apply {
                addModule("app", ModuleTypeRegistry(platform.extensions).resolve("java-lib")).apply {
                    putFacetData(FacetData("python", linkedMapOf("interpreter" to "python3.12")))
                }
                commit()
            }
            store.save()

            val toml = dir.resolve("app").resolve("module.toml").toFile().readText()
            assertTrue("[python]" in toml && """interpreter = "python3.12"""" in toml, toml)
        } finally {
            platform.dispose()
        }
    }

    @Test
    fun aReservedTableIsRefused() = withModule { module ->
        for (table in RESERVED_FACET_TABLES) {
            val failure = assertFailsWith<IllegalArgumentException> {
                module.putFacetData(FacetData(table, linkedMapOf("k" to "v")))
            }
            assertTrue(table in failure.message.orEmpty(), failure.message.orEmpty())
        }
    }

    @Test
    fun aValueTomlCannotHoldIsRefusedWhereItIsStaged() = withModule { module ->
        val nulled = assertFailsWith<IllegalArgumentException> {
            module.putFacetData(FacetData("java", linkedMapOf("preview" to null)))
        }
        assertTrue("omit the key" in nulled.message.orEmpty(), nulled.message.orEmpty())

        val wrongType = assertFailsWith<IllegalArgumentException> {
            module.putFacetData(FacetData("java", linkedMapOf("processors" to listOf(StringBuilder()))))
        }
        assertTrue("java.lang.StringBuilder" in wrongType.message.orEmpty(), wrongType.message.orEmpty())

        val wrongKey = assertFailsWith<IllegalArgumentException> {
            module.putFacetData(FacetData("java", linkedMapOf("packaging" to mapOf(1 to "a"))))
        }
        assertTrue("must be String" in wrongKey.message.orEmpty(), wrongKey.message.orEmpty())
    }

    @Test
    fun aSecondCodecForATableIsReportedOnce() {
        val records = ArrayList<LogRecord>()
        val sink = LogSink { records.add(it) }
        Log.addSink(sink)
        try {
            val codecs = FacetCodecRegistry()
                .register(JavaFacetCodec, PluginId("java-support"))
                .register(ImpostorJavaCodec, PluginId("impostor"))

            assertSame(ImpostorJavaCodec, codecs.codecForTable("java"), "last registration still wins by table")
            assertSame(JavaFacetCodec, codecs.codecFor(JavaFacet.KEY), "the shadowed codec still answers its key")

            val warning = records.single { "module.toml table 'java'" in it.message }
            assertTrue(JavaFacetCodec::class.java.name in warning.message, warning.message)
            assertTrue(ImpostorJavaCodec::class.java.name in warning.message, warning.message)
        } finally {
            Log.removeSink(sink)
        }
    }

    @Test
    fun aCodecClaimingAReservedTableIsRefusedAtRegistration() {
        val failure = assertFailsWith<IllegalArgumentException> {
            FacetCodecRegistry().register(ReservedTableCodec)
        }
        assertTrue("dependencies" in failure.message.orEmpty(), failure.message.orEmpty())
        assertNull(FacetCodecRegistry().codecForTable("dependencies"))
    }

    private object ReservedTableCodec : FacetCodec<ImpostorFacet> {
        override val key: FacetKey<ImpostorFacet> = ImpostorFacet.KEY
        override val tomlTable: String = "dependencies"
        override fun encode(facet: ImpostorFacet): Map<String, Any?> = emptyMap()
        override fun decode(values: Map<String, Any?>): ImpostorFacet = ImpostorFacet
    }

    /** A staged module in a throwaway workspace, for the cases that never reach a commit. */
    private fun withModule(block: (dev.ide.model.ModifiableModule) -> Unit) =
        withTempDir("codeassist-facet-reject") { dir ->
            val platform = PlatformCore()
            try {
                platform.registerTestTypes()
                val store = ProjectModel.open(dir, platform, FacetCodecRegistry().register(JavaFacetCodec))
                store.workspace.beginModification().apply {
                    addProject("app", BuildSystemId.NATIVE, store.vfs.root())
                    commit()
                }
                val tx = store.workspace.projects.single().beginModification()
                try {
                    block(tx.addModule("app", ModuleTypeRegistry(platform.extensions).resolve("java-lib")))
                } finally {
                    tx.dispose()
                }
            } finally {
                platform.dispose()
            }
        }
}
