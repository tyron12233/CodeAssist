package dev.ide.android.support.crashlytics

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CrashlyticsTest {

    private fun p(vararg s: String) = s.map { Path.of(it) }

    @Test
    fun detectsCrashlyticsAsAResolvedTransitive() {
        // The reported crash came from a transitively-reached Crashlytics, whose artifact is NOT its own
        // LibraryDependency (the whole closure is stored under the primary coordinate), so the Maven-layout
        // cache path is the only coordinate signal on the classpath.
        val cp = p(
            "/c/.platform/caches/resolved-deps/com/google/firebase/firebase-common/21.0.0/firebase-common-21.0.0-exploded/classes.jar",
            "/c/.platform/caches/resolved-deps/com/google/firebase/firebase-crashlytics/20.1.0/firebase-crashlytics-20.1.0-exploded/classes.jar",
        )
        assertTrue(Crashlytics.onClasspath(cp))
    }

    @Test
    fun detectsALocalAarWithNoMavenLayout() {
        assertTrue(Crashlytics.onClasspath(p("/proj/app/libs/firebase-crashlytics-20.1.0.aar")))
    }

    @Test
    fun siblingArtifactsAreNotTheCrashlyticsRuntime() {
        // `-ndk` and `-buildtools` ship alongside Crashlytics but neither triggers the startup check, and
        // matching them on a bare filename prefix would generate the resource for the wrong reason.
        assertFalse(Crashlytics.onClasspath(p("/proj/app/libs/firebase-crashlytics-ndk-20.1.0.aar")))
        assertFalse(Crashlytics.onClasspath(p("/proj/app/libs/firebase-crashlytics-buildtools-3.0.7.jar")))
        assertFalse(Crashlytics.onClasspath(p("/c/androidx/core/core/1.13.1/core-1.13.1.aar")))
    }

    @Test
    fun ndkArtifactUnderMavenLayoutIsNotTheRuntimeEither() {
        assertFalse(
            Crashlytics.onClasspath(
                p("/c/com/google/firebase/firebase-crashlytics-ndk/20.1.0/firebase-crashlytics-ndk-20.1.0-exploded/classes.jar"),
            ),
        )
    }

    @Test
    fun generatesTheResourceTheRuntimeLooksUpByName() {
        // `CommonUtils.getMappingFileId` resolves this by NAME via Resources.getIdentifier, so the exact
        // resource name is the contract, not an R constant.
        val xml = Crashlytics.mappingFileIdXml()
        assertContains(xml, """name="com.google.firebase.crashlytics.mapping_file_id"""")
        assertContains(xml, """translatable="false"""")
        assertContains(xml, Crashlytics.BLANK_MAPPING_FILE_ID)
        assertTrue(xml.startsWith("<?xml version=\"1.0\" encoding=\"utf-8\"?>"))
        assertTrue(xml.trimEnd().endsWith("</resources>"))
    }

    @Test
    fun theMappingFileIdIsStableAcrossBuilds() {
        // A per-build UUID would rewrite this resource every time and dirty the resource merge on each build.
        assertTrue(Crashlytics.mappingFileIdXml() == Crashlytics.mappingFileIdXml())
        assertTrue(Crashlytics.BLANK_MAPPING_FILE_ID.isNotEmpty(), "an empty id fails Crashlytics' own check")
    }
}
