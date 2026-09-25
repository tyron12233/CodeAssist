package dev.ide.ui.screens

import dev.ide.ui.backend.FileActions
import dev.ide.ui.backend.NodeKind
import dev.ide.ui.backend.TreeNode
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tapping an `.apk` in the tree installs it only when the IDE's own build wrote it. An APK anywhere else in a
 * project came from outside the project (a clone, a copied file) and is revealed like any other binary.
 */
class TreeApkInstallTest {

    private class Recorder : FileActions by FileActions.None {
        val calls = ArrayList<String>()
        override val canInstallApk: Boolean get() = true
        override val canReveal: Boolean get() = true
        override fun installApk(path: String) { calls += "install $path" }
        override fun reveal(path: String) { calls += "reveal $path" }
    }

    private fun tap(path: String): List<String> {
        val actions = Recorder()
        openTreeFile(TreeNode(id = path, name = path.substringAfterLast('/'), kind = NodeKind.File, filePath = path), actions) { p, _ ->
            actions.calls += "open $p"
        }
        return actions.calls
    }

    @Test
    fun aBuiltApkIsOfferedToTheInstaller() {
        val built = "/p/app/build/outputs/apk/debug/app-debug.apk"
        assertEquals(listOf("install $built"), tap(built))
    }

    @Test
    fun anApkFromOutsideTheBuildIsRevealedNotInstalled() {
        for (path in listOf("/p/app/libs/tool.apk", "/p/Downloaded.APK", "/p/app/build/intermediates/x.apk")) {
            assertEquals(listOf("reveal $path"), tap(path), path)
        }
    }
}
