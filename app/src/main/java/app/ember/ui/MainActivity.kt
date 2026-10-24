package app.ember.ui

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.ember.container
import app.ember.data.DemoSeeder
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    /** Route requested by an intent (used by automated screenshot tests). */
    private val routeRequest = mutableStateOf<String?>(null)

    /** Test hook: pick the torrent folder through Android's folder picker. */
    private val torrentFolderPicker = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            } catch (e: SecurityException) {
            }
            lifecycleScope.launch { container.settings.setTorrentFolder(uri.toString(), uri.lastPathSegment?.substringAfterLast(':') ?: "Folder") }
        }
    }

    /** Test hook: add a library folder through Android's folder picker, then scan it. */
    private val libraryFolderPicker = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: SecurityException) {
            }
            container.appScope.launch {
                val dao = container.db.dao()
                val folder = dao.getFolderByUri(uri.toString()) ?: run {
                    val name = uri.lastPathSegment?.substringAfterLast(':')?.substringAfterLast('/') ?: "Folder"
                    val id = dao.insertFolder(app.ember.data.LibraryFolderEntity(uri = uri.toString(), name = name))
                    app.ember.data.LibraryFolderEntity(id = id, uri = uri.toString(), name = name)
                }
                container.scanner.scanFolder(folder)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Status bar icons follow the phone's light/dark setting.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        // Not again when Android rebuilds the screen (that would replay a shortcut's Resume).
        if (savedInstanceState == null) handleIntent(intent)
        Posture.watch(this)
        setContent {
            val vm: MainViewModel = viewModel()
            EmberRoot(
                vm = vm,
                routeRequest = routeRequest.value,
                onRouteHandled = { routeRequest.value = null },
            )
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // Light/dark switched while Ember was open: flip the status bar icons too.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        )
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        if (intent.getBooleanExtra("ember_demo", false)) {
            lifecycleScope.launch {
                try {
                    DemoSeeder.seed(applicationContext)
                } finally {
                    container.backup.applyPending()
                }
            }
        }
        // Emulator test hooks (see ci/smoke.sh); they only touch Ember's own app folder.
        intent.getStringExtra("ember_selfcheck")?.let { name ->
            lifecycleScope.launch { app.ember.data.SelfCheck.report(applicationContext, name) }
        }
        intent.getStringExtra("ember_export_notes")?.let { id ->
            lifecycleScope.launch { app.ember.data.SelfCheck.exportNotes(applicationContext, id) }
        }
        if (intent.getBooleanExtra("ember_wipe_restore_test", false)) {
            lifecycleScope.launch { app.ember.data.SelfCheck.wipeAndRestore(applicationContext) }
        }
        // Follows a show and opens its page (emulator screenshots).
        intent.getStringExtra("ember_follow")?.let { url ->
            lifecycleScope.launch {
                val id = container.podcasts.follow(url)
                if (id != null) routeRequest.value = "podcast/$id"
            }
        }
        if (intent.getBooleanExtra("ember_delete_demo_audio", false)) {
            app.ember.data.SelfCheck.deleteDemoAudio(applicationContext)
        }
        // A magnet link or .torrent file opened with Ember.
        if (intent.action == Intent.ACTION_VIEW) {
            intent.data?.let { data ->
                container.torrents.incoming.value = data.toString()
                routeRequest.value = "torrent_add"
            }
        }
        if (intent.getBooleanExtra("ember_pick_torrent_folder", false)) {
            torrentFolderPicker.launch(
                android.provider.DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Audiobooks"),
            )
        }
        intent.getStringExtra("ember_pick_library_folder")?.let { path ->
            libraryFolderPicker.launch(
                android.provider.DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:$path"),
            )
        }
        // Test hooks for Ember-21: seek, add a bookmark, a book's skips, merge and split.
        intent.getStringExtra("ember_seek")?.split(',')?.let { p ->
            if (p.size == 2) container.player.seekTo(p[0].toIntOrNull() ?: 0, p[1].toLongOrNull() ?: 0L)
        }
        intent.getStringExtra("ember_add_bookmark")?.split(',')?.takeIf { it.size == 2 }?.let { p ->
            container.appScope.launch { container.db.dao().insertBookmark(app.ember.data.BookmarkEntity(bookId = p[0], positionMs = p[1].toLongOrNull() ?: 0L, note = "test")) }
        }
        intent.getStringExtra("ember_book_skips")?.split(',')?.takeIf { it.size == 3 }?.let { p ->
            container.appScope.launch { container.db.dao().setSkips(p[0], p[1].toIntOrNull() ?: 0, p[2].toIntOrNull() ?: 0) }
        }
        intent.getStringExtra("ember_merge")?.let { ids ->
            val title = intent.getStringExtra("ember_merge_title") ?: ""
            container.appScope.launch {
                val all = container.db.dao().getBooks().associateBy { it.id }
                container.scanner.merge(ids.split(',').mapNotNull { all[it] }, title)
            }
        }
        // Test hooks for the profile tab: your name, and a photo (in Ember's own files folder) to crop.
        intent.getStringExtra("ember_profile_name")?.let { n -> container.appScope.launch { container.settings.setProfileName(n) } }
        // (The test can't put files in Ember's storage, so Ember draws a wide test picture itself.)
        if (intent.hasExtra("ember_profile_photo")) {
            val f = java.io.File(cacheDir, "avatar-test.jpg")
            try {
                val bmp = android.graphics.Bitmap.createBitmap(1200, 800, android.graphics.Bitmap.Config.ARGB_8888)
                val cv = android.graphics.Canvas(bmp)
                val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
                cv.drawColor(0xFF2E4A62.toInt())
                p.color = 0xFFE2683C.toInt(); cv.drawCircle(600f, 400f, 300f, p)
                p.color = 0xFFFBF5F0.toInt(); cv.drawCircle(500f, 330f, 50f, p); cv.drawCircle(700f, 330f, 50f, p)
                p.color = 0xFF1A0E09.toInt(); cv.drawRect(480f, 500f, 720f, 540f, p)
                java.io.FileOutputStream(f).use { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it) }
                app.ember.data.ProfilePhoto.testCrop.value = android.net.Uri.fromFile(f)
            } catch (e: Exception) {
            }
            routeRequest.value = "you"
        }
        intent.getStringExtra("ember_split")?.let { id -> container.appScope.launch { container.scanner.split(id) } }
        intent.getStringExtra("ember_torrent")?.let {
            container.torrents.testPickSmallest = intent.getBooleanExtra("ember_torrent_smallest", false)
            container.torrents.incoming.value = it
            routeRequest.value = "torrent_add"
        }
        // Test hook: how much of the unfolded screen the player column takes.
        if (intent.hasExtra("ember_pane")) {
            val f = intent.getFloatExtra("ember_pane", 0.33f)
            lifecycleScope.launch { container.settings.setPlayerPaneFraction(f) }
        }
        // Long-press shortcuts on Ember's home screen icon.
        when (intent.getStringExtra(Shortcuts.EXTRA)) {
            "resume" -> lifecycleScope.launch {
                val id = container.settings.current().lastBookId ?: return@launch
                container.player.playBook(id)
                routeRequest.value = "player"
            }
            "sleep" -> routeRequest.value = "sleep"
            "car" -> routeRequest.value = "car"
            "search" -> routeRequest.value = "search"
        }
        // Test hook: the "you finished it" sheet for a book.
        // Test hooks for the emulator checks (it can't fold, and sleep timers take minutes).
        if (intent.hasExtra("ember_tabletop")) Posture.forced.value = intent.getBooleanExtra("ember_tabletop", false)
        intent.getIntExtra("ember_sleep_seconds", 0).takeIf { it > 0 }?.let { app.ember.playback.SleepTimer.startSeconds(it) }
        intent.getStringExtra("ember_sleep_mark")?.split(',')?.takeIf { it.size == 3 }?.let { p ->
            lifecycleScope.launch {
                // "now" = whatever is playing.
                val id = if (p[0] == "now") container.settings.current().lastBookId ?: return@launch else p[0]
                app.ember.playback.SleepMarks.set(this@MainActivity, app.ember.playback.SleepMarks.Mark(id, p[1].toLong(), p[2].toLong(), System.currentTimeMillis()))
            }
        }
        if (intent.getBooleanExtra("ember_shake", false)) app.ember.playback.PlaybackService.testShake()
        // Flip to pause: "on"/"off" sets the setting, "down"/"up" stands in for turning the phone over.
        intent.getStringExtra("ember_flip")?.let { f ->
            when (f) {
                "on", "off" -> container.appScope.launch { container.settings.setFlipToPause(f == "on") }
                else -> app.ember.playback.PlaybackService.testFlip(f == "down")
            }
        }
        intent.getStringExtra("ember_finish_prompt")?.let { app.ember.playback.PlaybackStatus.finished.value = app.ember.playback.PlaybackStatus.Finished(it) }
        intent.getStringExtra("ember_route")?.let { routeRequest.value = it }
        intent.getStringExtra("ember_play")?.let { container.player.playBook(it) }
    }

    override fun onStart() {
        super.onStart()
        app.ember.playback.CarMode.appVisible = true
        container.player.connect()
        // Carry on with any torrent downloads that were interrupted.
        container.torrents.startIfNeeded(applicationContext)
    }

    override fun onStop() {
        app.ember.playback.CarMode.appVisible = false
        container.player.release()
        super.onStop()
    }
}
