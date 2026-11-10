package dev.ide.android.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith

/** Version control on JGit: create a repository for a project, stage everything, commit. */
@RunWith(AndroidJUnit4::class)
class GitTest : E2eTest() {

    @Test
    fun initStageAndCommit() {
        val name = uniqueName("Git")
        createProject("Kotlin Console App", name)
        // The bottom-bar tap can land while the editor is busy indexing the new project and be dropped:
        // re-tap until the Git panel shows.
        poll(90_000, 10_000) {
            findNow("Create repository") ?: run { click("Source"); null }
        } ?: failWithScreen("the Git panel never opened")
        click("Create repository")
        await("Stage all", 60_000)
        click("Stage all")
        // The message field's "Commit message" placeholder isn't exposed to accessibility; it's the panel's
        // only text field.
        await(androidx.test.uiautomator.By.clazz("android.widget.EditText")).let { it.click(); it.text = "Initial commit from e2e" }
        hideKeyboard()
        click("Commit")
        // The "Committed <id>" note only shows for a few seconds; the panel's history row ("<short id> <message>")
        // and the clean-tree state are the durable proof.
        await(Regex("^[0-9a-f]{7,} Initial commit from e2e$"), 60_000)
        await("Your working tree matches the last commit.")
    }
}
