package dev.ide.core

import dev.ide.core.project.ProjectManager
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The app-global prefs file as a fresh [ProjectManager] sees it. The constructor's own init blocks read prefs
 * (plugin state, the crash guard), so whatever the cache holds after construction is what they read: an
 * empty cache there both hides every stored setting (onboarding shows again, the session is not restored)
 * and, if anything is written in that window, saves the file back with only the new key.
 */
class ProjectManagerPrefsTest {

    @Test
    fun storedPreferencesAreVisibleRightAfterConstruction() {
        val home = createTempDirectory("pm-prefs")
        try {
            home.resolve("prefs.properties").writeText("onboarding.done=true\nanalytics.consent=granted\n")
            val manager = ProjectManager.desktop(home.resolve("projects"))
            assertEquals("true", manager.preference("onboarding.done"))
            assertEquals("granted", manager.preference("analytics.consent"))
        } finally {
            home.toFile().deleteRecursively()
        }
    }

    @Test
    fun aWriteRightAfterConstructionKeepsTheOtherKeys() {
        val home = createTempDirectory("pm-prefs")
        try {
            val file = home.resolve("prefs.properties")
            file.writeText("onboarding.done=true\n")
            val manager = ProjectManager.desktop(home.resolve("projects"))
            manager.setPreferences(mapOf("editor.fontSize" to "14"))
            val text = file.readText()
            assertTrue("onboarding.done=true" in text, "the write dropped the stored key:\n$text")
            assertTrue("editor.fontSize=14" in text)
            assertTrue(Files.exists(file))
        } finally {
            home.toFile().deleteRecursively()
        }
    }
}
