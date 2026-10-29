package dev.ide.ui.screens

import dev.ide.ui.StubBackend
import dev.ide.ui.backend.UiDrawable
import dev.ide.ui.backend.UiIconArtwork
import dev.ide.ui.backend.UiIconEntry
import dev.ide.ui.backend.UiIconImport
import dev.ide.ui.backend.UiIconImportResult
import dev.ide.ui.backend.UiIconLayer
import dev.ide.ui.backend.UiIconLoadResult
import dev.ide.ui.backend.UiIconRepo
import dev.ide.ui.backend.UiIconTarget
import dev.ide.ui.backend.UiIconRef
import dev.ide.ui.backend.UiIconVariant
import dev.ide.ui.backend.UiInsertionTarget
import dev.ide.ui.backend.UiNavTarget
import dev.ide.ui.backend.UiRenameResult
import dev.ide.ui.backend.UiResourceConfig
import dev.ide.ui.backend.UiResourceIcon
import dev.ide.ui.backend.UiVectorPath
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Icon Manager's state holder against a fake backend: section switching, the debounced search, the
 * artwork cache (and that it does not refetch), selection defaults, the import request it builds, and how it
 * reports a name conflict.
 */
class IconManagerStateTest {

    private class FakeIcons : StubBackend() {
        val artworkRequests = ArrayList<String>()
        val imports = ArrayList<UiIconImport>()

        /** "<repoId>:<name>:<style>:<filled>" per import, so the request translation is visible. */
        val importedAs = ArrayList<String>()
        var loaded = false
        var importResult = UiIconImportResult(ok = true, path = "/p/app/src/main/res/drawable/ic_home.xml")

        /** What [existingResource] reports, so the import-before-insert branch can be exercised. */
        var existing: String? = null

        /** Names whose import reports a conflict, for the batch cases. */
        val taken = HashSet<String>()

        val deleted = ArrayList<String>()
        val renames = ArrayList<String>()
        var composeCatalogue: List<UiIconEntry> = emptyList()

        override suspend fun iconUsages(resType: String, name: String): List<UiNavTarget> =
            listOf(UiNavTarget("/p/app/src/main/res/layout/main.xml", 120, "main.xml:4", "usage"))

        override suspend fun renameIcon(resType: String, oldName: String, newName: String): UiRenameResult {
            renames += "$resType/$oldName->$newName"
            return UiRenameResult(true, "Renamed '$oldName' to '$newName'")
        }

        override fun deletePath(path: String): Boolean {
            deleted += path
            return true
        }

        override suspend fun composeIcons(moduleName: String?): List<UiIconEntry> = composeCatalogue

        private val bundled = UiIconRepo(
            id = "bundled", displayName = "Material", license = "Apache-2.0", attribution = null,
            requiresNetwork = false, loaded = true, iconCount = 3,
        )
        private val remote = UiIconRepo(
            id = "remote", displayName = "Material (all)", license = "Apache-2.0", attribution = null,
            requiresNetwork = true, loaded = false, iconCount = 0,
        )

        private val catalogue = listOf(
            entry("home", listOf("house")), entry("home_work", emptyList()), entry("settings", listOf("gear")),
        )

        override fun iconRepositories(): List<UiIconRepo> =
            listOf(bundled, if (loaded) remote.copy(loaded = true, iconCount = 2) else remote)

        override suspend fun loadRepository(repoId: String): UiIconLoadResult {
            loaded = true
            return UiIconLoadResult(ok = true, iconCount = 2)
        }

        /** Replaces the three-icon catalogue, for the cases about a repository of realistic size. */
        var largeCatalogue: List<UiIconEntry>? = null

        override suspend fun searchIcons(repoId: String, query: String, limit: Int): List<UiIconEntry> {
            if (repoId == "remote" && !loaded) return emptyList()
            val q = query.trim().lowercase()
            val source = largeCatalogue ?: catalogue
            return source.filter { q.isEmpty() || it.name.contains(q) || it.keywordsMatch(q) }.take(limit)
        }

        override suspend fun iconArtwork(repoId: String, name: String, variant: UiIconVariant): UiIconArtwork {
            artworkRequests += "$repoId:$name:${variant.style}:${variant.filled}"
            return UiIconArtwork(vector(), warnings = if (name == "settings") listOf("lossy") else emptyList())
        }

