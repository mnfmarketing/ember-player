package app.ember

import android.app.Application
import android.content.Context
import app.ember.data.CoverStore
import app.ember.data.EmberDatabase
import app.ember.data.LibraryScanner
import app.ember.data.SettingsStore
import app.ember.downloads.Downloads
import app.ember.drive.DriveApi
import app.ember.drive.DriveAuth
import app.ember.playback.PlayerConnection
import app.ember.podcasts.MediaDatabase
import app.ember.podcasts.PodcastRepository
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class EmberApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        app.ember.data.CrashReports.install(this)
        container = AppContainer(this)
        PodcastRepository.schedule(this)
        app.ember.data.BackupManager.schedule(this)
        app.ember.playback.ResumeOnConnect.watch(this)
        container.torrents.init()
        app.ember.ui.Shortcuts.watch(this)
        container.appScope.launch { container.downloads.reconcile() }
        // Once: "Book - Track 029" style chapter names in the library become "Chapter 29".
        container.appScope.launch {
            val prefs = getSharedPreferences("upgrades", MODE_PRIVATE)
            if (!prefs.getBoolean("tidy_names_1", false)) {
                val dao = container.db.dao()
                runCatching { dao.getBooks().filter { !it.isExternal }.forEach { dao.tidyNames(it) } }
                prefs.edit().putBoolean("tidy_names_1", true).apply()
            }
        }
        // Once: books split into CD1 / CD2 / CD3 folders become one book (with your place, bookmarks and log).
        container.appScope.launch {
            val prefs = getSharedPreferences("upgrades", MODE_PRIVATE)
            if (!prefs.getBoolean("merge_discs_1", false)) {
                kotlinx.coroutines.delay(8_000)
                if (container.db.dao().getFolders().isNotEmpty()) runCatching { container.scanner.scanAll() }
                prefs.edit().putBoolean("merge_discs_1", true).apply()
            }
        }
        // A daily backup, even if Android postponed the nightly job.
        container.appScope.launch {
            kotlinx.coroutines.delay(20_000)
            val last = container.settings.current().lastBackupAt
            val hasData = container.db.dao().getBooks().isNotEmpty() || container.media.dao().getPodcasts().isNotEmpty()
            if (hasData && System.currentTimeMillis() - last > 26L * 3_600_000L) runCatching { container.backup.backUpNow() }
        }
    }
}

class AppContainer(context: Context) {
    val appContext: Context = context.applicationContext
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val db: EmberDatabase = EmberDatabase.build(appContext)
    val settings = SettingsStore(appContext)
    val covers = CoverStore(appContext)
    val driveAuth = DriveAuth(appContext)
    val drive = DriveApi(driveAuth)
    val scanner = LibraryScanner(appContext, db, covers, drive, driveAuth)
    val media: MediaDatabase = MediaDatabase.build(appContext)
    val downloads = Downloads(appContext)
    val podcasts = PodcastRepository(appContext, media, covers, downloads)
    val player = PlayerConnection(appContext)
    val backup = app.ember.data.BackupManager(appContext, this)
    val torrents = app.ember.torrent.TorrentEngine(appContext)

    init {
        // Books from a restored backup are matched as soon as their folder is scanned.
        scanner.afterScan = { backup.applyPending() }
    }
}

val Context.container: AppContainer
    get() = (applicationContext as EmberApp).container
