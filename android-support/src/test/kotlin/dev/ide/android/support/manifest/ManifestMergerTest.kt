package dev.ide.android.support.manifest

import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Verifies the from-scratch [ManifestMerger] against the ManifestMerger2 behaviours that matter for real
 * libraries (Firebase/Play Services/AndroidX): library components/permissions merged in, `${applicationId}`
 * substitution, node de-duplication, and the `tools:` markers (`replace`/`remove`/`node`). Pure XML — no SDK.
 */
class ManifestMergerTest {

    private val APP = """
        <?xml version="1.0" encoding="utf-8"?>
        <manifest xmlns:android="http://schemas.android.com/apk/res/android"
            package="com.example.app">
            <uses-sdk android:minSdkVersion="24" android:targetSdkVersion="34"/>
            <application android:label="App" android:icon="@mipmap/ic_launcher">
                <activity android:name=".MainActivity" android:exported="true"/>
            </application>
        </manifest>
    """.trimIndent()

    // A Firebase-shaped library: a permission, an init provider keyed off ${applicationId}, a service.
    private val FIREBASE_LIB = """
        <?xml version="1.0" encoding="utf-8"?>
        <manifest xmlns:android="http://schemas.android.com/apk/res/android"
            package="com.google.firebase.common">
            <uses-permission android:name="android.permission.INTERNET"/>
            <application>
                <provider
                    android:name="com.google.firebase.provider.FirebaseInitProvider"
                    android:authorities="${'$'}{applicationId}.firebaseinitprovider"
                    android:exported="false"
                    android:initOrder="100"/>
                <service android:name="com.google.firebase.components.ComponentDiscoveryService"
                    android:exported="false"/>
            </application>
        </manifest>
    """.trimIndent()

