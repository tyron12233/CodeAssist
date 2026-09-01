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
import dev.ide.ui.components.AppNavBar
import dev.ide.ui.components.NavDestination
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.home_learn
import dev.ide.ui.generated.resources.home_store
import dev.ide.ui.generated.resources.projects
import dev.ide.ui.icons.CaSymbols
import dev.ide.ui.theme.Motion
import org.jetbrains.compose.resources.stringResource

/**
 * The home/landing scaffold: the selected [HomeTab]'s content above a [AppNavBar] that switches between
 * the project manager, the Projects Store, and Learn. Each tab's content is supplied by the host (so all the
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
        AppNavBar(
            destinations = listOf(
                NavDestination(HomeTab.Projects.name, stringResource(Res.string.projects), CaSymbols.folderOpen),
                NavDestination(HomeTab.Store.name, stringResource(Res.string.home_store), CaSymbols.travelExplore),
                NavDestination(HomeTab.Learn.name, stringResource(Res.string.home_learn), CaSymbols.school),
            ),
            selectedId = tab.name,
            onSelect = { id -> onSelectTab(HomeTab.valueOf(id)) },
        )
    }
}
