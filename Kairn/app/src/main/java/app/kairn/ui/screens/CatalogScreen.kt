package app.kairn.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kairn.R
import app.kairn.data.Catalog
import app.kairn.data.Library
import app.kairn.data.Opds
import app.kairn.data.OpdsClient
import app.kairn.data.OpdsEntry
import app.kairn.data.OpdsFeed
import app.kairn.data.Store
import app.kairn.ui.kit.KButton
import app.kairn.ui.kit.KButtonStyle
import app.kairn.ui.kit.KCard
import app.kairn.ui.kit.KIconButton
import app.kairn.ui.kit.KChevron
import app.kairn.ui.kit.KField
import app.kairn.ui.kit.KRow
import app.kairn.ui.kit.KSubHeader
import app.kairn.ui.kit.LocalToast
import app.kairn.ui.theme.Dim
import app.kairn.ui.theme.K
import app.kairn.ui.theme.KType
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * OPDS: books from a catalog instead of from this phone - a Calibre content server on the same Wi-Fi, or any
 * other catalog that speaks Atom.
 *
 * Nothing here runs on its own. The screen loads one page when it is opened or a folder is tapped, and a book
 * is downloaded only after a tap: this is the one place Kairn reads the network, and it is the reader's hand
 * that starts it. Covers are not fetched at all (they would be one request per visible row), so an entry is
 * its title, its author and its size of a row.
 */
