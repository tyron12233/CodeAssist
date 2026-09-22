package dev.ide.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.UiStoreCategory
import dev.ide.ui.backend.UiStoreItem
import dev.ide.ui.components.CircularProgressIndicator
import dev.ide.ui.components.PillChip
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.store_no_results
import dev.ide.ui.generated.resources.store_search_hint
import dev.ide.ui.generated.resources.store_search_all
import dev.ide.ui.generated.resources.store_title
import dev.ide.ui.icons.CaSymbols
import dev.ide.ui.itemsIndexedKeyed
import dev.ide.ui.itemsKeyed
import dev.ide.ui.platform.PlatformBackHandler
import dev.ide.ui.theme.Symbol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * The store's search, over the whole catalogue, a page at a time.
 *
 * It is also the store's **browse-all** route. The curated feed is bounded by design (every shelf has a
 * server-side item cap), so before this there was no way in the app to reach a published project that no
 * shelf happened to carry: search ran over the bundled templates only, and the feed's own search entry
 * re-selected the tab it was already on. An empty query here is a real query, ordered by the backend's
 * quality score, and scrolling it walks the catalogue.
 *
 * One implementation, used both from the Explore feed and from the bundled catalogue screen, so the two
 * cannot drift into two different searches.
 */
@Composable
fun StoreSearchScreen(
    backend: IdeBackend,
    onOpenItem: (UiStoreItem) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    /** The category to open filtered to, from a "browse by kind" tile. Null opens unfiltered. */
    initialCategory: String? = null,
) {
    val state = rememberStoreSearchState(backend, initialCategory)
    val c = MaterialTheme.colorScheme
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    // Ahead of the app's own handler, so Back leaves the search rather than the tab it was opened from.
    PlatformBackHandler(enabled = true, onBack = onClose)

    Column(modifier.fillMaxSize().background(c.background)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = CircleShape,
                color = c.surfaceContainer,
                contentColor = c.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) {
                Row(
                    Modifier.padding(start = 4.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(48.dp).clip(CircleShape).clickable(onClick = onClose),
                        contentAlignment = Alignment.Center,
                    ) {
                        Symbol(
                            CaSymbols.arrowBack,
                            contentDescription = stringResource(Res.string.store_title),
                            size = 24.dp,
                        )
                    }
                    BasicTextField(
                        value = state.query,
                        onValueChange = state::updateQuery,
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = c.onSurface),
                        cursorBrush = SolidColor(c.primary),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = {}),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                        decorationBox = { field ->
                            if (state.query.isEmpty()) {
                                Text(
                                    stringResource(Res.string.store_search_hint),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = c.onSurfaceVariant,
                                )
                            }
                            field()
                        },
                    )
                }
            }
        }

        if (state.categories.isNotEmpty()) {
            LazyRow(
                Modifier.fillMaxWidth().padding(bottom = 10.dp),
                contentPadding = PaddingValues(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                itemsKeyed(state.categories, key = { it.id }) { cat ->
                    PillChip(
                        label = cat.title,
                        selected = state.category == cat.id,
                        leadingGlyph = CaSymbols.check,
                        onClick = { state.selectCategory(if (state.category == cat.id) null else cat.id) },
                    )
                }
            }
        }

        val results = state.results
        when {
            results.isEmpty() && state.loading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
            }

            results.isEmpty() -> Column(
                Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 56.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Symbol(CaSymbols.searchOff, contentDescription = null, size = 44.dp, tint = c.outlineVariant)
                Text(
                    // A blank query that finds nothing is an empty store, not a search that missed.
                    state.error
                        ?: if (state.query.isBlank()) stringResource(Res.string.store_search_all)
                        else stringResource(Res.string.store_no_results, state.query),
                    style = MaterialTheme.typography.bodyLarge,
                    color = c.onSurfaceVariant,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }

            else -> {
                val listState = rememberLazyListState()
                // The next page is asked for before the last row is reached, so a steady scroll never
                // stops on a spinner. Driven by what is on screen rather than by a "load more" button:
                // the button is the thing a reader has to notice, and half of them do not.
                LaunchedEffect(listState, state) {
                    snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
                        .distinctUntilChanged()
                        .filter { it >= state.results.size - PREFETCH_ROWS }
                        .collect { state.loadMore() }
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 24.dp),
                ) {
                    itemsIndexedKeyed(results, key = { _, it -> it.id }) { i, item ->
                        StoreItemRow(item, i, onOpenItem, backend = backend)
                    }
                    if (state.loadingMore) {
                        item("more") {
                            Box(Modifier.fillMaxWidth().padding(vertical = 20.dp), Alignment.Center) {
                                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                            }
                        }
                    }
                    state.error?.takeIf { results.isNotEmpty() }?.let { message ->
                        // Results already on screen stay: a failed second page is not a reason to empty
                        // the first one.
                        item("error") {
                            Text(
                                message,
                                style = MaterialTheme.typography.bodySmall,
                                color = c.error,
                                modifier = Modifier.fillMaxWidth().padding(20.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * What the search screen holds: the query, the filter, and the pages read so far.
 *
 * A query change restarts at page zero (`collectLatest` cancels the request in flight, so a fast typist
 * makes one request rather than one per keystroke); [loadMore] appends. Results are deduplicated by id
 * because the first page carries the device's bundled templates ahead of the remote hits, and a repeated
 * key in a `LazyColumn` is a crash rather than a duplicate row.
 */
@Stable
internal class StoreSearchState(
    private val backend: IdeBackend,
    private val scope: CoroutineScope,
    initialCategory: String?,
) {
    var query: String by mutableStateOf("")
        private set
    var category: String? by mutableStateOf(initialCategory)
        private set
    var categories: List<UiStoreCategory> by mutableStateOf(emptyList())
        private set
    var results: List<UiStoreItem> by mutableStateOf(emptyList())
        private set
    var loading: Boolean by mutableStateOf(true)
        private set
    var loadingMore: Boolean by mutableStateOf(false)
        private set
    var error: String? by mutableStateOf(null)
        private set

    private var hasMore = false
    private var offset = 0
    /** The page request in flight, so a scroll cannot ask for the same page twice. */
    private var paging: kotlinx.coroutines.Job? = null

    init {
        scope.launch {
            categories = runCatching { backend.store.searchCategories() }.getOrDefault(emptyList())
        }
        scope.launch {
            snapshotFlow { query to category }.collectLatest { (text, cat) ->
                // Typed queries are debounced; opening the screen, and picking a category, are not: they
                // are one deliberate act each and waiting on them only reads as lag.
                if (text.isNotEmpty()) delay(SEARCH_DEBOUNCE_MS)
                // A page still arriving for the previous query would append rows from a different search,
                // at an offset that no longer means anything.
                paging?.cancel()
                loadingMore = false
                loading = true
                error = null
                val page = runCatching { backend.store.searchPage(text, cat) }.getOrNull()
                results = page?.items.orEmpty().distinctBy { it.id }
                hasMore = page?.hasMore == true
                error = page?.error
                offset = page?.items?.size ?: 0
                loading = false
            }
        }
    }

    fun updateQuery(value: String) { query = value }

    fun selectCategory(value: String?) { category = value }

    /** Read the next page, if there is one and nothing else is already reading it. */
    fun loadMore() {
        if (!hasMore || loading || paging?.isActive == true) return
        paging = scope.launch {
            loadingMore = true
            val page = runCatching { backend.store.searchPage(query, category, offset) }.getOrNull()
            val fetched = page?.items.orEmpty()
            if (fetched.isNotEmpty()) {
                results = (results + fetched).distinctBy { it.id }
                // Counted from what was asked for, not from what survived deduplication: the offset is a
                // position in the backend's result set, and dropping a duplicate must not rewind it.
                offset += fetched.size
            }
            hasMore = page?.hasMore == true
            error = page?.error
            loadingMore = false
        }
    }
}

@Composable
internal fun rememberStoreSearchState(
    backend: IdeBackend,
    initialCategory: String? = null,
    scope: CoroutineScope = rememberCoroutineScope(),
): StoreSearchState = remember(backend, scope) { StoreSearchState(backend, scope, initialCategory) }

/** How close to the end of the list the next page is asked for, in rows. */
private const val PREFETCH_ROWS = 6

/** How long a typed query settles before it is sent. */
private const val SEARCH_DEBOUNCE_MS = 180L