        override fun existingResource(target: UiIconTarget, resType: String, name: String): String? = existing

        override fun importTargets(): List<UiIconTarget> = listOf(
            UiIconTarget("app", "main", "/p/app/src/main/res", isDefault = true),
            UiIconTarget("app", "debug", "/p/app/src/debug/res"),
            UiIconTarget("core", "main", "/p/core/src/main/res"),
        )

        override suspend fun projectIcons(moduleName: String?): List<UiResourceIcon> = listOf(
            UiResourceIcon(
                "app", "drawable", "ic_existing",
                listOf(
                    UiResourceConfig("", "/p/app/src/main/res/drawable/ic_existing.xml", isRaster = false),
                    UiResourceConfig("night", "/p/app/src/main/res/drawable-night/ic_existing.xml", isRaster = false),
                ),
            ),
            UiResourceIcon(
                "app", "mipmap", "logo",
                listOf(UiResourceConfig("hdpi", "/p/app/src/main/res/mipmap-hdpi/logo.png", isRaster = true)),
            ),
        )

        override suspend fun resourceArtwork(path: String): UiIconArtwork {
            artworkRequests += "res:$path"
            return UiIconArtwork(vector())
        }

        override suspend fun resourceBytes(path: String): ByteArray {
            artworkRequests += "raster:$path"
            return byteArrayOf(1, 2, 3)
        }

        override suspend fun importIcon(
            repoId: String,
            name: String,
            variant: UiIconVariant,
            request: UiIconImport,
        ): UiIconImportResult {
            imports += request
            importedAs += "$repoId:$name:${variant.style}:${variant.filled}"
            if (request.name in taken) return UiIconImportResult(ok = false, conflictPath = "/p/${request.name}.xml")
            return importResult
        }

        private fun UiIconEntry.keywordsMatch(q: String) = displayName.lowercase().contains(q)

        fun entry(name: String, keywords: List<String>) = UiIconEntry(
            repoId = "bundled",
            name = name,
            displayName = name.replaceFirstChar { it.uppercaseChar() },
            styles = listOf("outlined"),
            supportsFill = true,
        )

        private fun vector() = UiDrawable.Vector(
            widthDp = 24f, heightDp = 24f, viewportWidth = 24f, viewportHeight = 24f, rootAlpha = 1f,
            nodes = listOf(
                UiVectorPath("M0,0h24v24H0z", 0xFF000000L, null, 0f, 1f, 1f),
            ),
        )
    }

    /** Runs the state's launches on the caller's thread so a test can assert without polling. */
    private fun <T> withState(
        backend: FakeIcons = FakeIcons(),
        initialResDir: String? = null,
        body: suspend (IconManagerState, FakeIcons) -> T,
    ): T = runBlocking {
        val scope = CoroutineScope(coroutineContext + Dispatchers.Unconfined)
        val state = IconManagerState(backend, scope, initialResDir)
        body(state, backend)
    }

    private fun target(path: String, compose: Boolean = false, insideAttribute: Boolean = false) =
        UiInsertionTarget(path, composeContext = compose, insideXmlAttributeValue = insideAttribute)

    @Test
    fun itOpensOnTheLibraryWithTheOfflineRepositorySelected() {
        withState { state, _ ->
            assertEquals(IconTab.Library, state.tab)
            assertEquals("bundled", state.selectedRepoId)
            assertEquals(3, state.results.size, "the offline repository lists immediately")
            assertEquals("/p/app/src/main/res", state.target?.resDirPath, "the default target is preselected")
        }
    }

    /** The whole repository is browsable, not the first few hundred of it. */
    @Test
    fun aLargeRepositoryListsEveryIcon() {
        val backend = FakeIcons().apply {
            largeCatalogue = List(4271) { entry("icon_$it", emptyList()) }
        }
        withState(backend) { state, _ ->
            assertEquals(4271, state.results.size)
        }
    }