@Composable
fun CatalogScreen(
    store: Store,
    library: Library,
    client: OpdsClient,
    onBack: () -> Unit,
    onImported: (Int) -> Unit = {},
) {
    val toast = LocalToast.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by store.settings.collectAsStateWithLifecycle()

    var browsing by remember { mutableStateOf<Catalog?>(null) }
    var editing by remember { mutableStateOf<Catalog?>(null) }
    var adding by remember { mutableStateOf(false) }

    var feed by remember { mutableStateOf<OpdsFeed?>(null) }
    var loading by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var stack by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }  // url to title, for Up
    var downloading by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf(0) }
    var imported by remember { mutableStateOf(0) }

    val unreachable = stringResource(R.string.s_catalog_unreachable)
    val notOpds = stringResource(R.string.s_catalog_not_opds)

    fun load(catalog: Catalog, url: String, title: String, push: Boolean) {
        scope.launch {
            loading = true; failed = false; feed = null
            val body = client.fetch(url, catalog.user, catalog.pass)
            val parsed = body?.let { Opds.parse(it, url) }
            loading = false
            if (parsed == null) { failed = true; toast.show(if (body == null) unreachable else notOpds); return@launch }
            if (push) stack = stack + (url to title)
            browsing = catalog
            feed = parsed
        }
    }

    fun download(catalog: Catalog, entry: OpdsEntry) {
        val url = entry.epub ?: return
        scope.launch {
            downloading = entry.title; progress = 0
            val dest = File(ctx.cacheDir, "opds-${System.currentTimeMillis()}.epub")
            val size = client.download(url, dest, catalog.user, catalog.pass) { progress = it }
            if (size == null) {
                downloading = null
                toast.show(unreachable)
                return@launch
            }
            // importShared copies the file into the app's own folder and gives it a content id, exactly as an
            // "Open with" does; the cache copy is ours to delete.
            val book = withContext(Dispatchers.IO) { library.importShared(android.net.Uri.fromFile(dest)) }
            withContext(Dispatchers.IO) { dest.delete() }
            downloading = null
            imported += 1
            onImported(imported)
            if (book.broken) toast.show(ctx.getString(R.string.s_bad_file_damaged))
        }
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        val title = when {
            adding || editing != null -> stringResource(R.string.s_catalog_add)
            browsing != null -> browsing?.name ?: stringResource(R.string.s_catalog)
            else -> stringResource(R.string.s_from_a_catalog)
        }
        KSubHeader(
            title,
            onBack = {
                when {
                    adding || editing != null -> { adding = false; editing = null }
                    browsing != null && stack.isNotEmpty() -> {
                        val prev = stack.last()
                        stack = stack.dropLast(1)
                        browsing?.let { load(it, prev.first, prev.second, push = false) }
                    }
                    browsing != null -> { browsing = null; feed = null; stack = emptyList() }
                    else -> onBack()
                }
            },
            subtitle = browsing?.url,
        )

        when {
            adding || editing != null -> CatalogForm(
                initial = editing,
                onCancel = { adding = false; editing = null },
                onSave = { c ->
                    store.saveSettings { s ->
                        val others = s.catalogs.filterNot { it.url == (editing?.url ?: c.url) && it.name == (editing?.name ?: c.name) }
                        s.copy(catalogs = (others + c).take(10))
                    }
                    adding = false; editing = null
                },
            )

            browsing == null -> LazyColumn(Modifier.fillMaxSize().padding(horizontal = Dim.screenPad.dp)) {
                item {
                    Text(stringResource(R.string.s_catalog_hint), style = KType.small.copy(color = K.dim),
                        modifier = Modifier.padding(top = 8.dp, bottom = 14.dp))
                }
                if (settings.catalogs.isEmpty()) {
                    item {
                        Text(stringResource(R.string.s_catalog_empty), style = KType.body.copy(color = K.mut),
                            modifier = Modifier.padding(vertical = 20.dp))
                    }
                }
                items(settings.catalogs, key = { it.url + it.name }) { c ->
                    KCard(Modifier.padding(bottom = 8.dp)) {
                        KRow(c.name, subtitle = c.url, leading = Icons.Default.CloudDownload, onClick = { load(c, c.url, c.name, push = false) }) {
                            KIconButton(Icons.Default.Delete, stringResource(R.string.s_catalog_remove), {
                                store.saveSettings { s -> s.copy(catalogs = s.catalogs.filterNot { it == c }) }
                            })
                        }
                    }
                }
                item {
                    Spacer(Modifier.height(10.dp))
                    KButton(stringResource(R.string.s_catalog_add), { adding = true }, style = KButtonStyle.Secondary, icon = Icons.Default.Add)
                }
            }

            else -> {
                val catalog = browsing!!
                Column(Modifier.fillMaxSize()) {
                    if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 6.dp), color = K.mint)
                    if (stack.isNotEmpty()) {
                        KRow(stringResource(R.string.s_catalog_up), leading = Icons.AutoMirrored.Filled.ArrowBack, onClick = {
                            val prev = stack.last()
                            stack = stack.dropLast(1)
                            load(catalog, prev.first, prev.second, push = false)
                        })
                    }
                    downloading?.let { t ->
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = Dim.screenPad.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text(stringResource(R.string.s_catalog_downloading, t), style = KType.small, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("$progress%", style = KType.small.copy(color = K.mint))
                        }
                    }
                    LazyColumn(Modifier.fillMaxSize().padding(horizontal = Dim.screenPad.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        val entries = feed?.entries.orEmpty()
                        if (!loading && entries.isEmpty() && !failed) {
                            item { Text(stringResource(R.string.s_catalog_nothing_here), style = KType.small.copy(color = K.dim), modifier = Modifier.padding(top = 20.dp)) }
                        }
                        items(entries) { e ->
                            Row(
                                Modifier.fillMaxWidth()
                                    .clickable(enabled = downloading == null) {
                                        if (e.navigation) {
                                            // A navigation entry names a folder; its own href is the book's
                                            // parent, so the folder is opened by its link, not by the entry.
                                            val sub = e.sub
                                            if (sub != null) load(catalog, sub, e.title, push = true)
                                        } else download(catalog, e)
                                    }
                                    .padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Icon(
                                    if (e.navigation) Icons.Default.Folder else Icons.Default.Description,
                                    null, tint = if (e.navigation) K.mut else K.mint,
                                )
                                Column(Modifier.weight(1f)) {
                                    Text(e.title, style = KType.title.copy(fontWeight = FontWeight.SemiBold), maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    val sub = listOfNotNull(e.author, e.summary).joinToString(" · ")
                                    if (sub.isNotEmpty()) Text(sub, style = KType.small.copy(color = K.dim), maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                                if (e.navigation) KChevron()
                            }
                        }
                        feed?.next?.let { next ->
                            item {
                                KButton(stringResource(R.string.s_more), {
                                    scope.launch {
                                        loading = true
                                        val body = client.fetch(next, catalog.user, catalog.pass)
                                        val page = body?.let { Opds.parse(it, next) }
                                        loading = false
                                        // Appended with a dedupe and a cap: a catalog whose "next" points at
                                        // itself answers the same page forever, and the button must not be
                                        // able to grow the list without end.
                                        if (page == null) toast.show(unreachable)
                                        if (page != null) {
                                            val merged = Opds.appendPage(feed?.entries.orEmpty(), page)
                                            feed = (feed ?: page).copy(entries = merged.entries, next = merged.next)
                                        }
                                    }
                                }, style = KButtonStyle.Secondary, modifier = Modifier.padding(vertical = 12.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Add or edit one catalog. The password never leaves the phone and never enters a backup. */
@Composable
private fun CatalogForm(initial: Catalog?, onCancel: () -> Unit, onSave: (Catalog) -> Unit) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var url by remember { mutableStateOf(initial?.url ?: "") }
    var user by remember { mutableStateOf(initial?.user ?: "") }
    var pass by remember { mutableStateOf(initial?.pass ?: "") }
    Column(Modifier.fillMaxSize().padding(horizontal = Dim.screenPad.dp)) {
        KField(name, { name = it }, label = stringResource(R.string.s_catalog_name))
        Spacer(Modifier.height(10.dp))
        KField(url, { url = it }, placeholder = "http://192.168.1.10:8080/opds", label = stringResource(R.string.s_catalog_address))
        Spacer(Modifier.height(10.dp))
        KField(user, { user = it }, label = stringResource(R.string.s_catalog_username))
        Spacer(Modifier.height(10.dp))
        KField(pass, { pass = it }, label = stringResource(R.string.s_catalog_password))
        Text(stringResource(R.string.s_catalog_hint), style = KType.small.copy(color = K.dim), modifier = Modifier.padding(top = 12.dp))
        Spacer(Modifier.height(16.dp))
        KButton(stringResource(R.string.s_save), {
            val address = url.trim()
            if (address.startsWith("http://") || address.startsWith("https://")) {
                onSave(Catalog(name.trim().ifEmpty { address }, address, user.trim(), pass))
            }
        }, enabled = url.trim().startsWith("http"))
        Spacer(Modifier.height(8.dp))
        KButton(stringResource(R.string.s_cancel), onCancel, style = KButtonStyle.Secondary)
    }
}
