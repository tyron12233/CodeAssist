package dev.ide.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.back
import dev.ide.ui.icons.CaIcons
import org.jetbrains.compose.resources.stringResource

/**
 * True while a screen renders as the detail pane of a two-pane host (the wide Settings & Tools hub). The
 * host's sidebar is the navigation there, so [ExpressiveScaffold] drops its back arrow and uses the compact
 * bar: a collapsing large title beside a persistent sidebar is a phone idiom wasting the top of a window.
 */
val LocalEmbeddedPane = staticCompositionLocalOf { false }

/**
 * The shared Material 3 Expressive screen frame: a [Scaffold] with a collapsing [LargeTopAppBar] (a big
 * title that shrinks on scroll), an optional back navigation icon, a trailing [actions] cluster, and an
 * optional FAB and [snackbarHost]. Every redesigned screen wraps its content in this so the whole app
 * reads as one system.
 * The [content] receives the top-bar inset as [PaddingValues] — apply it (or pass it to a LazyColumn's
 * `contentPadding`) so content scrolls under the collapsing bar.
 *
 * Set [large] = false for a compact (non-collapsing) [TopAppBar] on dense/secondary screens. A screen whose
 * commit action must stay reachable while its content scrolls passes it as [bottomBar]. Inside a
 * [LocalEmbeddedPane] both [onBack] and [large] are ignored.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExpressiveScaffold(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    /** Pinned bottom bar — the place for a screen's primary/secondary actions. */
    bottomBar: @Composable () -> Unit = {},
    /** Snackbar host, for a screen that reports the outcome of an action. */
    snackbarHost: @Composable () -> Unit = {},
    large: Boolean = true,
    content: @Composable (PaddingValues) -> Unit,
) {
    val embedded = LocalEmbeddedPane.current
    val back = onBack.takeUnless { embedded }
    val largeBar = large && !embedded
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = modifier.fillMaxSize().nestedScroll(scroll.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            val nav: @Composable () -> Unit = {
                if (back != null) IconButton(onClick = back) {
                    Icon(CaIcons.chevronLeft, stringResource(Res.string.back))
                }
            }
            val titleContent: @Composable () -> Unit = { Text(title) }
            if (largeBar) {
                LargeTopAppBar(title = titleContent, navigationIcon = nav, actions = actions, scrollBehavior = scroll)
            } else {
                TopAppBar(title = titleContent, navigationIcon = nav, actions = actions, scrollBehavior = scroll)
            }
        },
        bottomBar = bottomBar,
        floatingActionButton = floatingActionButton,
        snackbarHost = snackbarHost,
        content = content,
    )
}
