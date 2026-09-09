package dev.ide.android.support.icons

import dev.ide.android.support.preview.DrawablePreview
import dev.ide.android.support.preview.DrawablePreviewParser
import dev.ide.android.support.preview.VectorGroup
import dev.ide.android.support.preview.VectorPath
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The two built-in icon repositories: the bundled Material subset (which must work with no network and whose
 * committed data is validated row by row) and the remote set (whose index parsing, URL shapes, disk cache and
 * offline behaviour are checked against a fake [IconHttp] rather than the real network).
 */
class IconRepositoryTest {

    // --- bundled -------------------------------------------------------------------------------------

    private val bundled = BundledMaterialIcons()

    @Test
    fun theBundledSetShipsWithTheModuleAndIsSearchable() {
        val entries = bundled.entries()
        assertTrue(entries.size >= 200, "expected a few hundred bundled icons, got ${entries.size}")
        assertFalse(bundled.requiresNetwork)

        val home = assertNotNull(entries.firstOrNull { it.name == "home" }, "the bundled set should include home")
        assertEquals("Home", home.displayName)
        assertEquals(BundledMaterialIcons.ID, home.repositoryId)
        assertTrue(home.supportsFill)
        assertTrue(home.keywords.isNotEmpty(), "bundled icons carry search keywords")
    }

    @Test
    fun theMostUsedIconsComeFirst() {
        // The generator orders rows by Google's popularity metadata, and the picker relies on that order.
        val first = bundled.entries().take(12).map { it.name }
        assertTrue("search" in first && "home" in first && "settings" in first, "unexpected head of the list: $first")
    }

    @Test
    fun aBundledIconRendersAsAVectorInThe960Box() {
        val entry = assertNotNull(bundled.entries().firstOrNull { it.name == "home" })
        val spec = assertNotNull(bundled.artwork(entry)).spec

        assertEquals(24f, spec.widthDp)
        assertEquals(960f, spec.viewportWidth)
        // Material Symbols draw above the origin, so the bundle's viewBox offset has to be carried.
        val group = spec.nodes.single() as VectorGroup
        assertEquals(960f, group.translateY)
        val path = group.children.single() as VectorPath
        assertTrue(path.pathData.isNotEmpty())
        assertEquals(0xFF000000L, path.fillColor)
    }

    @Test
    fun theFilledVariantIsDifferentArtwork() {
        val entry = assertNotNull(bundled.entries().firstOrNull { it.name == "favorite" })
        val outlined = assertNotNull(bundled.artwork(entry, IconVariant(filled = false))).spec.paths.single().pathData
        val filled = assertNotNull(bundled.artwork(entry, IconVariant(filled = true))).spec.paths.single().pathData
        assertTrue(outlined.isNotEmpty() && filled.isNotEmpty())
        assertTrue(outlined != filled, "the filled variant should not be the outlined path data")
    }

    @Test
    fun askingTheBundledSetForAStyleItDoesNotShipSaysSo() {
        val entry = assertNotNull(bundled.entries().firstOrNull())
        val artwork = assertNotNull(bundled.artwork(entry, IconVariant(style = IconStyle.ROUNDED)))
        assertTrue(artwork.warnings.any { it.contains("outlined") }, artwork.warnings.toString())
    }

    @Test
    fun anUnknownIconHasNoArtwork() {
        val fake = IconEntry(BundledMaterialIcons.ID, "definitely_not_an_icon", "Nope")
        assertNull(bundled.artwork(fake))
    }

    @Test
    fun aMissingResourceDegradesToAnEmptyRepositoryRatherThanThrowing() {
        val missing = BundledMaterialIcons(resourcePath = "no-such-file.tsv")
        assertTrue(missing.entries().isEmpty())
        assertNull(missing.artwork(IconEntry(BundledMaterialIcons.ID, "home", "Home")))
    }

    /**
     * Every committed path string has to be geometry our own path parser understands: this walks all 600 of
     * them (outlined and filled) so a bad regeneration can never ship as silently blank icons.
     */
    @Test
    fun everyBundledPathParsesIntoRealGeometry() {
        var checked = 0
        for (entry in bundled.entries()) {
            for (filled in listOf(false, true)) {
                if (filled && !entry.supportsFill) continue
                val artwork = assertNotNull(bundled.artwork(entry, IconVariant(filled = filled)), entry.name)
                val data = artwork.spec.paths.single().pathData
                val segments = SvgPathData.parse(data)
                assertTrue(segments.size >= 2, "${entry.name} (filled=$filled) parsed to ${segments.size} segments")
                assertTrue(
                    segments.first() is PathSegment.MoveTo,
                    "${entry.name} (filled=$filled) does not start with a move",
                )
                checked++
            }
        }
        assertTrue(checked >= 400, "expected to check both variants of every icon, only did $checked")
    }

