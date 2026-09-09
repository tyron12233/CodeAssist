package dev.ide.core

import dev.ide.testkit.withTempDir
import dev.ide.ui.backend.UiAppIconSpec
import dev.ide.ui.backend.UiDrawable
import dev.ide.ui.backend.UiIconLayer
import dev.ide.ui.backend.UiRasterFile
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The app-icon studio's backend against a real Android project: reading the icon a module already declares,
 * planning a change, composing the layers for the preview, and committing the whole file set plus the manifest
 * edit. The rasters are supplied here the way the UI supplies them after rendering.
 */
class AppIconStudioTest {

    @Test
    fun itReadsTheLauncherIconTheModuleAlreadyDeclares() {
        withTempDir("appicon-current") { dir ->
            IdeServices.bootstrapDemo(dir).use { ide ->
                runBlocking {
                    val state = assertNotNull(IdeServicesBackend(ide).icons.launcherIcon())
                    assertTrue(state.moduleName.isNotEmpty())
                    // The Android sample ships an adaptive icon, which resolves to a renderable drawable.
                    assertNotNull(state.iconRef, "the sample manifest declares an icon")
                    assertTrue(
                        state.current != null || state.currentBytes != null,
                        "the declared icon should resolve to something previewable",
                    )
                }
            }
        }
    }

    @Test
    fun aPlanListsEveryFileTheChangeWouldWrite() {
        withTempDir("appicon-plan") { dir ->
            IdeServices.bootstrapDemo(dir).use { ide ->
                val icons = IdeServicesBackend(ide).icons
                runBlocking {
                    val plan = assertNotNull(icons.planAppIcon(spec(icons)))

                    assertTrue("mipmap-anydpi-v26/ic_launcher.xml" in plan.files, plan.files.toString())
                    assertTrue("mipmap-anydpi-v26/ic_launcher_round.xml" in plan.files)
                    assertTrue("drawable/ic_launcher_foreground.xml" in plan.files)
                    assertTrue("values/ic_launcher_background.xml" in plan.files)
                    assertEquals(11, plan.rasters.size, "5 densities x 2 shapes, plus the store image")
                    assertTrue(plan.rasters.any { it.pixels == 512 && it.opaque })
                    assertTrue(plan.resDirPath.replace('\\', '/').endsWith("src/main/res"), plan.resDirPath)
                    assertEquals(
                        "android:icon=@mipmap/ic_launcher, android:roundIcon=@mipmap/ic_launcher_round",
                        plan.manifestChange,
                    )
                }
            }
        }
    }

    @Test
    fun thePreviewComposesEachLayerIntoTheIconBox() {
        withTempDir("appicon-preview") { dir ->
            IdeServices.bootstrapDemo(dir).use { ide ->
                val icons = IdeServicesBackend(ide).icons
                runBlocking {
                    val preview = assertNotNull(icons.previewAppIcon(spec(icons)))

                    val background = assertNotNull(preview.background)
                    assertTrue(background is UiDrawable.SolidColor, "a flat background arrives as a colour")
                    assertEquals(0xFF2196F3L, background.color)

                    val foreground = assertNotNull(preview.foreground) as UiDrawable.Vector
                    assertEquals(108f, foreground.viewportWidth, "layers are composed in the adaptive icon box")
                    assertEquals(108f, foreground.viewportHeight)
                    assertNull(preview.monochrome, "no themed layer was asked for")
                }
            }
        }
    }

    @Test
    fun aThemedLayerIsComposedWhenItIsRequested() {
        withTempDir("appicon-mono") { dir ->
            IdeServices.bootstrapDemo(dir).use { ide ->
                val icons = IdeServicesBackend(ide).icons
                runBlocking {
                    val repo = icons.iconRepositories().first { !it.requiresNetwork }
                    val base = spec(icons)
                    val preview = assertNotNull(
                        icons.previewAppIcon(
                            base.copy(monochrome = UiIconLayer.RepoIcon(repo.id, "home", tintArgb = 0xFF000000L)),
                        ),
                    )
                    assertNotNull(preview.monochrome)
                }
            }
        }
    }