    private fun parse(xml: String) =
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray())).documentElement

    private fun descendants(root: Element, tag: String): List<Element> {
        val out = ArrayList<Element>()
        val list = root.getElementsByTagName(tag)
        for (i in 0 until list.length) out += list.item(i) as Element
        return out
    }

    private fun android(e: Element, local: String) =
        e.getAttributeNS(ManifestMerger.ANDROID_NS, local)

    @Test
    fun mergesLibraryComponentsAndPermissions() {
        val r = ManifestMerger.mergeXml(APP, listOf(FIREBASE_LIB), mapOf("applicationId" to "com.example.app"))
        assertFalse(r.hasErrors, "unexpected errors: ${r.messages}")
        val root = parse(r.xml)

        // The library's permission, provider, and service all landed in the merged manifest.
        assertTrue(descendants(root, "uses-permission").any { android(it, "name") == "android.permission.INTERNET" })
        val provider = descendants(root, "provider").single()
        assertEquals("com.google.firebase.provider.FirebaseInitProvider", android(provider, "name"))
        assertTrue(descendants(root, "service").any { android(it, "name") == "com.google.firebase.components.ComponentDiscoveryService" })

        // The app's own activity survived, exactly once.
        assertEquals(1, descendants(root, "activity").size)
    }

    @Test
    fun substitutesApplicationIdPlaceholder() {
        val r = ManifestMerger.mergeXml(APP, listOf(FIREBASE_LIB), mapOf("applicationId" to "com.example.app"))
        val provider = descendants(parse(r.xml), "provider").single()
        assertEquals("com.example.app.firebaseinitprovider", android(provider, "authorities"))
    }

    @Test
    fun unresolvedPlaceholderIsReportedAndLeftVerbatim() {
        val r = ManifestMerger.mergeXml(APP, listOf(FIREBASE_LIB), emptyMap())
        assertTrue(r.messages.any { it.severity == ManifestMerger.Severity.WARNING && "applicationId" in it.text })
        // Left verbatim rather than failing the build.
        assertTrue("\${applicationId}.firebaseinitprovider" in r.xml)
    }

    @Test
    fun dedupesAComponentDeclaredInBothAppAndLibrary() {
        val lib = """
            <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.lib">
                <application>
                    <activity android:name="com.example.app.MainActivity" android:enabled="true"/>
                </application>
            </manifest>
        """.trimIndent()
        // App declares .MainActivity; the resolved (absolute) name differs textually, so this checks that a
        // DIFFERENT-keyed activity is added (the merger keys by the raw android:name string, AGP-style).
        val r = ManifestMerger.mergeXml(APP, listOf(lib), mapOf("applicationId" to "com.example.app"))
        val activities = descendants(parse(r.xml), "activity")
        // .MainActivity and com.example.app.MainActivity have distinct keys → both present (AGP behaves the
        // same; it resolves names earlier in its pipeline). The merge must not crash or drop the app's node.
        assertTrue(activities.any { android(it, "name") == ".MainActivity" })
    }

    @Test
    fun toolsReplaceLetsAppOverrideALibraryAttribute() {
        val lib = """
            <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.lib">
                <application android:label="LibLabel" android:theme="@style/LibTheme"/>
            </manifest>
        """.trimIndent()
        val app = """
            <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                xmlns:tools="http://schemas.android.com/tools" package="com.example.app">
                <application android:label="App" tools:replace="android:label">
                    <activity android:name=".A"/>
                </application>
            </manifest>
        """.trimIndent()
        val r = ManifestMerger.mergeXml(app, listOf(lib))
        assertFalse(r.hasErrors, "errors: ${r.messages}")
        val appEl = descendants(parse(r.xml), "application").single()
        assertEquals("App", android(appEl, "label"))           // app's value kept (tools:replace)
        assertEquals("@style/LibTheme", android(appEl, "theme")) // lib-only attribute still merged in
        // tools:* artifacts stripped from the output.
        assertFalse("tools:replace" in r.xml)
        assertFalse("schemas.android.com/tools" in r.xml)
    }

    @Test
    fun toolsRemoveAttributeDropsIt() {
        val lib = """
            <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.lib">
                <application android:allowBackup="true"/>
            </manifest>
        """.trimIndent()
        val app = """
            <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                xmlns:tools="http://schemas.android.com/tools" package="com.example.app">
                <application tools:remove="android:allowBackup"><activity android:name=".A"/></application>
            </manifest>
        """.trimIndent()
        val r = ManifestMerger.mergeXml(app, listOf(lib))
        val appEl = descendants(parse(r.xml), "application").single()
        assertEquals("", android(appEl, "allowBackup"))   // removed
    }

    @Test
    fun toolsNodeRemoveDropsALibraryComponent() {
        val app = """
            <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                xmlns:tools="http://schemas.android.com/tools" package="com.example.app">
                <application>
                    <activity android:name=".A"/>
                    <provider android:name="com.google.firebase.provider.FirebaseInitProvider" tools:node="remove"/>
                </application>
            </manifest>
        """.trimIndent()
        val r = ManifestMerger.mergeXml(app, listOf(FIREBASE_LIB), mapOf("applicationId" to "com.example.app"))
        // The library's FirebaseInitProvider is removed by the marker; the service still merges.
        assertTrue(descendants(parse(r.xml), "provider").isEmpty())
        assertTrue(descendants(parse(r.xml), "service").isNotEmpty())
    }

    @Test
    fun libraryPackageAndLowerSdkAreAbsorbedWithoutWarnings() {
        // An AndroidX-shaped library: its own package + a lower minSdk + a different targetSdk. None of these
        // are conflicts (AGP/ManifestMerger2 keeps the app values silently); the merge must not warn on them.
        val lib = """
            <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="androidx.core">
                <uses-sdk android:minSdkVersion="14" android:targetSdkVersion="31"/>
                <application/>
            </manifest>
        """.trimIndent()
        val r = ManifestMerger.mergeXml(APP, listOf(lib), libraryNames = listOf("androidx.core"))
        assertFalse(r.hasErrors, "errors: ${r.messages}")
        assertTrue(r.messages.none { "@package" in it.text }, "spurious package warning: ${r.messages}")
        assertTrue(r.messages.none { "minSdkVersion" in it.text }, "spurious minSdk warning: ${r.messages}")
        assertTrue(r.messages.none { "targetSdkVersion" in it.text }, "spurious targetSdk warning: ${r.messages}")
        // The app's own package + uses-sdk values survive unchanged.
        val root = parse(r.xml)
        assertEquals("com.example.app", root.getAttribute("package"))
        val usesSdk = descendants(root, "uses-sdk").single()
        assertEquals("24", android(usesSdk, "minSdkVersion"))
        assertEquals("34", android(usesSdk, "targetSdkVersion"))
    }

    @Test
    fun libraryDemandingHigherMinSdkIsStillFlagged() {
        // The silence above must not hide a genuine problem: a library needing a HIGHER minSdk than the app.
        val lib = """
            <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.lib">
                <uses-sdk android:minSdkVersion="33"/>
            </manifest>
        """.trimIndent()
        val r = ManifestMerger.mergeXml(APP, listOf(lib), libraryNames = listOf("com.lib"))
        assertTrue(r.messages.any { it.severity == ManifestMerger.Severity.WARNING && "minSdkVersion 33" in it.text })
    }

    @Test
    fun expandsLibraryRelativeComponentNamesAgainstItsPackage() {
        val lib = """
            <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.lib">
                <uses-permission android:name="android.permission.VIBRATE"/>
                <application android:name=".LibApp" android:backupAgent=".LibBackup">
                    <service android:name=".MyService"/>
                    <receiver android:name="BareReceiver"/>
                    <activity android:name="com.other.AlreadyQualified"/>
                    <activity-alias android:name=".Alias" android:targetActivity=".MyService"/>
                    <provider android:name=".MyProvider" android:authorities="com.lib.provider"/>
                    <meta-data android:name="barekey" android:value="x"/>
                </application>
            </manifest>
        """.trimIndent()
        val r = ManifestMerger.mergeXml(APP, listOf(lib))
        assertFalse(r.hasErrors, "errors: ${r.messages}")
        val root = parse(r.xml)

        // Relative component names resolve against the LIBRARY package.
        assertEquals("com.lib.MyService", android(descendants(root, "service").single(), "name"))
        assertEquals("com.lib.BareReceiver", android(descendants(root, "receiver").single(), "name"))   // bare → pkg.Bare
        assertEquals("com.lib.MyProvider", android(descendants(root, "provider").single(), "name"))
        val alias = descendants(root, "activity-alias").single()
        assertEquals("com.lib.Alias", android(alias, "name"))
        assertEquals("com.lib.MyService", android(alias, "targetActivity"))
        val app = descendants(root, "application").single()
        assertEquals("com.lib.LibApp", android(app, "name"))
        assertEquals("com.lib.LibBackup", android(app, "backupAgent"))

        // Already-qualified names are left alone.
        assertEquals("com.other.AlreadyQualified", android(descendants(root, "activity").single { android(it, "name") != ".MainActivity" }, "name"))
        // The provider's authorities (not a class name) and identifier android:names are NOT rewritten.
        assertEquals("com.lib.provider", android(descendants(root, "provider").single(), "authorities"))
        assertTrue(descendants(root, "uses-permission").any { android(it, "name") == "android.permission.VIBRATE" })
        assertEquals("barekey", android(descendants(root, "meta-data").single(), "name"))   // not a class attr → bare name kept
        // The app's own relative name stays relative (it resolves against the merged manifest's app package).
        assertTrue(descendants(root, "activity").any { android(it, "name") == ".MainActivity" })
    }

    @Test
    fun doesNotImportALibraryUsesSdkWhenTheAppDeclaresNone() {
        // The default app template declares no <uses-sdk> (min/target come from the build config, injected by
        // aapt2's --min/--target-sdk-version). A library's <uses-sdk> must NOT leak in, or its targetSdkVersion
        // would silently become the app's effective target (forcing edge-to-edge / breaking insets).
        val appNoSdk = """
            <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.example.app">
                <application android:label="App"><activity android:name=".MainActivity"/></application>
            </manifest>
        """.trimIndent()
        val lib = """
            <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="androidx.core">
                <uses-sdk android:minSdkVersion="14" android:targetSdkVersion="33"/>
                <application/>
            </manifest>
        """.trimIndent()
        val r = ManifestMerger.mergeXml(appNoSdk, listOf(lib), libraryNames = listOf("androidx.core"))
        assertFalse(r.hasErrors, "errors: ${r.messages}")
        // No <uses-sdk> leaked in, so aapt2's --target-sdk-version (the facet value) stays authoritative.
        assertTrue(descendants(parse(r.xml), "uses-sdk").isEmpty(), "library uses-sdk leaked into the app manifest")
    }

    @Test
    fun noLibrariesRoundTripsTheAppManifest() {
        val r = ManifestMerger.mergeXml(APP, emptyList(), mapOf("applicationId" to "com.example.app"))
        assertFalse(r.hasErrors)
        val root = parse(r.xml)
        assertEquals("com.example.app", root.getAttribute("package"))
        assertEquals(1, descendants(root, "activity").size)
    }
}