    @Test
    fun aBundledIconWritesOutAsParseableVectorDrawableXml() {
        val entry = assertNotNull(bundled.entries().firstOrNull { it.name == "settings" })
        val spec = assertNotNull(bundled.artwork(entry)).spec
        val xml = VectorDrawableWriter.write(spec.recolored(0xFF6200EEL))

        val parsed = DrawablePreviewParser.parse(xml)
        assertTrue(parsed is DrawablePreview.Vector, xml)
        assertEquals(0xFF6200EEL, parsed.spec.paths.single().fillColor, "recolouring applies to the written file")
    }

    // --- remote --------------------------------------------------------------------------------------

    private val indexText = """
        home e88a
        search e8b6
        settings e8b8
        # not an icon
        with-a-dash e000
    """.trimIndent()

    private val svgText = """
        <svg xmlns="http://www.w3.org/2000/svg" height="24" viewBox="0 -960 960 960" width="24">
          <path d="M240-200h120v-240h240v240h120Z"/>
        </svg>
    """.trimIndent()

    @Test
    fun theRemoteSetIsEmptyUntilItIsExplicitlyLoaded() {
        val repo = MaterialSymbolsRemote(createTempDirectory("icons"), IconHttp { null })
        assertTrue(repo.requiresNetwork)
        assertTrue(repo.entries().isEmpty(), "nothing is fetched before load()")
    }

    @Test
    fun loadingBuildsEntriesFromTheCodepointsIndex() {
        val repo = MaterialSymbolsRemote(createTempDirectory("icons")) { url ->
            if (url == MaterialSymbolsRemote.INDEX_URL) indexText.encodeToByteArray() else null
        }
        assertTrue(repo.load().isSuccess)
        val names = repo.entries().map { it.name }
        assertEquals(listOf("home", "search", "settings"), names, "a dashed or commented line is not an icon")
        val home = repo.entries().first()
        assertEquals(setOf(IconStyle.OUTLINED, IconStyle.ROUNDED, IconStyle.SHARP), home.styles)
        assertTrue(home.supportsFill)
    }

    @Test
    fun aFailedIndexDownloadIsReportedRatherThanLeavingAnEmptyList() {
        val repo = MaterialSymbolsRemote(createTempDirectory("icons"), IconHttp { null })
        val result = repo.load()
        assertTrue(result.isFailure)
        assertTrue(repo.entries().isEmpty())
    }

    @Test
    fun anEmptyIndexIsTreatedAsAFailure() {
        val repo = MaterialSymbolsRemote(createTempDirectory("icons")) { "\n\n".encodeToByteArray() }
        assertTrue(repo.load().isFailure)
    }

    @Test
    fun eachVariantIsFetchedFromItsOwnFamilyUrl() {
        val asked = ArrayList<String>()
        val repo = MaterialSymbolsRemote(createTempDirectory("icons")) { url ->
            asked += url
            if (url == MaterialSymbolsRemote.INDEX_URL) indexText.encodeToByteArray() else svgText.encodeToByteArray()
        }
        repo.load()
        val home = repo.entries().first { it.name == "home" }

        assertNotNull(repo.artwork(home, IconVariant(IconStyle.OUTLINED, filled = false)))
        assertNotNull(repo.artwork(home, IconVariant(IconStyle.ROUNDED, filled = true)))

        assertTrue(asked.any { it.endsWith("/materialsymbolsoutlined/home_24px.svg") }, asked.toString())
        assertTrue(asked.any { it.endsWith("/materialsymbolsrounded/home_fill1_24px.svg") }, asked.toString())
    }

    @Test
    fun aFetchedIconIsConvertedThroughTheSharedSvgPipeline() {
        val repo = MaterialSymbolsRemote(createTempDirectory("icons")) { url ->
            if (url == MaterialSymbolsRemote.INDEX_URL) indexText.encodeToByteArray() else svgText.encodeToByteArray()
        }
        repo.load()
        val spec = assertNotNull(repo.artwork(repo.entries().first())).spec
        assertEquals(960f, spec.viewportWidth)
        assertEquals(24f, spec.widthDp)
        assertEquals("M240-200h120v-240h240v240h120Z", spec.paths.single().pathData)
    }

