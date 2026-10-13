package dev.ide.android.support.navigation

import dev.ide.android.support.resources.ResourceType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AndroidXmlTargetsTest {

    private val layout = "/p/app/src/main/res/layout/activity_main.xml"
    private val values = "/p/app/src/main/res/values/strings.xml"
    private val manifest = "/p/app/src/main/AndroidManifest.xml"

    /** The target at the `|` marker in [marked]. */
    private fun at(path: String, marked: String, ns: String? = "com.example.app"): XmlNavTarget? {
        val caret = marked.indexOf('|')
        return AndroidXmlTargets.at(path, marked.removeRange(caret, caret + 1), caret, ns)
    }

    private val ns = """xmlns:android="http://schemas.android.com/apk/res/android" xmlns:app="http://schemas.android.com/apk/res-auto" xmlns:tools="http://schemas.android.com/tools""""

    @Test
    fun resourceReferenceCoversJustTheName() {
        val text = """<LinearLayout $ns><TextView android:text="@string/app_n|ame"/></LinearLayout>"""
        val t = assertIs<XmlNavTarget.Resource>(at(layout, text))
        assertEquals(ResourceType.STRING, t.type)
        assertEquals("app_name", t.name)
        assertEquals(false, t.declaration)
        val plain = text.replace("|", "")
        assertEquals("app_name", plain.substring(t.range.first, t.range.last + 1))
    }

    @Test
    fun idOnAndroidIdIsADeclarationButAConstraintIsNot() {
        val decl = at(layout, """<FrameLayout $ns><View android:id="@+id/he|ader"/></FrameLayout>""")
        assertEquals(true, assertIs<XmlNavTarget.Resource>(decl).declaration)
        val use = at(layout, """<FrameLayout $ns><View app:layout_constraintTop_toBottomOf="@+id/he|ader"/></FrameLayout>""")
        assertEquals(false, assertIs<XmlNavTarget.Resource>(use).declaration)
    }

    @Test
    fun frameworkReferenceIsMarked() {
        val t = assertIs<XmlNavTarget.Resource>(at(layout, """<View $ns android:background="@android:color/bla|ck"/>"""))
        assertTrue(t.isFramework)
    }

    @Test
    fun valuesNameIsTheDeclaration() {
        val t = assertIs<XmlNavTarget.Resource>(at(values, """<resources><string name="app|_name">X</string></resources>"""))
        assertEquals(ResourceType.STRING, t.type)
        assertTrue(t.declaration)
        val item = assertIs<XmlNavTarget.Resource>(at(values, """<resources><item type="id" name="ro|ot"/></resources>"""))
        assertEquals(ResourceType.ID, item.type)
    }

    @Test
    fun styleParentAndItemNames() {
        val parent = assertIs<XmlNavTarget.Resource>(
            at(values, """<resources><style name="Theme.App" parent="Theme.Material|3.DayNight"/></resources>"""),
        )
        assertEquals(ResourceType.STYLE, parent.type)
        assertEquals("Theme.Material3.DayNight", parent.name)
        assertEquals("Theme_Material3_DayNight", parent.rName)

        val item = assertIs<XmlNavTarget.Resource>(
            at(values, """<resources><style name="A"><item name="colorPri|mary">#fff</item></style></resources>"""),
        )
        assertEquals(ResourceType.ATTR, item.type)
        assertEquals("colorPrimary", item.name)

        val framework = at(values, """<resources><style name="A"><item name="android:window|NoTitle">true</item></style></resources>""")
        assertIs<XmlNavTarget.Attribute>(framework)
    }

    @Test
    fun manifestComponentsResolveAgainstTheNamespace() {
        val text = """<manifest $ns><application><activity android:name=".Main|Activity"/></application></manifest>"""
        assertEquals("com.example.app.MainActivity", assertIs<XmlNavTarget.ClassRef>(at(manifest, text)).fqn)
        val bare = """<manifest $ns><application android:name="App|lication"/></manifest>"""
        assertEquals("com.example.app.Application", assertIs<XmlNavTarget.ClassRef>(at(manifest, bare)).fqn)
        val full = """<manifest $ns><application><service android:name="org.x.Sy|nc"/></application></manifest>"""
        assertEquals("org.x.Sync", assertIs<XmlNavTarget.ClassRef>(at(manifest, full)).fqn)
    }

    @Test
    fun toolsContextAndFragmentClasses() {
        val ctx = at(layout, """<LinearLayout $ns tools:context=".ui.Ho|me"/>""")
        assertEquals("com.example.app.ui.Home", assertIs<XmlNavTarget.ClassRef>(ctx).fqn)
        val frag = at(layout, """<LinearLayout $ns><fragment android:name="com.x.List|Fragment"/></LinearLayout>""")
        assertEquals("com.x.ListFragment", assertIs<XmlNavTarget.ClassRef>(frag).fqn)
        val lm = at(layout, """<LinearLayout $ns><androidx.recyclerview.widget.RecyclerView app:layoutManager="LinearLayout|Manager"/></LinearLayout>""")
        assertEquals("androidx.recyclerview.widget.LinearLayoutManager", assertIs<XmlNavTarget.ClassRef>(lm).fqn)
    }

    @Test
    fun tagNamesAreClasses() {
        val custom = at(layout, """<LinearLayout $ns><com.example.app.Ch|art android:id="@+id/c"/></LinearLayout>""")
        assertEquals("com.example.app.Chart", assertIs<XmlNavTarget.ClassRef>(custom).fqn)
        val closing = at(layout, """<LinearLayout $ns><com.example.app.Chart></com.example.app.Ch|art></LinearLayout>""")
        assertEquals("com.example.app.Chart", assertIs<XmlNavTarget.ClassRef>(closing).fqn)
        assertEquals("android.widget.TextView", assertIs<XmlNavTarget.ClassRef>(at(layout, """<LinearLayout $ns><Text|View/></LinearLayout>""")).fqn)
        assertEquals("android.view.View", assertIs<XmlNavTarget.ClassRef>(at(layout, """<LinearLayout $ns><Vi|ew/></LinearLayout>""")).fqn)
        assertNull(at(layout, """<LinearLayout $ns><mer|ge/></LinearLayout>"""))
        // Outside a layout a capitalised tag is not a view.
        assertNull(at(values, """<resources><Fo|o/></resources>"""))
    }

    @Test
    fun attributeNamesAndOnClick() {
        val a = assertIs<XmlNavTarget.Attribute>(at(layout, """<LinearLayout $ns><TextView android:text|Size="12sp"/></LinearLayout>"""))
        assertEquals("android", a.prefix)
        assertEquals("textSize", a.localName)
        assertEquals("TextView", a.tag)
        assertEquals("LinearLayout", a.parentTag)

        val click = at(layout, """<LinearLayout $ns tools:context=".MainActivity"><Button android:onClick="onS|ave"/></LinearLayout>""")
        val c = assertIs<XmlNavTarget.OnClick>(click)
        assertEquals("onSave", c.method)
        assertEquals("com.example.app.MainActivity", c.contextClass)
    }

    @Test
    fun fileResourceFromPath() {
        assertEquals(ResourceType.LAYOUT to "activity_main", AndroidXmlTargets.fileResource(layout))
        assertEquals(ResourceType.DRAWABLE to "ic_back", AndroidXmlTargets.fileResource("/p/res/drawable-hdpi/ic_back.png"))
        assertNull(AndroidXmlTargets.fileResource(values))
        assertNull(AndroidXmlTargets.fileResource(manifest))
    }
}
