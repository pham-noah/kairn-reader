package app.kairn

import android.app.Application
import androidx.core.net.toUri
import app.kairn.data.AutoBackup
import app.kairn.data.Backup
import app.kairn.data.Library
import app.kairn.data.ReadiumHelper
import app.kairn.data.SafBackupFolder
import app.kairn.data.PositionsCache
import app.kairn.data.SearchIndex
import app.kairn.data.Store
import kotlinx.coroutines.launch

/** Manual wiring: no DI framework, no network, no analytics (DEC-3). */
class KairnApp : Application() {
    lateinit var store: Store; private set
    lateinit var readium: ReadiumHelper; private set
    lateinit var library: Library; private set
    lateinit var backup: Backup; private set
    lateinit var autoBackup: AutoBackup; private set
    lateinit var searchIndex: SearchIndex; private set
    lateinit var positionsCache: PositionsCache; private set

    /** Application-scoped work: the initial library read. Cancelled with the process. */
    private val scope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default,
    )

    override fun onCreate() {
        super.onCreate()
        store = Store(this)
        readium = ReadiumHelper(this)
        library = Library(this, store, readium)
        backup = Backup(this, store)
        autoBackup = AutoBackup(
            store = store,
            openFolder = { tree -> SafBackupFolder(this, tree) },
            write = { doc -> backup.write(doc.value.toUri(), includeBookFiles = false) },
        )
        searchIndex = SearchIndex(this, store, readium)
        positionsCache = PositionsCache(this)
        // A removed book takes its cached files with it: each cache registers itself, so the wiring is
        // additive and there is no single slot to leave unset.
        store.onBookRemoved { id -> searchIndex.forget(id); positionsCache.forget(id) }
        // Reading + parsing every book file belongs on IO, not in Application.onCreate on the main thread.
        scope.launch { store.load() }
    }
}