    @Test
    fun aPreselectedResDirOpensTheProjectSectionOnThatTarget() {
        withState(initialResDir = "/p/core/src/main/res") { state, _ ->
            assertEquals(IconTab.Project, state.tab, "a file-tree entry is about the project's own resources")
            assertEquals("core", state.target?.moduleName)
        }
    }

    @Test
    fun anUnknownResDirFallsBackToTheDefaultTarget() {
        withState(initialResDir = "/somewhere/else/res") { state, _ ->
            assertEquals("/p/app/src/main/res", state.target?.resDirPath)
        }
    }

    @Test
    fun searchIsDebouncedAndThenFilters() {
        withState { state, _ ->
            state.updateQuery("home")
            assertEquals(3, state.results.size, "the previous results stay while the debounce is pending")
            delay(400)
            assertEquals(listOf("home", "home_work"), state.results.map { it.name })
        }
    }

    @Test
    fun switchingToTheProjectSectionLoadsTheCatalogue() {
        withState { state, _ ->
            state.selectTab(IconTab.Project)
            assertEquals(listOf("ic_existing", "logo"), state.projectIcons.map { it.name })
        }
    }

    @Test
    fun theProjectSectionFiltersLocallyWithoutAnotherBackendCall() {
        withState { state, _ ->
            state.selectTab(IconTab.Project)
            state.updateQuery("logo")
            assertEquals(listOf("logo"), state.filteredProjectIcons().map { it.name })
        }
    }

    @Test
    fun selectingANetworkRepositoryDownloadsIt() {
        withState { state, backend ->
            assertFalse(backend.loaded, "nothing downloads just by opening the screen")
            state.selectRepo("remote")
            assertTrue(backend.loaded, "picking the set is the request to download it")
            assertNull(state.loadingRepoId, "the spinner clears")
            assertTrue(assertNotNull(state.selectedRepo()).loaded)
            assertEquals(3, state.results.size, "the catalogue lists once it is loaded")
        }
    }

    @Test
    fun theFirstSearchDownloadsTheNetworkSetAndCountsItsMatches() {
        withState { state, backend ->
            state.updateQuery("home")
            delay(400)
            assertTrue(backend.loaded, "the first search fetched the network set in the background")
            assertEquals("bundled", state.selectedRepoId, "without moving off the set being browsed")
            assertEquals(2, state.otherRepoMatches["remote"], "its matches label its chip")

            state.updateQuery("")
            delay(400)
            assertTrue(state.otherRepoMatches.isEmpty(), "no query, no match counts")
        }
    }

    @Test
    fun aQueryCountsMatchesInEverySection() {
        withState { state, backend ->
            backend.composeCatalogue = listOf(UiIconEntry("compose-icons", "Home", "Home", styles = listOf("filled")))
            assertNull(state.matchesIn(IconTab.Project), "no counts without a query")
            state.updateQuery("logo")
            delay(400)
            assertEquals(1, state.matchesIn(IconTab.Project), "the project catalogue loaded for the count")
            assertEquals(0, state.matchesIn(IconTab.Library))
            assertEquals(0, state.matchesIn(IconTab.Compose))

            state.updateQuery("home")
            delay(400)
            assertEquals(2, state.matchesIn(IconTab.Library))
            assertEquals(1, state.matchesIn(IconTab.Compose))
        }
    }

    @Test
    fun artworkIsFetchedOncePerIconAndVariantAndThenCached() {
        withState { state, backend ->
            state.ensureRepoArtwork("bundled", "home")
            state.ensureRepoArtwork("bundled", "home")
            assertEquals(listOf("bundled:home:outlined:false"), backend.artworkRequests)
            assertNotNull(state.artworkFor(state.repoKey("bundled", "home")))

            // A different variant is a different key, so it is fetched (once) as well.
            state.selectVariant(UiIconVariant(filled = true))
            state.ensureRepoArtwork("bundled", "home")
            state.ensureRepoArtwork("bundled", "home")
            assertEquals(
                listOf("bundled:home:outlined:false", "bundled:home:outlined:true"),
                backend.artworkRequests,
            )
        }
    }

