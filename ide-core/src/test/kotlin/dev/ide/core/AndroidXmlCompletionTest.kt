package dev.ide.core

import dev.ide.android.support.metadata.AndroidSdkMetadata
import dev.ide.android.support.metadata.AttrsXmlParser
import dev.ide.android.support.resources.ResourceItem
import dev.ide.android.support.resources.ResourceRepository
import dev.ide.android.support.resources.ResourceType
import dev.ide.lang.completion.CompletionRequest
import dev.ide.lang.completion.CompletionTrigger
import dev.ide.lang.incremental.DocumentSnapshot
import dev.ide.lang.xml.completion.XmlCompletionService
import dev.ide.platform.ContentHash
import dev.ide.vfs.VirtualFile
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Proves the Android XML completion adapter wires the widget catalog + a resource repository into the
 * neutral [XmlCompletionService] contributor seam — the end-to-end path the editor exercises, minus the
 * SDK (the repository is hand-built so the test needs no Android SDK).
 */
class AndroidXmlCompletionTest {

    private val repo = ResourceRepository(
        listOf(
            ResourceItem(ResourceType.STRING, "app_name"),
            ResourceItem(ResourceType.STRING, "greeting"),
            ResourceItem(ResourceType.DRAWABLE, "ic_launcher"),
        )
    )
    private val service = XmlCompletionService(contributors = { listOf(AndroidXmlContributor(resourceNames = { type -> repo.names(type).toList() })) })

    // runTest returns a TestResult (not the lambda's value), so collect the labels into a local instead.
    private fun complete(src: String, path: String = "res/layout/a.xml", svc: XmlCompletionService = service): List<String> {
        var labels: List<String> = emptyList()
        runTest {
            val offset = src.indexOf('|')
            val text = src.removeRange(offset, offset + 1)
            labels = svc.complete(CompletionRequest(Doc(text, path), offset, CompletionTrigger.Explicit)).items.map { it.label }
        }
        return labels
    }

    @Test
    fun usesSdkMetadataWhenProvided() {
        val parsed = AttrsXmlParser.parse(
            """<resources>
                 <attr name="text" format="string"/>
                 <declare-styleable name="View"><attr name="id" format="reference"/></declare-styleable>
                 <declare-styleable name="TextView"><attr name="text"/></declare-styleable>
               </resources>"""
        )
        val sdk = AndroidSdkMetadata(34, parsed.attrs, parsed.styleables,
            mapOf("Button" to "TextView", "TextView" to "View"),
            listOf(AndroidSdkMetadata.WidgetInfo("Button", false)))
        val svc = XmlCompletionService(contributors = {
            listOf(AndroidXmlContributor(resourceNames = { type -> repo.names(type).toList() }, layout = { sdk }))
        })
        // <Button> inherits TextView.text and View.id from the SDK class hierarchy.
        val labels = complete("<Button android:|", svc = svc)
        assertTrue("android:text" in labels && "android:id" in labels, "got $labels")
    }

    @Test
    fun mergesCustomViewAttributes() {
        val custom = AttrsXmlParser.parse(
            """<resources><declare-styleable name="MyView"><attr name="customColor" format="color"/></declare-styleable></resources>"""
        )
        val customMeta = AndroidSdkMetadata(0, custom.attrs, custom.styleables, emptyMap(), emptyList(), attrPrefix = "app:")
        val svc = XmlCompletionService(contributors = {
            listOf(AndroidXmlContributor(resourceNames = { type -> repo.names(type).toList() }, customAttrs = { customMeta }))
        })
        assertTrue("app:customColor" in complete("<com.example.MyView app:|", svc = svc), "custom attr should appear")
    }

    @Test
    fun completesWidgetTags() {
        val labels = complete("<LinearLayout>\n  <T|\n</LinearLayout>")
        assertTrue("TextView" in labels && "TableLayout" in labels, "got $labels")
    }