    @Test
    fun downloadsAreCachedOnDiskAndTheSecondReadIsOffline() {
        val dir = createTempDirectory("icons")
        var requests = 0
        val online = MaterialSymbolsRemote(dir) { url ->
            requests++
            if (url == MaterialSymbolsRemote.INDEX_URL) indexText.encodeToByteArray() else svgText.encodeToByteArray()
        }
        online.load()
        val entry = online.entries().first { it.name == "home" }
        assertNotNull(online.artwork(entry))
        val afterFirstPass = requests

        // A fresh repository over the same cache, with the network hard-failing.
        val offline = MaterialSymbolsRemote(dir, IconHttp { error("the network must not be touched") })
        assertTrue(offline.load().isSuccess, "the index came from the cache")
        assertNotNull(offline.artwork(offline.entries().first { it.name == "home" }), "the SVG came from the cache")
        assertEquals(afterFirstPass, requests, "no further requests were made")

        assertTrue(dir.resolve("material-symbols/index.codepoints").exists())
        val cachedSvg = dir.resolve("material-symbols/outlined/home.svg")
        assertTrue(cachedSvg.exists())
        assertTrue(cachedSvg.readText().contains("<path"))
    }

    @Test
    fun anInterruptedDownloadLeavesNoPartialCacheEntry() {
        val dir = createTempDirectory("icons")
        val repo = MaterialSymbolsRemote(dir) { url ->
            if (url == MaterialSymbolsRemote.INDEX_URL) indexText.encodeToByteArray() else null
        }
        repo.load()
        assertNull(repo.artwork(repo.entries().first()))
        assertFalse(dir.resolve("material-symbols/outlined/home.svg").exists())
        assertFalse(dir.resolve("material-symbols/outlined/home.svg.part").exists())
    }

    // --- search --------------------------------------------------------------------------------------

    @Test
    fun searchRanksExactThenPrefixThenKeyword() {
        val entries = listOf(
            entry("shopping_cart", keywords = listOf("buy")),
            entry("cart_check", keywords = emptyList()),
            entry("add", keywords = listOf("cart", "plus")),
            entry("cart", keywords = emptyList()),
        )
        assertEquals(
            listOf("cart", "cart_check", "shopping_cart", "add"),
            IconSearch.filter(entries, "cart").map { it.name },
        )
    }

    /** A word match inside a name still hits, but ranks below a name that starts with the query. */
    @Test
    fun searchMatchesWordsInsideAnUnderscoredName() {
        val entries = listOf(entry("arrow_back_ios"), entry("backspace"), entry("unrelated"))
        assertEquals(listOf("backspace", "arrow_back_ios"), IconSearch.filter(entries, "back").map { it.name })
    }

    @Test
    fun searchTreatsSpacesLikeUnderscoresAndIgnoresCase() {
        val entries = listOf(entry("shopping_cart"), entry("other"))
        assertEquals(listOf("shopping_cart"), IconSearch.filter(entries, "Shopping Cart").map { it.name })
    }

    @Test
    fun aBlankQueryKeepsTheRepositoryOrderAndHonoursTheLimit() {
        val entries = listOf(entry("a"), entry("b"), entry("c"))
        assertEquals(listOf("a", "b", "c"), IconSearch.filter(entries, "  ").map { it.name })
        assertEquals(listOf("a", "b"), IconSearch.filter(entries, "", limit = 2).map { it.name })
    }

    @Test
    fun aQueryThatMatchesNothingReturnsNothing() {
        assertTrue(IconSearch.filter(listOf(entry("home")), "zzzz").isEmpty())
    }

    @Test
    fun theRealBundledSetIsSearchableBySynonym() {
        // "trash" is not in any icon's name; it only matches through the keyword list.
        val hits = IconSearch.filter(bundled.entries(), "trash").map { it.name }
        assertTrue("delete" in hits, "expected delete to match the synonym, got $hits")
    }

    private fun entry(name: String, keywords: List<String> = emptyList(), category: String? = null) =
        IconEntry(repositoryId = "test", name = name, displayName = name, keywords = keywords, category = category)
}
