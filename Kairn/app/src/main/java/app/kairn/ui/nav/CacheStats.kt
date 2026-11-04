package app.kairn.ui.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import app.kairn.data.PositionsCache
import app.kairn.data.SearchIndex
import app.kairn.data.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * S-01 and the positions cache: how many books hold a cache file and how much it all weighs.
 *
 * Measured off the main thread - listing the dump directory on the composition thread janks the first paint of
 * Settings on a large library - and only once the library has loaded, because pruning against an empty book
 * list would delete every book's cache on every cold start.
 */
@Composable
internal fun rememberCacheStats(store: Store, searchIndex: SearchIndex, positionsCache: PositionsCache): MutableState<Pair<Int, Long>> {
    // A state, not a value: "Clear cache" sets it to zero without a second listing of the directory.
    val stats = remember { mutableStateOf(0 to 0L) }
    LaunchedEffect(Unit) {
        store.awaitLoaded()
        stats.value = withContext(Dispatchers.IO) {
            val known = store.books.value.map { it.id }.toSet()
            searchIndex.prune(known)
            positionsCache.prune(known)
            val (_, indexBytes) = searchIndex.stats()
            val (_, measuredBytes) = positionsCache.stats()
            // One book can hold both a dump and a measurement, so count the union rather than the sum.
            (searchIndex.ids() + positionsCache.ids()).size to indexBytes + measuredBytes
        }
    }
    return stats
}
