package app.kairn

import android.app.Application
import app.kairn.data.AutoBackup
import app.kairn.data.Backup
import app.kairn.data.Library
import app.kairn.data.ReadiumHelper
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
        autoBackup = AutoBackup(this, store, backup)
        searchIndex = SearchIndex(this, store, readium)
        // Reading + parsing every book file belongs on IO, not in Application.onCreate on the main thread.
        scope.launch { store.load() }
    }
}