    @Test
    fun aRasterResourceLoadsItsBytesRatherThanADrawable() {
        withState { state, backend ->
            val raster = UiResourceConfig("hdpi", "/p/app/src/main/res/mipmap-hdpi/logo.png", isRaster = true)
            state.ensureResourceArtwork(raster)
            assertEquals(listOf("raster:${raster.path}"), backend.artworkRequests)
            assertNotNull(state.rasterFor(raster.path))
            assertNull(state.artworkFor("res:${raster.path}"), "a raster has no vector model")
        }
    }

    @Test
    fun selectingAnIconSuggestsAResourceNameAndCarriesItsWarnings() {
        withState { state, _ ->
            val entry = state.results.first { it.name == "settings" }
            state.ensureRepoArtwork("bundled", "settings")
            state.select(IconSelection.FromRepo(entry))

            assertEquals("ic_settings", state.resourceName)
            assertEquals("drawable", state.resType)
            assertEquals(listOf("lossy"), state.warnings, "a lossy conversion is surfaced on selection")
        }
    }

    @Test
    fun selectingAProjectIconKeepsItsOwnNameAndFolder() {
        withState { state, _ ->
            state.selectTab(IconTab.Project)
            val icon = state.projectIcons.first { it.name == "logo" }
            state.select(IconSelection.FromProject(icon, icon.configurations.first()))
            assertEquals("logo", state.resourceName)
            assertEquals("mipmap", state.resType, "the folder follows the resource it came from")
        }
    }

    @Test
    fun aResourceNameIsSanitisedAsItIsTyped() {
        withState { state, _ ->
            state.updateResourceName("My Icon-2!")
            assertEquals("my_icon2", state.resourceName)
        }
    }

    @Test
    fun theImportRequestCarriesTheFormExactly() {
        withState { state, backend ->
            state.select(IconSelection.FromRepo(state.results.first { it.name == "home" }))
            state.updateResourceName("ic_house")
            state.updateSize(48)
            state.updateTint(0xFFFF0000L)
            state.updateResType("mipmap")
            state.selectTarget(state.targets.first { it.sourceSetName == "debug" })
            state.import()

            val request = assertNotNull(backend.imports.singleOrNull())
            assertEquals("ic_house", request.name)
            assertEquals(48f, request.sizeDp)
            assertEquals(0xFFFF0000L, request.colorArgb)
            assertEquals("mipmap", request.resType)
            assertEquals("/p/app/src/debug/res", request.target.resDirPath)
            assertFalse(request.overwrite, "the first attempt never overwrites")
        }
    }

    @Test
    fun sizeIsClampedToSomethingDrawable() {
        withState { state, _ ->
            state.updateSize(0)
            assertEquals(8, state.sizeDp)
            state.updateSize(9999)
            assertEquals(512, state.sizeDp)
        }
    }

    @Test
    fun aConflictIsSurfacedAndOnlyTheConfirmedRetryOverwrites() {
        withState { state, backend ->
            backend.importResult = UiIconImportResult(
                ok = false,
                conflictPath = "/p/app/src/main/res/drawable/ic_home.xml",
                message = "exists",
            )
            state.select(IconSelection.FromRepo(state.results.first { it.name == "home" }))
            state.import()

            assertEquals("/p/app/src/main/res/drawable/ic_home.xml", state.conflictPath)
            assertNull(state.message, "a conflict is a dialog, not an error line")
            assertFalse(backend.imports.single().overwrite)

            backend.importResult = UiIconImportResult(ok = true, path = "/p/app/src/main/res/drawable/ic_home.xml")
            state.import(replace = true)
            assertTrue(backend.imports.last().overwrite, "the confirmed retry replaces")
            assertNull(state.conflictPath, "the dialog closes")
        }
    }

    @Test
    fun aFailedImportShowsItsReason() {
        withState { state, backend ->
            backend.importResult = UiIconImportResult(ok = false, message = "disk full")
            state.select(IconSelection.FromRepo(state.results.first()))
            state.import()
            assertEquals("disk full", state.message)
            assertNull(state.conflictPath)
        }
    }

    // --- language-aware references ---------------------------------------------------------------------

    @Test
    fun aLibraryIconIsReferencedAsTheResourceItWillBecome() {
        withState { state, _ ->
            state.select(IconSelection.FromRepo(state.results.first { it.name == "home" }))
            val ref = assertNotNull(state.selectedRef()) as UiIconRef.Resource
            assertEquals("drawable", ref.resType)
            assertEquals("ic_home", ref.name, "the form's name is what the reference will point at")
            assertEquals("@drawable/ic_home", state.resourceReference())
        }
    }

