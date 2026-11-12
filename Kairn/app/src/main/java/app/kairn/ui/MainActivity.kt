package app.kairn.ui

import android.content.Intent
import android.util.Log
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue   // required by `by` delegation; never appears as an identifier
import androidx.lifecycle.lifecycleScope
import app.kairn.KairnApp
import app.kairn.data.Book
import androidx.compose.runtime.remember
import app.kairn.ui.nav.AppShell
import app.kairn.ui.nav.ShellDeps
import app.kairn.ui.theme.KairnTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private val app get() = application as KairnApp

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The app chrome is always dark. The default (auto) style follows the SYSTEM theme, so in
        // light mode the status/nav icons would turn dark-on-dark.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        handleViewIntent(intent)
        setContent {
            val s by app.store.settings.collectAsState()
            // Built once: the shell must not see a new dependency object on every recomposition.
            val deps = remember(app) {
                ShellDeps(
                    store = app.store, library = app.library, backup = app.backup,
                    autoBackup = app.autoBackup, searchIndex = app.searchIndex,
                    positionsCache = app.positionsCache, readium = app.readium,
                )
            }
            KairnTheme(glass = s.glass, ambient = s.ambient) { AppShell(deps, ::openBook) }
        }
    }

    private var thumbsBackfilled = false
    private var metaBackfilled = false

    // L-01: at boot nothing should compete with the first frames: wait for the library read, let the
    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            app.store.awaitLoaded()
            delay(1_200)
            app.library.scan(force = false)
            if (!thumbsBackfilled) { thumbsBackfilled = true; app.library.backfillThumbs() }
            // Records imported before 1.0 have no series or description: one bounded pass per launch fills
            // them in, after the grid is already drawn.
            if (!metaBackfilled) {
                metaBackfilled = true
                val at = SystemClock.elapsedRealtime()
                val n = app.library.backfillMeta { done, total ->
                    if (done == total) Log.i("KairnPerf", "meta backfilled $done/$total in ${SystemClock.elapsedRealtime() - at}ms")
                }
                if (n > 0) Log.i("KairnPerf", "meta backfilled n=$n")
            }
            // After the scan, never before it: a scheduled backup should contain the library as it is now.
            if (app.autoBackup.runIfDue()) Log.i("KairnPerf", "auto backup written")
        }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handleViewIntent(intent) }

    private fun openBook(b: Book) =
        startActivity(Intent(this, ReaderActivity::class.java).putExtra(ReaderActivity.EXTRA_ID, b.id))

    /** L-03: "Open with". */
    private fun handleViewIntent(i: Intent?) {
        val uri: Uri = i?.takeIf { it.action == Intent.ACTION_VIEW }?.data ?: return
        lifecycleScope.launch { val b = app.library.importShared(uri); if (!b.broken) openBook(b) }
    }
}
