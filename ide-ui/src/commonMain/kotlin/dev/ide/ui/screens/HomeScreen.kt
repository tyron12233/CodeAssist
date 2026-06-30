package dev.ide.ui.screens

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.ide.ui.HomeTab
import dev.ide.ui.components.BottomNavBar
import dev.ide.ui.components.BottomNavItem
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.theme.Motion

/**
 * The home/landing scaffold: the selected [HomeTab]'s content above a [BottomNavBar] that switches between
 * the project picker, the Projects Store, and Learn. Each tab's content is supplied by the host (so all the
 * picker/store/learn wiring stays in one place) and crossfades on switch. Only shown on `Screen.Projects`;
 * full-screen destinations (editor, settings, run) push over it without the nav bar.
 */
@Composable
fun HomeScreen(
    tab: HomeTab,
    onSelectTab: (HomeTab) -> Unit,
    projectsContent: @Composable () -> Unit,
    storeContent: @Composable () -> Unit,
    learnContent: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Crossfade(
            targetState = tab,
            animationSpec = tween(Motion.BASE, easing = Motion.soft),
            label = "homeTab",
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) { t ->
            Box(Modifier.fillMaxSize()) {
                when (t) {
                    HomeTab.Projects -> projectsContent()
                    HomeTab.Store -> storeContent()
                    HomeTab.Learn -> learnContent()
                }
            }
        }
        BottomNavBar(
            items = NAV_ITEMS,
            selectedId = tab.name,
            onSelect = { id -> onSelectTab(HomeTab.valueOf(id)) },
        )
    }
}

private val NAV_ITEMS = listOf(
    BottomNavItem(HomeTab.Projects.name, "Projects", CaIcons.folder),
    BottomNavItem(HomeTab.Store.name, "Store", CaIcons.grid),
    BottomNavItem(HomeTab.Learn.name, "Learn", CaIcons.lightbulb),
)
