package dev.ide.ui.ext

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.graphics.vector.ImageVector
import dev.ide.ui.backend.FileActions
import dev.ide.ui.backend.IdeBackend

/** What a home tab's body is handed: the backend, and the app's ways out of the home screen. */
interface HomeTabContext {
    val backend: IdeBackend

    /** Platform bridges: opening an external link, sharing or picking a file. */
    val fileActions: FileActions get() = FileActions.None

    /** Show the editor for the project that is open now (a tab may open one in the background first). */
    fun openEditor() {}

    /** Open [path] in the editor. */
    fun openFile(path: String) {}

    /**
     * Run the open project the way the editor's Run button does, and show its output. Null where this build cannot
     * run projects.
     */
    val runProject: (() -> Unit)? get() = null
}

/**
 * A tab on the home screen, beside Projects, Store and Learn. [id] is the slot it fills: the host places known
 * slots ([AI]) where they belong, so a plugin supplies the body while the home screen keeps its layout. The tab
 * shows only while a contribution for its slot is registered.
 */
class HomeTabContribution(
    val id: String,
    val title: String,
    /** The tab's icon in the bottom bar and the sidebar. */
    val icon: ImageVector,
    val content: @Composable (HomeTabContext) -> Unit,
) {
    companion object {
        /** The AI tab: the middle of the bottom bar, drawn as its hero action. */
        const val AI = "ai"
    }
}

object HomeTabRegistry {
    private val items = mutableStateListOf<HomeTabContribution>()

    fun register(tab: HomeTabContribution): Registration {
        items.add(tab)
        return Registration { items.remove(tab) }
    }

    fun find(id: String): HomeTabContribution? = items.lastOrNull { it.id == id }
}