    @Test
    fun theReferenceFormFollowsTheTargetLanguage() {
        withState { state, _ ->
            state.select(IconSelection.FromRepo(state.results.first { it.name == "home" }))
            assertEquals("R.drawable.ic_home", state.referenceFor(target("Main.kt")))
            assertEquals("R.drawable.ic_home", state.referenceFor(target("Main.java")))
            assertEquals("@drawable/ic_home", state.referenceFor(target("layout.xml")))
            assertEquals("@drawable/ic_home", state.referenceFor(null), "with no editor tab, the XML form")
        }
    }

    @Test
    fun theSnippetFollowsTheTargetLanguageAndComposeContext() {
        withState { state, _ ->
            state.select(IconSelection.FromRepo(state.results.first { it.name == "home" }))
            assertEquals(
                "Icon(painterResource(R.drawable.ic_home), contentDescription = null)",
                state.snippetFor(target("Main.kt", compose = true)),
            )
            assertEquals("R.drawable.ic_home", state.snippetFor(target("Main.kt")))
            assertEquals("R.drawable.ic_home", state.snippetFor(target("Main.java")))
            assertEquals(
                """android:src="@drawable/ic_home"""",
                state.snippetFor(target("layout.xml")),
                "outside an attribute value the whole attribute is needed",
            )
            assertEquals(
                "@drawable/ic_home",
                state.snippetFor(target("layout.xml", insideAttribute = true)),
                "between the quotes only the value belongs",
            )
        }
    }

    @Test
    fun aComposeIconIsReferencedByItsPropertyAndOnlyFromKotlin() {
        withState { state, _ ->
            val entry = UiIconEntry("compose-icons", "ShoppingCart", "Shopping cart", styles = listOf("outlined"))
            state.select(IconSelection.FromCompose(entry))

            val ref = assertNotNull(state.selectedRef()) as UiIconRef.ComposeIcon
            assertEquals("ShoppingCart", ref.property)
            assertEquals("outlined", ref.style)

            assertEquals("Icons.Outlined.ShoppingCart", state.referenceFor(target("Main.kt")))
            assertEquals(
                "Icon(Icons.Outlined.ShoppingCart, contentDescription = null)",
                state.snippetFor(target("Main.kt")),
            )
            assertNull(state.referenceFor(target("layout.xml")), "a Compose icon has no XML form")
            assertNull(state.referenceFor(target("Main.java")), "nor a Java one")
            assertNull(state.resourceReference(), "a Compose icon is not a resource")
        }
    }

    @Test
    fun theFilledStyleSelectsTheFilledComposeProperty() {
        withState { state, _ ->
            state.selectVariant(UiIconVariant(style = "filled"))
            state.select(
                IconSelection.FromCompose(UiIconEntry("compose-icons", "Star", "Star", styles = listOf("filled"))),
            )
            assertEquals("Icons.Filled.Star", state.referenceFor(target("Main.kt")))
        }
    }

    @Test
    fun aProjectIconIsReferencedByItsOwnFolderAndName() {
        withState { state, _ ->
            state.selectTab(IconTab.Project)
            val icon = state.projectIcons.first { it.name == "logo" }
            state.select(IconSelection.FromProject(icon, icon.configurations.first()))
            assertEquals("@mipmap/logo", state.resourceReference())
            assertEquals("R.mipmap.logo", state.referenceFor(target("Main.java")))
        }
    }

    @Test
    fun insertingALibraryIconImportsItFirstSoTheReferenceResolves() {
        withState { state, backend ->
            state.select(IconSelection.FromRepo(state.results.first { it.name == "home" }))
            var handed: UiIconRef? = null
            state.prepareInsertion { handed = it }

            assertEquals(1, backend.imports.size, "the icon is written before it is referenced")
            assertEquals("ic_home", (assertNotNull(handed) as UiIconRef.Resource).name)
        }
    }