    @Test
    fun completesCustomViewTagsFromLibrariesByFqnAndSimpleName() {
        val views = listOf(
            dev.ide.android.support.metadata.Widget("com.google.android.material.button.MaterialButton", false),
            dev.ide.android.support.metadata.Widget("androidx.constraintlayout.widget.ConstraintLayout", true),
        )
        val svc = XmlCompletionService(contributors = {
            listOf(AndroidXmlContributor(resourceNames = { type -> repo.names(type).toList() }, customViews = { views }))
        })
        // Typing the simple name matches the fully-qualified custom view (which is what gets inserted).
        assertTrue("com.google.android.material.button.MaterialButton" in complete("<Material|", svc = svc),
            "library custom view should be suggested by its simple name")
        // Framework widgets and custom views coexist.
        val all = complete("<C|", svc = svc)
        assertTrue("androidx.constraintlayout.widget.ConstraintLayout" in all, "got $all")
    }

    @Test
    fun completesAttributeNames() {
        val labels = complete("<TextView android:tex|")
        assertTrue("android:text" in labels, "got $labels")
    }

    @Test
    fun completesEnumAttributeValues() {
        val labels = complete("<View android:layout_width=\"|\"")
        assertTrue("match_parent" in labels && "wrap_content" in labels, "got $labels")
    }

    @Test
    fun completesResourceReferences() {
        val labels = complete("<TextView android:text=\"@string/|\"")
        assertTrue("@string/app_name" in labels && "@string/greeting" in labels, "got $labels")
        assertTrue("@string/ic_launcher" !in labels, "should not offer a drawable for a string attr: $labels")
    }

    @Test
    fun completesIdReferencesAndDeclaration() {
        val idRepo = ResourceRepository(listOf(ResourceItem(ResourceType.ID, "header"), ResourceItem(ResourceType.ID, "ok_button")))
        val svc = XmlCompletionService(contributors = {
            listOf(AndroidXmlContributor(resourceNames = { type -> idRepo.names(type).toList() }))
        })
        // layout_below references an id; the bundled SDK metadata maps it (RelativeLayout_Layout, reference→ID).
        val refs = complete("<RelativeLayout><Button android:layout_below=\"@id/|\"/></RelativeLayout>", svc = svc)
        assertTrue("@id/header" in refs && "@id/ok_button" in refs, "got $refs")
        // The `@+id/` declaration form is offered when declaring (e.g. typing `android:id="@+|"`).
        val decl = complete("<Button android:id=\"@+|\"/>", svc = svc)
        assertTrue("@+id/" in decl, "declaration form offered: $decl")
    }

    @Test
    fun completesManifestElements() {
        val path = "src/main/AndroidManifest.xml"
        val labels = complete("<manifest>\n  <app|\n</manifest>", path)
        assertTrue("application" in labels, "got $labels")
        // Layout widgets must NOT leak into the manifest.
        assertTrue("TextView" !in labels, "got $labels")
    }

    @Test
    fun completesManifestAttributesAndEnumValues() {
        val path = "src/main/AndroidManifest.xml"
        assertTrue("android:launchMode" in complete("<activity android:lau|", path))
        val launch = complete("<activity android:launchMode=\"|\"", path)
        assertTrue("singleTop" in launch && "standard" in launch, "got $launch")
        assertTrue("true" in complete("<activity android:exported=\"|\"", path), "boolean values")
    }

    private class Doc(override val text: CharSequence, path: String) : DocumentSnapshot {
        override val file: VirtualFile = FakeFile(path)
        override val version: Long = 1
        override fun length(): Int = text.length
    }

    private class FakeFile(override val path: String) : VirtualFile {
        override val name: String = path.substringAfterLast('/')
        override val isDirectory: Boolean = false
        override val exists: Boolean = true
        override val length: Long = 0
        override fun parent(): VirtualFile? = null
        override fun children(): List<VirtualFile> = emptyList()
        override fun contentHash(): ContentHash = ContentHash("")
        override fun readBytes(): ByteArray = ByteArray(0)
        override fun readText(): CharSequence = ""
    }
}
