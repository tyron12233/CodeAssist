package dev.ide.android.spike

import android.content.Context
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.intellij.lang.LanguageASTFactory
import com.intellij.lang.LanguageParserDefinitions
import com.intellij.lang.xml.BackendXmlElementFactory
import com.intellij.lang.xml.BasicXmlElementFactory
import com.intellij.lang.xml.XMLLanguage
import com.intellij.lang.xml.XMLParserDefinition
import com.intellij.lang.xml.XmlASTFactory
import com.intellij.mock.MockApplication
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Disposer
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.create
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.diagnostics.impl.BaseDiagnosticsCollector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Discovery spike (not a regression test) — the XML-PSI counterpart of [KotlinCompilerArtSpikeTest]. It
 * stands up a [KotlinCoreEnvironment] on a real device, registers IntelliJ's XML PSI onto it (exactly the
 * `:intellij-psi-host` mechanism, proven green on the desktop JVM in `XmlPsiSpikeTest`), and parses an
 * `XmlFile`. Its job is to surface what ART's verifier/classloader can't handle in the newly-dexed XML PSI
 * jars (xml-parser / xml-psi / xml-psi-impl / xml-frontback-impl / regexp / concurrency / indexing): each
 * `LinkageError`/`VerifyError`/`NoClassDefFoundError`/`ExceptionInInitializerError` this throws is the input
 * to a targeted `ArtPatchPass` / a shipped `javax`/StAX type / an `artShims` class, after which it is re-run
 * until green — the same loop the Kotlin spike drove.
 *
 *     ./gradlew :ide-android:connectedDebugAndroidTest \
 *       -Pandroid.testInstrumentationRunnerArguments.class=dev.ide.android.spike.XmlPsiArtSpikeTest
 *     adb logcat -s XmlPsiArtSpike
 */
@RunWith(AndroidJUnit4::class)
class XmlPsiArtSpikeTest {

    @OptIn(CompilerConfiguration.Internals::class, org.jetbrains.kotlin.K1Deprecation::class)
    @Test
    fun xmlPsiParsesOnArt() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val work = File(ctx.filesDir, "xml-psi-art-spike").apply { deleteRecursively(); mkdirs() }

        // IntelliJ-core reads its extension-point descriptors from a real filesystem path; publish the
        // extracted resource home via `kotlinc.art.home` (the ASM PathUtil pass reads it) — same as Kotlin.
        val home = provisionKotlincHome(ctx, File(work, "kotlinc-home"))
        System.setProperty("kotlinc.art.home", home.absolutePath)
        Log.i(TAG, "kotlinc.art.home = $home")

        val disposable = Disposer.newDisposable("xml-psi-art-spike")
        try {
            val configuration = CompilerConfiguration.create(
                diagnosticsCollector = BaseDiagnosticsCollector.DoNothing,
                messageCollector = MessageCollector.NONE,
            ).apply { put(CommonConfigurationKeys.MODULE_NAME, "xml-psi-art-spike") }

            val env = KotlinCoreEnvironment.createForProduction(
                disposable, configuration, EnvironmentConfigFiles.JVM_CONFIG_FILES,
            )
            LanguageParserDefinitions.INSTANCE.addExplicitExtension(XMLLanguage.INSTANCE, XMLParserDefinition(), disposable)
            LanguageASTFactory.INSTANCE.addExplicitExtension(XMLLanguage.INSTANCE, XmlASTFactory(), disposable)
            (ApplicationManager.getApplication() as MockApplication)
                .registerService(BasicXmlElementFactory::class.java, BackendXmlElementFactory())

            val factory = PsiFileFactory.getInstance(env.project)
            val text = """
                <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content">
                    <TextView android:text="hello" />
                </LinearLayout>
            """.trimIndent()

            val file = factory.createFileFromText("layout.xml", XMLLanguage.INSTANCE, text) as XmlFile
            val root = file.rootTag ?: fail("no rootTag").let { return }
            assertEquals("LinearLayout", root.name)
            // Walk raw PSI children (the projection's approach) — avoids the XInclude/FileBasedIndex path.
            val child = PsiTreeUtil.getChildrenOfType(root, XmlTag::class.java)?.single()
                ?: fail("no child tag").let { return }
            assertEquals("TextView", child.name)
            val attr = child.getAttribute("android:text") ?: fail("no attr").let { return }
            assertEquals("hello", attr.value)
            assertTrue(PsiTreeUtil.findChildrenOfType(file, PsiErrorElement::class.java).isEmpty())

            // Error tolerance: malformed input still yields a whole-file tree + error elements, no throw.
            val broken = factory.createFileFromText(
                "broken.xml", XMLLanguage.INSTANCE, "<LinearLayout><TextView android:text=\"hi\"",
            ) as XmlFile
            assertTrue(PsiTreeUtil.findChildrenOfType(broken, PsiErrorElement::class.java).isNotEmpty())

            Log.i(TAG, "XML PSI parsed on ART: root=${root.name} child=${child.name} attr=${attr.value}")
        } catch (t: Throwable) {
            Log.e(TAG, "XML PSI failed to RUN on ART — add an ArtPatchPass / shipped type for this:", t)
            fail(
                "XML PSI failed on ART: ${t.javaClass.name}: ${t.message}\n" +
                    "Add a pass to dev.ide.build.kotlinc.ArtPatchPasses (or a shipped javax/StAX type / " +
                    "artShims class) targeting the class in this trace, then re-run.\n${t.stackTraceToString()}",
            )
        } finally {
            Disposer.dispose(disposable)
        }
    }

    /** Extract the kotlinc-resources.zip asset (the platform's non-class resources) into [home]. */
    private fun provisionKotlincHome(ctx: Context, home: File): File {
        home.deleteRecursively()
        home.mkdirs()
        val canonicalHome = home.canonicalPath + File.separator
        ctx.assets.open("kotlinc-resources.zip").use { input ->
            ZipInputStream(input).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val outFile = File(home, entry.name)
                    if (outFile.canonicalPath.startsWith(canonicalHome)) {
                        if (entry.isDirectory) outFile.mkdirs()
                        else { outFile.parentFile?.mkdirs(); outFile.outputStream().use { zis.copyTo(it) } }
                    }
                    entry = zis.nextEntry
                }
            }
        }
        return home
    }

    private companion object {
        const val TAG = "XmlPsiArtSpike"
    }
}