    @Test
    fun insertingAnIconThatIsAlreadyInTheProjectDoesNotWriteItAgain() {
        withState { state, backend ->
            backend.existing = "/p/app/src/main/res/drawable/ic_home.xml"
            state.select(IconSelection.FromRepo(state.results.first { it.name == "home" }))
            var handed: UiIconRef? = null
            state.prepareInsertion { handed = it }

            assertTrue(backend.imports.isEmpty(), "nothing is rewritten")
            assertNotNull(handed)
        }
    }

    @Test
    fun insertingAProjectOrComposeIconNeedsNoImportStep() {
        withState { state, backend ->
            state.selectTab(IconTab.Project)
            val icon = state.projectIcons.first { it.name == "logo" }
            state.select(IconSelection.FromProject(icon, icon.configurations.first()))
            var handed: UiIconRef? = null
            state.prepareInsertion { handed = it }
            assertTrue(backend.imports.isEmpty())
            assertNotNull(handed)
        }
    }

    @Test
    fun addingAComposeIconAsADrawableTranslatesTheStyleToTheRepositoryNaming() {
        withState { state, backend ->
            // The Compose libraries treat "filled" as a family; the repositories treat it as a fill flag on
            // the outlined artwork. Getting this wrong would silently write the outlined icon.
            state.selectVariant(UiIconVariant(style = "filled"))
            state.select(
                IconSelection.FromCompose(
                    UiIconEntry("compose-icons", "ShoppingCart", "Shopping cart", styles = listOf("filled")),
                ),
            )
            state.import()

            val request = assertNotNull(backend.imports.singleOrNull())
            assertEquals("ic_shopping_cart", request.name, "the resource name is snake_case")
            assertEquals(
                listOf("bundled:shopping_cart:outlined:true"),
                backend.importedAs,
                "the artwork is taken from a loaded repository under its own naming",
            )
        }
    }

    @Test
    fun addingAComposeIconUsesItsFamilyWhenTheRepositoryHasOne() {
        withState { state, backend ->
            state.selectVariant(UiIconVariant(style = "rounded"))
            state.select(
                IconSelection.FromCompose(
                    UiIconEntry("compose-icons", "Home", "Home", styles = listOf("rounded")),
                ),
            )
            state.import()
            assertEquals(listOf("bundled:home:rounded:false"), backend.importedAs)
        }
    }

    @Test
    fun switchingSectionClearsTheSelection() {
        withState { state, _ ->
            state.select(IconSelection.FromRepo(state.results.first()))
            assertNotNull(state.selection)
            state.selectTab(IconTab.Project)
            assertNull(state.selection)
        }
    }

    @Test
    fun theStyleToolbarDoesNotMoveWhenAnIconLacksTheStyle() {
        withState { state, _ ->
            state.selectVariant(UiIconVariant(style = "rounded", filled = true))
            val outlinedOnly = UiIconEntry("bundled", "home", "Home", styles = listOf("outlined"), supportsFill = false)
            state.select(IconSelection.FromRepo(outlinedOnly))
            assertEquals("rounded", state.variant.style, "selecting an icon leaves the grid's style alone")
            assertEquals(UiIconVariant("outlined", filled = false), state.variantFor(outlinedOnly))
            assertEquals("bundled:home:outlined:false", state.keyOf(IconSelection.FromRepo(outlinedOnly)))
        }
    }

    @Test
    fun aBatchAddsEveryTickedIconUnderItsOwnNameAndSkipsTakenNames() {
        withState { state, backend ->
            backend.taken += "ic_settings"
            val home = IconSelection.FromRepo(state.results.first { it.name == "home" })
            val settings = IconSelection.FromRepo(state.results.first { it.name == "settings" })
            state.select(home)
            state.toggleChecked(home)
            assertNull(state.selection, "ticking replaces the single-icon sheet")
            state.toggleChecked(settings)
            assertTrue(state.isChecked(settings))
            state.updateSize(32)

            state.importChecked()

            assertEquals(listOf("ic_home", "ic_settings"), backend.imports.map { it.name })
            assertTrue(backend.imports.all { it.sizeDp == 32f }, "the shared settings apply to each")
            assertEquals(BatchOutcome(added = 1, existing = 1, failed = emptyList()), state.batchOutcome)
            assertTrue(state.checked.isEmpty(), "the batch clears once added")
        }
    }