    @Test
    fun applyingWritesTheLayersTheAdaptiveXmlTheRastersAndTheManifest() {
        withTempDir("appicon-apply") { dir ->
            IdeServices.bootstrapDemo(dir).use { ide ->
                val icons = IdeServicesBackend(ide).icons
                runBlocking {
                    val spec = spec(icons)
                    val plan = assertNotNull(icons.planAppIcon(spec))
                    val res = Paths.get(plan.resDirPath)
                    // The UI renders these; here they stand in as recognisable bytes.
                    val rasters = plan.rasters.map { UiRasterFile(it.relativePath, "png:${it.pixels}".encodeToByteArray()) }

                    val result = icons.applyAppIcon(spec, rasters)
                    assertTrue(result.ok, result.message)

                    val adaptive = Files.readString(res.resolve("mipmap-anydpi-v26/ic_launcher.xml"))
                    assertTrue(adaptive.contains("<adaptive-icon"), adaptive)
                    assertTrue(adaptive.contains("@color/ic_launcher_background"), adaptive)
                    assertTrue(adaptive.contains("@drawable/ic_launcher_foreground"), adaptive)

                    val foreground = Files.readString(res.resolve("drawable/ic_launcher_foreground.xml"))
                    assertTrue(foreground.contains("android:viewportWidth=\"108\""), foreground)

                    val colour = Files.readString(res.resolve("values/ic_launcher_background.xml"))
                    assertTrue(colour.contains("#2196F3"), colour)

                    // Every density bucket got the bytes we handed over, at the size the plan asked for.
                    assertEquals("png:48", Files.readString(res.resolve("mipmap-mdpi/ic_launcher.png")))
                    assertEquals("png:192", Files.readString(res.resolve("mipmap-xxxhdpi/ic_launcher.png")))
                    assertEquals("png:48", Files.readString(res.resolve("mipmap-mdpi/ic_launcher_round.png")))

                    // The store image lands beside res/, never inside it.
                    val store = res.parent.resolve("ic_launcher-playstore.png")
                    assertTrue(Files.exists(store), "expected $store")
                    assertEquals("png:512", Files.readString(store))

                    val manifest = manifestOf(res)
                    assertTrue(manifest.contains("""android:icon="@mipmap/ic_launcher""""), manifest)
                    assertTrue(manifest.contains("""android:roundIcon="@mipmap/ic_launcher_round""""), manifest)
                }
            }
        }
    }

    @Test
    fun aRasterWithNoSuppliedBytesIsSkippedAndReported() {
        withTempDir("appicon-missing") { dir ->
            IdeServices.bootstrapDemo(dir).use { ide ->
                val icons = IdeServicesBackend(ide).icons
                runBlocking {
                    val spec = spec(icons)
                    val plan = assertNotNull(icons.planAppIcon(spec))
                    val res = Paths.get(plan.resDirPath)
                    // Hand over everything except the store image.
                    val partial = plan.rasters.filterNot { it.opaque }
                        .map { UiRasterFile(it.relativePath, byteArrayOf(1)) }

                    val result = icons.applyAppIcon(spec, partial)
                    assertTrue(result.ok, "the rest of the change still lands")
                    assertTrue(
                        result.warnings.any { it.contains("playstore") },
                        "the skipped file is named: ${result.warnings}",
                    )
                    assertFalse(Files.exists(res.parent.resolve("ic_launcher-playstore.png")))
                }
            }
        }
    }

    @Test
    fun turningOffTheRastersWritesAVectorFallbackInstead() {
        withTempDir("appicon-novec") { dir ->
            IdeServices.bootstrapDemo(dir).use { ide ->
                val icons = IdeServicesBackend(ide).icons
                runBlocking {
                    val spec = spec(icons).copy(generateRasters = false, generatePlayStoreIcon = false)
                    val plan = assertNotNull(icons.planAppIcon(spec))
                    assertTrue(plan.rasters.isEmpty())
                    assertTrue("mipmap/ic_launcher.xml" in plan.files, plan.files.toString())

                    assertTrue(icons.applyAppIcon(spec, emptyList()).ok)
                    val legacy = Files.readString(Paths.get(plan.resDirPath).resolve("mipmap/ic_launcher.xml"))
                    assertTrue(legacy.contains("<layer-list"), legacy)
                }
            }
        }
    }

    @Test
    fun replacingAnExistingIconIsReportedBeforeItHappens() {
        withTempDir("appicon-replace") { dir ->
            IdeServices.bootstrapDemo(dir).use { ide ->
                val icons = IdeServicesBackend(ide).icons
                runBlocking {
                    val spec = spec(icons)
                    val first = assertNotNull(icons.planAppIcon(spec))
                    icons.applyAppIcon(spec, first.rasters.map { UiRasterFile(it.relativePath, byteArrayOf(1)) })

                    val second = assertNotNull(icons.planAppIcon(spec))
                    assertEquals(
                        second.files.sorted(),
                        second.replacing.sorted(),
                        "a second pass over the same spec replaces everything it wrote",
                    )
                }
            }
        }
    }

    @Test
    fun aCustomNameDoesNotCollideWithTheDefaultIcon() {
        withTempDir("appicon-name") { dir ->
            IdeServices.bootstrapDemo(dir).use { ide ->
                val icons = IdeServicesBackend(ide).icons
                runBlocking {
                    val spec = spec(icons).copy(name = "ic_brand", generatePlayStoreIcon = false)
                    val plan = assertNotNull(icons.planAppIcon(spec))
                    assertTrue(plan.files.all { it.contains("ic_brand") }, plan.files.toString())
                    assertTrue(icons.applyAppIcon(spec, plan.rasters.map { UiRasterFile(it.relativePath, byteArrayOf(1)) }).ok)

                    val manifest = manifestOf(Paths.get(plan.resDirPath))
                    assertTrue(manifest.contains("""android:icon="@mipmap/ic_brand""""), manifest)
                }
            }
        }
    }

    @Test
    fun aSpecWithNoForegroundStillPlansButSaysWhatIsMissing() {
        withTempDir("appicon-nofg") { dir ->
            IdeServices.bootstrapDemo(dir).use { ide ->
                val icons = IdeServicesBackend(ide).icons
                runBlocking {
                    val state = assertNotNull(icons.launcherIcon())
                    val plan = assertNotNull(
                        icons.planAppIcon(UiAppIconSpec(moduleName = state.moduleName, foreground = UiIconLayer.None)),
                    )
                    assertTrue(plan.warnings.any { it.contains("No foreground") }, plan.warnings.toString())
                }
            }
        }
    }

    // --- helpers ---------------------------------------------------------------------------------------

    private suspend fun spec(icons: dev.ide.ui.backend.IconService): UiAppIconSpec {
        val module = assertNotNull(icons.launcherIcon()).moduleName
        val repo = icons.iconRepositories().first { !it.requiresNetwork }
        return UiAppIconSpec(
            moduleName = module,
            background = UiIconLayer.Color(0xFF2196F3L),
            foreground = UiIconLayer.RepoIcon(repo.id, "home"),
        )
    }

    /** The manifest for the module owning [resDir] (`src/main/res` sits beside `AndroidManifest.xml`). */
    private fun manifestOf(resDir: Path): String = Files.readString(resDir.parent.resolve("AndroidManifest.xml"))
}