    @Test
    fun tickingTwiceUnticksAndProjectIconsAreNotBatched() {
        withState { state, _ ->
            val home = IconSelection.FromRepo(state.results.first { it.name == "home" })
            state.toggleChecked(home)
            state.toggleChecked(home)
            assertTrue(state.checked.isEmpty())

            state.selectTab(IconTab.Project)
            val icon = state.projectIcons.first()
            state.toggleChecked(IconSelection.FromProject(icon, icon.configurations.first()))
            assertTrue(state.checked.isEmpty(), "a project icon is already in the project")
        }
    }

    @Test
    fun anyIconCanBecomeTheAppIconInTheStyleItIsShownIn() {
        withState { state, _ ->
            state.selectVariant(UiIconVariant(style = "outlined", filled = true))
            val home = state.results.first { it.name == "home" }
            assertEquals(
                UiIconLayer.RepoIcon("bundled", "home", UiIconVariant("outlined", filled = true)),
                state.appIconLayerFor(IconSelection.FromRepo(home)),
                "the picked style travels with it",
            )

            val icon = UiResourceIcon(
                "app", "drawable", "ic_existing",
                listOf(UiResourceConfig("", "/p/app/src/main/res/drawable/ic_existing.xml", isRaster = false)),
            )
            assertEquals(
                UiIconLayer.Resource("/p/app/src/main/res/drawable/ic_existing.xml"),
                state.appIconLayerFor(IconSelection.FromProject(icon, icon.configurations.first())),
            )

            state.selectVariant(UiIconVariant(style = "filled"))
            val star = UiIconEntry("compose-icons", "ShoppingCart", "Shopping cart", styles = listOf("filled"))
            assertEquals(
                UiIconLayer.RepoIcon("bundled", "shopping_cart", UiIconVariant("outlined", filled = true)),
                state.appIconLayerFor(IconSelection.FromCompose(star)),
                "a Compose icon is taken from a repository under its naming",
            )
        }
    }

    @Test
    fun aProjectIconShowsItsUsagesAndCanBeRenamedOrDeleted() {
        withState { state, backend ->
            state.selectTab(IconTab.Project)
            val icon = state.projectIcons.first { it.name == "ic_existing" }
            state.select(IconSelection.FromProject(icon, icon.configurations.first()))
            assertEquals(1, state.usages?.size, "its usages are looked up on selection")

            var renamed: Boolean? = null
            var moved: Map<String, String> = emptyMap()
            state.renameSelected("ic_new") { ok, paths -> renamed = ok; moved = paths }
            assertEquals(true, renamed)
            assertEquals(
                mapOf(
                    "/p/app/src/main/res/drawable/ic_existing.xml" to "/p/app/src/main/res/drawable/ic_new.xml",
                    "/p/app/src/main/res/drawable-night/ic_existing.xml" to "/p/app/src/main/res/drawable-night/ic_new.xml",
                ),
                moved,
                "every configuration's file is reported where it went, for open tabs to follow",
            )
            assertEquals(listOf("drawable/ic_existing->ic_new"), backend.renames)
            assertNull(state.selection, "the renamed icon is a different icon now")

            state.select(IconSelection.FromProject(icon, icon.configurations.first()))
            state.deleteSelected()
            assertEquals(icon.configurations.map { it.path }, backend.deleted, "every configuration goes")
            assertNull(state.selection)
        }
    }

    @Test
    fun aWrittenFileResolvesAgainstTheResDirectory() {
        assertEquals(
            "/p/app/src/main/res/mipmap-hdpi/ic_launcher.png",
            resolveAgainst("/p/app/src/main/res", "mipmap-hdpi/ic_launcher.png"),
        )
        assertEquals(
            "/p/app/src/main/ic_launcher-playstore.png",
            resolveAgainst("/p/app/src/main/res/", "../ic_launcher-playstore.png"),
            "the Play Store image sits beside res/",
        )
        assertEquals(
            "C:\\p\\app\\src\\main\\res\\drawable\\a.xml",
            resolveAgainst("C:\\p\\app\\src\\main\\res", "drawable/a.xml"),
        )
    }
}
