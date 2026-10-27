package app.ember.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import app.ember.container
import app.ember.data.BookEntity
import app.ember.data.Settings
import app.ember.data.LibraryScanner
import app.ember.data.TrackEntity
import app.ember.drive.DriveApi
import app.ember.podcasts.EpisodeEntity
import app.ember.podcasts.PodcastEntity
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.cast.CastPlayer
import androidx.media3.cast.SessionAvailabilityListener
import android.media.AudioManager
import app.ember.cast.CastStatus
import app.ember.cast.LocalMediaServer
import app.ember.sound.SoundEngine
import app.ember.wear.WearLink
import com.google.android.gms.cast.framework.CastContext
import app.ember.ui.MainActivity
import app.ember.util.todayEpochDay
import app.ember.widget.EmberWidget
import app.ember.widget.WidgetBus
import androidx.glance.appwidget.updateAll
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.flatMapLatest
import java.io.File
import kotlin.math.sqrt

@UnstableApi
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PlaybackService : MediaLibraryService() {

    private lateinit var exo: ExoPlayer
    /** Whichever player is in charge right now: the phone, or a Chromecast. */
    private lateinit var active: Player
    private var castPlayer: CastPlayer? = null
    private var castServer: LocalMediaServer? = null
    private val sound = SoundEngine()
    private val casting: Boolean get() = castPlayer != null && active === castPlayer
    private lateinit var player: AudiobookPlayer
    private var session: MediaLibrarySession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val dao by lazy { container.db.dao() }
    private val media by lazy { container.media.dao() }
    private val isEpisode: Boolean get() = bookId?.startsWith(EPISODE_PREFIX) == true

    @Volatile
    private var settings = Settings()
    private var bookId: String? = null
    private var offsets = LongArray(0)
    private var trackDurations = LongArray(0)
    private var pausedAt = 0L
    private var listenAccumMs = 0L
    private var lastTickAt = 0L
    private var shake: ShakeDetector? = null
    private var flip: FlipDetector? = null
    /** Ember paused because the phone was turned face down (so turning it up resumes). */
    private var flippedPause = false
    private var flippedAt = 0L
    private var playReason = app.ember.data.ListenEventEntity.REASON_NONE
    private var flipStopJob: kotlinx.coroutines.Job? = null
    /** The show of the episode playing, for its own effects and intro/outro skips. */
    private var showFx: PodcastEntity? = null
    private var outroHandled = false
    private val showId = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    private val log by lazy { ListenLogger(dao) }
    /** Set just before Ember itself pauses or seeks, so the log says why (or skips it). */
    private var pauseReason = app.ember.data.ListenEventEntity.REASON_NONE
    private var quietSeek = false
    /** The book playing, for its own trim silence and voice boost. */
    private var bookFx: BookEntity? = null
    private val bookFxId = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    /** Where you were when you last set or shook the sleep timer (you were awake then). */
    private var lastAwakePos = -1L
    private var prevSleep = SleepTimer.State()
    private var shakeStopJob: kotlinx.coroutines.Job? = null
    /** Remembers when you paused, even if Android stops Ember overnight (for the rewind on resume). */
    private val pausePrefs by lazy { getSharedPreferences("playback", MODE_PRIVATE) }

    override fun onCreate() {
        super.onCreate()
        running = true
        val attrs = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
            .build()
        // Adds your Google sign-in to Drive streams; podcasts and local files pass straight through.
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent("Ember-Audiobooks/1.0")
            .setAllowCrossProtocolRedirects(true)
        val resolving = ResolvingDataSource.Factory(DefaultDataSource.Factory(this, http)) { spec ->
            if (spec.uri.host == "www.googleapis.com") {
                val t = container.driveAuth.tokenBlocking()
                if (t != null) spec.withAdditionalHeaders(mapOf("Authorization" to "Bearer $t")) else spec
            } else {
                spec
            }
        }
        // Start as soon as ~1 second is buffered, keep up to 2 minutes ahead while streaming.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, 120_000, 1_000, 3_000)
            .build()
        exo = ExoPlayer.Builder(this)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(DefaultMediaSourceFactory(resolving))
            .setAudioAttributes(attrs, true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        active = exo
        val audioSession = getSystemService(AudioManager::class.java).generateAudioSessionId()
        exo.setAudioSessionId(audioSession)
        sound.attach(audioSession, settings)
        player = wrap(exo)
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaLibrarySession.Builder(this, player, Callback())
            .setSessionActivity(openApp)
            .build()
        exo.addListener(listener)

        scope.launch {
            container.settings.flow.collect { s ->
                settings = s
                applyEffects()
                updateFlip()
            }
        }
        scope.launch {
            SleepTimer.state.collect {
                onSleepChanged(it)
                updateShake()
                publishWear()
            }
        }
        // Changing a book's own sound settings applies right away.
        scope.launch {
            bookFxId.flatMapLatest { id -> if (id == null) kotlinx.coroutines.flow.flowOf(null) else dao.observeBook(id) }
                .collect { b ->
                    if (b != null && b.id == bookId) {
                        bookFx = b
                        applyEffects()
                    }
                }
        }
        scope.launch { tickLoop() }
        scope.launch { wearPoke.collect { publishWear(force = true) } }
        scope.launch { shakePoke.collect { if (shake != null) onShake() } }
        scope.launch { flipPoke.collect { onFlip(it) } }
        scope.launch { clipWatch() }
        // Changing a show's settings applies right away to the episode that's playing.
        scope.launch {
            showId.flatMapLatest { id -> if (id == null) kotlinx.coroutines.flow.flowOf(null) else media.observePodcast(id) }
                .collect { p ->
                    if (p != null && p.id == showFx?.id) {
                        showFx = p
                        applyEffects()
                    }
                }
        }
        setUpCast()
    }

    private fun wrap(p: Player) = AudiobookPlayer(
        p,
        backMs = { settings.skipBackSec * 1000L },
        forwardMs = { settings.skipForwardSec * 1000L },
        trackDuration = { i -> trackDurations.getOrElse(i) { 0L } },
    )

    // ---- Chromecast ----

    private fun setUpCast() {
        val ctx = try {
            CastContext.getSharedInstance(this)
        } catch (e: Exception) {
            return
        }
        val cp = CastPlayer(ctx)
        castPlayer = cp
        cp.addListener(listener)
        cp.setSessionAvailabilityListener(object : SessionAvailabilityListener {
            override fun onCastSessionAvailable() {
                CastStatus.device.value = ctx.sessionManager.currentCastSession?.castDevice?.friendlyName ?: "Chromecast"
                scope.launch { switchTo(cp) }
            }

            override fun onCastSessionUnavailable() {
                CastStatus.device.value = null
                scope.launch { switchTo(exo) }
            }
        })
        if (cp.isCastSessionAvailable) {
            CastStatus.device.value = ctx.sessionManager.currentCastSession?.castDevice?.friendlyName ?: "Chromecast"
            scope.launch { switchTo(cp) }
        }
    }

    /** Moves whatever is playing between the phone and the Chromecast, keeping your place. */
    private suspend fun switchTo(target: Player) {
        val from = active
        if (from === target) return
        val count = from.mediaItemCount
        val items = (0 until count).map { from.getMediaItemAt(it) }
        val index = from.currentMediaItemIndex
        val position = from.currentPosition.coerceAtLeast(0)
        val wasPlaying = from.playWhenReady
        val speed = from.playbackParameters.speed
        if (count > 0) scope.launch { saveProgress() }
        from.pause()
        from.stop()

        active = target
        if (target === castPlayer) {
            castServer = (castServer ?: LocalMediaServer(applicationContext) { container.driveAuth.tokenBlocking() }).also { it.start() }
        } else {
            castServer?.stop()
        }
        session?.player = wrap(target)

        if (count > 0) {
            val restored = if (target === castPlayer) items.map { toCastItem(it) } else items.map { toPhoneItem(it) }
            target.setMediaItems(restored, index.coerceIn(0, restored.size - 1), position)
            target.setPlaybackSpeed(speed)
            target.prepare()
            target.playWhenReady = wasPlaying
        }
    }

    /** Items for whichever player is active. */
    private suspend fun forActive(items: List<MediaItem>): List<MediaItem> =
        if (casting) items.map { toCastItem(it) } else items

    /** Rewrites an item so a Chromecast can fetch it: public links stay, local and Drive files go through the phone. */
    private suspend fun toCastItem(item: MediaItem): MediaItem {
        val server = castServer ?: return item
        val uri = item.localConfiguration?.uri ?: return item
        val key = item.mediaId.replace(':', '_')
        val (url, mime) = when {
            (uri.scheme == "http" || uri.scheme == "https") && uri.host != "www.googleapis.com" ->
                uri.toString() to LocalMediaServer.mimeForName(uri.lastPathSegment ?: "")
            uri.host == "www.googleapis.com" -> {
                val fileId = uri.pathSegments.getOrNull(3) ?: ""
                val mime = container.drive.mimeType(fileId)
                server.urlFor(key, LocalMediaServer.Source.Drive(fileId, mime)) to mime
            }
            uri.scheme == "file" -> {
                val path = uri.path ?: ""
                val mime = LocalMediaServer.mimeForName(path)
                server.urlFor(key, LocalMediaServer.Source.FilePath(path, mime)) to mime
            }
            else -> {
                val mime = contentResolver.getType(uri)?.takeIf { it.startsWith("audio/") && it != "audio/x-m4b" }
                    ?: LocalMediaServer.mimeForName(uri.lastPathSegment ?: "")
                server.urlFor(key, LocalMediaServer.Source.Content(uri, mime)) to mime
            }
        }
        if (url == null) return item
        val meta = item.mediaMetadata
        val art = meta.artworkUri?.takeIf { it.scheme == "file" }?.path?.let { path ->
            server.urlFor("art_$key", LocalMediaServer.Source.FilePath(path, "image/jpeg"))
        }
        return item.buildUpon()
            .setUri(url)
            .setMimeType(mime)
            .setMediaMetadata(if (art != null) meta.buildUpon().setArtworkUri(Uri.parse(art)).build() else meta)
            .build()
    }

    /** Back to the phone: rebuild the item from its id so it plays from local files again. */
    private suspend fun toPhoneItem(item: MediaItem): MediaItem {
        val id = item.mediaId
        return when {
            id.startsWith(EPISODE_PREFIX) -> {
                val ep = media.getEpisode(id.removePrefix(EPISODE_PREFIX)) ?: return item
                episodeItem(ep, media.getPodcast(ep.podcastId))
            }
            id.startsWith("track:") -> resolveTrack(id) ?: item
            else -> item
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!active.playWhenReady || active.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        running = false
        // Tell the watch nothing is playing any more.
        val ctx = applicationContext
        container.appScope.launch {
            WearLink.publish(ctx, WearLink.Status(false, "", "", "", false, 0, false, 0, 0, null, 0, 0))
        }
        captureListening()
        flushListening()
        val id = bookId
        if (id != null && active.mediaItemCount > 0 && active.playbackState != Player.STATE_ENDED) {
            val pos = globalPosition()
            val episode = isEpisode
            container.appScope.launch {
                if (episode) {
                    media.updateEpisodePosition(id.removePrefix(EPISODE_PREFIX), pos, System.currentTimeMillis())
                } else {
                    dao.updateProgress(id, pos, System.currentTimeMillis(), BookEntity.STATUS_IN_PROGRESS)
                }
            }
        }
        shake?.stop()
        shake = null
        flip?.stop()
        flip = null
        sound.release()
        castPlayer?.setSessionAvailabilityListener(null)
        castPlayer?.release()
        castServer?.stop()
        CastStatus.device.value = null
        PlaybackStatus.isPlaying.value = false
        PlaybackStatus.loadedId.value = null
        session?.release()
        session = null
        exo.removeListener(listener)
        exo.release()
        scope.cancel()
        container.appScope.launch { refreshWidget(applicationContext) }
        super.onDestroy()
    }

    // ---- Player events ----

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            publishWear()
            val id = mediaItem?.mediaId?.let { bookIdFromTrack(it) } ?: return
            if (id != bookId && !id.startsWith(EPISODE_PREFIX)) scope.launch { loadTrackState(id) }
            // The next file started by itself: skip the book's repeated intro.
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && id == bookId) skipIntoFile(active.currentMediaItemIndex)
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            val item = active.currentMediaItem
            val uri = item?.localConfiguration?.uri
            val source = when {
                uri == null -> PlaybackIssues.Source.LOCAL
                uri.host == "www.googleapis.com" -> PlaybackIssues.Source.DRIVE
                isEpisode && uri.scheme == "file" -> PlaybackIssues.Source.EPISODE_DOWNLOAD
                isEpisode -> PlaybackIssues.Source.EPISODE_STREAM
                else -> PlaybackIssues.Source.LOCAL
            }
            PlaybackIssues.report(PlaybackIssues.describe(error, bookId, source))
            scope.launch { saveProgress() }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            PlaybackStatus.isPlaying.value = isPlaying
            if (isPlaying) PlaybackIssues.clear()
            if (isPlaying) {
                lastTickAt = SystemClock.elapsedRealtime()
            } else {
                captureListening()
                flushListening()
                if (active.playbackState != Player.STATE_ENDED) scope.launch { saveProgress() }
                // Your other devices get your place a few seconds after you pause.
                container.sync.request("pause", 4_000)
            }
            scope.launch { refreshWidget(applicationContext) }
            publishWear()
            updateFlip()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (playWhenReady) {
                smartRewind()
                maybeBedtime()
            } else {
                rememberPause()
            }
            val id = loggableId() ?: return
            val pos = globalPosition()
            val speed = active.playbackParameters.speed
            if (playWhenReady) {
                pauseReason = app.ember.data.ListenEventEntity.REASON_NONE
                val why = playReason
                playReason = app.ember.data.ListenEventEntity.REASON_NONE
                // Played some other way: turning the phone up no longer means anything.
                if (why != app.ember.data.ListenEventEntity.REASON_FLIPPED) flippedPause = false
                scope.launch { log.play(id, pos, speed, why) }
            } else {
                val why = when {
                    pauseReason != app.ember.data.ListenEventEntity.REASON_NONE -> pauseReason
                    reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY -> app.ember.data.ListenEventEntity.REASON_UNPLUGGED
                    reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS -> app.ember.data.ListenEventEntity.REASON_OTHER_APP
                    reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM -> app.ember.data.ListenEventEntity.REASON_FINISHED
                    else -> app.ember.data.ListenEventEntity.REASON_NONE
                }
                pauseReason = app.ember.data.ListenEventEntity.REASON_NONE
                // Paused by the sleep timer: the log also notes where you were last awake.
                val awake = if (why == app.ember.data.ListenEventEntity.REASON_SLEEP) lastAwakePos else -1L
                scope.launch { log.pause(id, pos, speed, why, awake) }
            }
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            // The listening log: skips, the scrubber and chapter jumps.
            if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                val quiet = quietSeek
                quietSeek = false
                val id = loggableId()
                if (id != null && !quiet) {
                    val from = offsets.getOrElse(oldPosition.mediaItemIndex) { 0L } + oldPosition.positionMs
                    val to = offsets.getOrElse(newPosition.mediaItemIndex) { 0L } + newPosition.positionMs
                    val speed = active.playbackParameters.speed
                    scope.launch {
                        val e = log.moved(id, from, to, speed, settings.skipBackSec * 1000L, settings.skipForwardSec * 1000L)
                        if (e != null && e.kind == app.ember.data.ListenEventEntity.JUMP && kotlin.math.abs(e.toMs - e.positionMs) >= 30_000) {
                            PlaybackStatus.jumped.value = PlaybackStatus.Jump(id, e.positionMs, e.toMs)
                        }
                    }
                }
            }
            // Jumping away from a clip you're previewing ends the preview.
            PlaybackStatus.clipRange.value?.let { (start, end) ->
                val pos = globalPosition()
                if (reason == Player.DISCONTINUITY_REASON_SEEK && (pos < start - 2_000 || pos > end)) PlaybackStatus.clipRange.value = null
            }
            // Skips and chapter jumps: save right away so the widget shows the new spot.
            if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                scope.launch {
                    saveProgress()
                    refreshWidget(applicationContext)
                }
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) scope.launch { markFinished() }
        }
    }

    private suspend fun tickLoop() {
        var ticks = 0
        while (true) {
            delay(1000)
            if (active.isPlaying) {
                captureListening()
                ticks++
                if (ticks % 5 == 0) saveProgress()
                // And every 5 minutes while playing.
                if (ticks % 300 == 0) container.sync.request("playing", 1_000)
                // Keep the home-screen widget's progress and time left current.
                if (ticks % 30 == 0) refreshWidget(applicationContext)
                if (ticks % 10 == 0) publishWear()
                if (listenAccumMs >= 15_000) flushListening()
            } else {
                lastTickAt = 0
            }
            enforceSleepTimer()
        }
    }

    /** Trim silence and voice boost: the show's or book's own choice if it has one, otherwise the app's. */
    private fun applyEffects() {
        val show = showFx?.takeIf { isEpisode && it.customEffects }
        val book = bookFx?.takeIf { !isEpisode && it.customEffects && it.id == bookId }
        val s = when {
            show != null -> settings.copy(skipSilence = show.trimSilence, narrationBoost = show.voiceBoost)
            book != null -> settings.copy(skipSilence = book.trimSilence, narrationBoost = book.voiceBoost)
            else -> settings
        }
        exo.skipSilenceEnabled = s.skipSilence
        sound.apply(s)
    }

    /** Skips a show's outro: treats the episode as finished that many seconds before the end. */
    private suspend fun outroWatch() {
        val outro = (showFx?.skipOutroSec ?: 0) * 1000L
        if (!isEpisode || outro <= 0 || outroHandled || !active.isPlaying) return
        val d = active.duration
        if (d > outro && active.currentPosition >= d - outro) {
            outroHandled = true
            if (media.getQueue().isEmpty()) {
                pauseReason = app.ember.data.ListenEventEntity.REASON_FINISHED
                active.pause()
            }
            markFinished()
        }
    }

    /** A book's own intro skip, in ms (0 when off or not a book). */
    private fun bookIntroMs(): Long = bookFx?.takeIf { !isEpisode && it.id == bookId }?.skipIntroSec?.times(1000L) ?: 0L
    private fun bookOutroMs(): Long = bookFx?.takeIf { !isEpisode && it.id == bookId }?.skipOutroSec?.times(1000L) ?: 0L

    /** Starts file [index] past the book's repeated intro (only for files long enough to have one). */
    private fun skipIntoFile(index: Int) {
        val intro = bookIntroMs()
        if (intro <= 0 || index < 0) return
        val d = trackDurations.getOrElse(index) { 0L }
        if (d > 0 && d < intro + 10_000) return
        if (active.currentPosition >= intro) return
        quietSeek = true
        active.seekTo(index, intro)
    }

    private var outroIndex = -1
    private var outroPos = -1L

    /**
     * Skips a book's repeated outro: when playback runs into the last seconds of a file, it moves on to the
     * next file (past its intro), or to the very end on the last one. Only when it gets there by playing:
     * scrubbing or jumping into those seconds stays where you chose.
     */
    private fun bookOutroWatch() {
        val outro = bookOutroMs()
        if (outro <= 0 || !active.isPlaying || PlaybackStatus.clipRange.value != null) {
            outroIndex = -1
            return
        }
        val idx = active.currentMediaItemIndex
        val pos = active.currentPosition
        val d = trackDurations.getOrElse(idx) { 0L }.takeIf { it > 0 } ?: active.duration.takeIf { it > 0 } ?: return
        val prevIdx = outroIndex
        val prevPos = outroPos
        outroIndex = idx
        outroPos = pos
        if (d < outro + 10_000) return
        val zone = d - outro
        val playedInto = prevIdx == idx && prevPos in 0 until zone && pos >= zone && pos - prevPos < 3_000
        if (!playedInto) return
        outroIndex = -1
        quietSeek = true
        if (idx + 1 < active.mediaItemCount) {
            val intro = bookIntroMs()
            val nd = trackDurations.getOrElse(idx + 1) { 0L }
            active.seekTo(idx + 1, if (intro > 0 && (nd == 0L || nd >= intro + 10_000)) intro else 0L)
        } else {
            active.seekTo(idx, d)
        }
    }

    /** Stops a clip preview at the clip's end. */
    private suspend fun clipWatch() {
        while (true) {
            delay(200)
            outroWatch()
            bookOutroWatch()
            val (_, end) = PlaybackStatus.clipRange.value ?: continue
            if (active.mediaItemCount > 0 && active.isPlaying && globalPosition() >= end) {
                active.pause()
                PlaybackStatus.clipRange.value = null
            }
        }
    }

    private fun captureListening() {
        val now = SystemClock.elapsedRealtime()
        if (lastTickAt > 0) listenAccumMs += (now - lastTickAt).coerceIn(0, 5_000)
        lastTickAt = if (active.isPlaying) now else 0
    }

    private fun flushListening() {
        val ms = listenAccumMs
        listenAccumMs = 0
        // Podcasts don't count toward your book listening stats.
        if (ms <= 0 || isEpisode) return
        val day = todayEpochDay()
        container.appScope.launch { dao.addListening(day, ms) }
    }

    // ---- Galaxy Watch ----

    private var wearJob: kotlinx.coroutines.Job? = null

    /** Sends what's playing to the watch app (WearLink skips it when there's no watch). */
    private fun publishWear(force: Boolean = false) {
        wearJob?.cancel()
        wearJob = scope.launch {
            delay(250)
            val has = active.mediaItemCount > 0
            val meta = active.mediaMetadata
            val pos = if (has) globalPosition() else 0L
            val chapter = if (has) currentChapters().lastOrNull { it.first <= pos }?.second ?: "" else ""
            val total = (offsets.lastOrNull() ?: 0L) + (trackDurations.lastOrNull() ?: 0L)
            val sleep = SleepTimer.state.value
            val status = WearLink.Status(
                hasMedia = has,
                title = meta.title?.toString() ?: "",
                author = meta.artist?.toString() ?: "",
                chapter = chapter,
                playing = active.playWhenReady,
                sleepEndsAt = sleep.endsAt ?: 0L,
                sleepChapter = sleep.stopAtPositionMs != null,
                backSec = settings.skipBackSec,
                forwardSec = settings.skipForwardSec,
                coverPath = meta.artworkUri?.takeIf { it.scheme == "file" }?.path,
                positionMs = pos,
                durationMs = total,
            )
            WearLink.publish(applicationContext, status, force)
        }
    }

    /** Commands from the watch (through WearLink): bookmark, resume, sleep timer, chapters. */
    private suspend fun handleCustom(action: String, args: android.os.Bundle) {
        when (action) {
            WearLink.CMD_BOOKMARK -> bookmarkHere()
            WearLink.CMD_CHAPTER -> jumpChapter(if (args.getInt("dir", 1) < 0) -1 else 1)
            WearLink.CMD_RESUME -> {
                if (active.mediaItemCount == 0) {
                    val id = container.settings.current().lastBookId ?: return
                    val r = (if (id.startsWith(EPISODE_PREFIX)) resolveEpisode(id.removePrefix(EPISODE_PREFIX)) else resolveBook(id)) ?: return
                    active.setMediaItems(forActive(r.mediaItems), r.startIndex, r.startPositionMs)
                    active.prepare()
                }
                active.play()
            }
            WearLink.CMD_SLEEP -> {
                val m = args.getInt("minutes", 0)
                when {
                    m > 0 -> SleepTimer.start(m)
                    m < 0 -> if (active.mediaItemCount > 0) chapterEndAfter(globalPosition())?.let { SleepTimer.endOfChapter(it) }
                    else -> SleepTimer.cancel()
                }
            }
        }
        publishWear(force = true)
    }

    private fun enforceSleepTimer() {
        val s = SleepTimer.state.value
        val endsAt = s.endsAt
        val stopAt = s.stopAtPositionMs
        when {
            endsAt != null -> {
                val rem = endsAt - System.currentTimeMillis()
                when {
                    rem <= 0 -> sleepNow()
                    // A gentle fade over the last seconds, only if you turned it on.
                    settings.sleepFade && rem < 10_000 && !casting -> exo.volume = (rem / 10_000f).coerceIn(0.05f, 1f)
                    exo.volume < 1f -> exo.volume = 1f
                }
            }
            stopAt != null -> {
                if (active.mediaItemCount > 0 && globalPosition() >= stopAt - 400) sleepNow()
            }
            exo.volume < 1f -> exo.volume = 1f
        }
    }

    /** The sleep timer ran out: pause, and remember where you were last awake. */
    private fun sleepNow() {
        val id = loggableId()
        val stopped = if (active.mediaItemCount > 0) globalPosition() else 0L
        pauseReason = app.ember.data.ListenEventEntity.REASON_SLEEP
        active.pause()
        exo.volume = 1f
        SleepTimer.expire()
        if (id != null && lastAwakePos >= 0 && stopped - lastAwakePos > 60_000) {
            SleepMarks.set(applicationContext, SleepMarks.Mark(id, lastAwakePos, stopped, System.currentTimeMillis()))
        }
        // A shake in the next few minutes picks up again (see onShake).
        updateShake()
    }

    /** A timer was set, restarted or moved on: you're awake right now. */
    private fun onSleepChanged(s: SleepTimer.State) {
        val prev = prevSleep
        prevSleep = s
        if (!s.active) return
        val renewed = !prev.active || (s.endsAt ?: 0) > (prev.endsAt ?: 0) + 1_000 || s.stopAtPositionMs != prev.stopAtPositionMs
        if (renewed && active.mediaItemCount > 0) lastAwakePos = globalPosition()
    }

    /**
     * Flip to pause: the motion sensors are only watched while a book plays with the setting on,
     * and for up to 30 minutes after a face-down pause (to notice it being turned back up).
     */
    private fun updateFlip() {
        val want = settings.flipToPause && active.mediaItemCount > 0 && (active.isPlaying || flippedPause)
        if (want && flip == null) {
            flip = FlipDetector(this) { down -> scope.launch { onFlip(down) } }.also { it.start() }
        } else if (!want && flip != null) {
            flip?.stop()
            flip = null
        }
    }

    private fun onFlip(faceDown: Boolean) {
        if (!settings.flipToPause || CarMode.screenShowing) return
        if (faceDown) {
            if (!active.playWhenReady || active.mediaItemCount == 0) return
            pauseReason = app.ember.data.ListenEventEntity.REASON_FLIPPED
            flippedPause = true
            flippedAt = System.currentTimeMillis()
            active.pause()
            flipStopJob?.cancel()
            flipStopJob = scope.launch {
                delay(FLIP_RESUME_MS)
                flippedPause = false
                updateFlip()
            }
        } else if (flippedPause) {
            flippedPause = false
            flipStopJob?.cancel()
            if (!active.playWhenReady && System.currentTimeMillis() - flippedAt < FLIP_RESUME_MS) {
                playReason = app.ember.data.ListenEventEntity.REASON_FLIPPED
                if (active.playbackState == Player.STATE_IDLE) active.prepare()
                active.play()
            }
        }
        updateFlip()
    }

    /** Shake detection runs while a timer is on, and for 5 minutes after one runs out. */
    private fun updateShake() {
        val justExpired = SleepTimer.lastExpired != null && System.currentTimeMillis() - SleepTimer.expiredAt < SHAKE_AFTER_MS
        val want = SleepTimer.state.value.active || justExpired
        if (want && shake == null) {
            shake = ShakeDetector(this) { scope.launch { onShake() } }.also { it.start() }
        } else if (!want) {
            shake?.stop()
            shake = null
        }
        shakeStopJob?.cancel()
        if (want && !SleepTimer.state.value.active) {
            shakeStopJob = scope.launch {
                delay(SHAKE_AFTER_MS)
                updateShake()
            }
        }
    }

    /**
     * Shake the phone:
     *  - timer running: it starts over (end of chapter moves on to the end of the next chapter)
     *  - timer just ran out: playback picks up again with the same timer
     * A short beep says it worked.
     */
    private suspend fun onShake() {
        val s = SleepTimer.state.value
        when {
            s.endsAt != null -> if (SleepTimer.extend()) exo.volume = 1f else return
            s.stopAtPositionMs != null -> SleepTimer.endOfChapter(chapterEndAfter(s.stopAtPositionMs + 1_000) ?: return)
            else -> {
                val last = SleepTimer.lastExpired ?: return
                if (System.currentTimeMillis() - SleepTimer.expiredAt > SHAKE_AFTER_MS || active.mediaItemCount == 0) return
                SleepTimer.forgetExpired()
                if (last.durationMs > 0) {
                    SleepTimer.start((last.durationMs / 60_000L).toInt().coerceAtLeast(1), auto = last.auto)
                } else {
                    chapterEndAfter(globalPosition())?.let { SleepTimer.endOfChapter(it) }
                }
                active.play()
            }
        }
        beep(android.media.ToneGenerator.TONE_PROP_BEEP, 150)
    }

    /** The end of the chapter playing at [pos] (the next chapter's start, or the end of the book). */
    private suspend fun chapterEndAfter(pos: Long): Long? {
        val starts = currentChapters().map { it.first }
        val next = starts.firstOrNull { it > pos }
        if (next != null) return next
        val total = (offsets.lastOrNull() ?: 0L) + (trackDurations.lastOrNull() ?: 0L)
        return total.takeIf { it > pos }
    }

    private suspend fun beep(tone: Int, ms: Int) {
        try {
            val t = android.media.ToneGenerator(AudioManager.STREAM_MUSIC, 60)
            t.startTone(tone, ms)
            delay(ms + 350L)
            t.release()
        } catch (e: Exception) {
        }
    }

    /** Bedtime: starting to listen at night turns the sleep timer on by itself. */
    private fun maybeBedtime() {
        val st = settings
        if (!st.bedtimeOn || SleepTimer.state.value.active || casting) return
        val now = System.currentTimeMillis()
        // Turned it off yourself tonight: leave it off.
        if (now - SleepTimer.userCancelledAt < 10 * 3_600_000L) return
        val cal = java.util.Calendar.getInstance()
        val m = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
        val inWindow = if (st.bedtimeFromMin <= st.bedtimeToMin) m in st.bedtimeFromMin until st.bedtimeToMin else m >= st.bedtimeFromMin || m < st.bedtimeToMin
        if (!inWindow) return
        SleepTimer.start(st.bedtimeTimerMin, auto = true)
        PlaybackStatus.notice.value = "Bedtime: sleep timer on for ${st.bedtimeTimerMin} minutes. Shake to add more."
    }

    private fun rememberPause() {
        val id = bookId ?: return
        pausedAt = System.currentTimeMillis()
        pausePrefs.edit().putLong("pausedAt", pausedAt).putString("pausedBook", id).apply()
    }

    /**
     * Smart rewind: picking up after a pause goes back a little, more the longer the break
     * (nothing after a few seconds, half your setting after a short pause, your setting after
     * a few minutes, double after an hour, triple after a night).
     */
    private fun smartRewind() {
        val id = bookId
        val since = when {
            pausedAt > 0 -> pausedAt
            id != null && pausePrefs.getString("pausedBook", null) == id -> pausePrefs.getLong("pausedAt", 0L)
            else -> 0L
        }
        pausedAt = 0
        pausePrefs.edit().remove("pausedAt").remove("pausedBook").apply()
        val base = settings.autoRewindSec * 1000L
        if (since <= 0 || base <= 0 || active.mediaItemCount == 0) return
        val gap = System.currentTimeMillis() - since
        val rewind = rewindFor(gap, base)
        if (rewind <= 0) return
        val target = (globalPosition() - rewind).coerceAtLeast(0)
        var idx = 0
        for (i in offsets.indices) if (offsets[i] <= target) idx = i
        quietSeek = true
        active.seekTo(idx, (target - offsets.getOrElse(idx) { 0L }).coerceAtLeast(0))
        // After a real break, say so, so the jump back doesn't look like a glitch.
        if (gap >= 5 * 60_000L) PlaybackStatus.notice.value = "Went back ${rewind / 1000}s since you paused"
    }

    // ---- Headphone and car buttons ----

    private var presses = 0
    private var pressJob: kotlinx.coroutines.Job? = null
    private var longFired = false

    /**
     * Wired headphones' single button: one press plays or pauses, two and three presses and a
     * long press do what you chose in Settings. Next and previous (earbuds, steering wheel) too.
     */
    private fun handleMediaKey(ev: android.view.KeyEvent): Boolean {
        if (active.mediaItemCount == 0) return false
        when (ev.keyCode) {
            android.view.KeyEvent.KEYCODE_HEADSETHOOK, android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                if (ev.repeatCount > 0) {
                    // Held down: Android repeats the press while the button stays down.
                    if (!longFired) {
                        longFired = true
                        pressJob?.cancel()
                        presses = 0
                        runButtonAction(settings.btnLong)
                    }
                    return true
                }
                longFired = false
                if (settings.btnDouble == ButtonActions.NOTHING && settings.btnTriple == ButtonActions.NOTHING) {
                    togglePlayback()
                    return true
                }
                presses++
                pressJob?.cancel()
                pressJob = scope.launch {
                    delay(450)
                    val n = presses
                    presses = 0
                    when {
                        n <= 1 -> togglePlayback()
                        n == 2 -> runButtonAction(settings.btnDouble)
                        else -> runButtonAction(settings.btnTriple)
                    }
                }
                return true
            }
            android.view.KeyEvent.KEYCODE_MEDIA_NEXT -> {
                if (ev.repeatCount == 0) runButtonAction(settings.btnNext)
                return true
            }
            android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                if (ev.repeatCount == 0) runButtonAction(settings.btnPrevious)
                return true
            }
        }
        return false
    }

    private fun togglePlayback() {
        val p = session?.player ?: return
        if (p.playWhenReady) {
            p.pause()
        } else {
            if (p.playbackState == Player.STATE_IDLE) p.prepare()
            p.play()
        }
    }

    private fun runButtonAction(action: Int) {
        val p = session?.player ?: return
        when (action) {
            ButtonActions.SKIP_BACK -> p.seekBack()
            ButtonActions.SKIP_FORWARD -> p.seekForward()
            ButtonActions.BOOKMARK -> scope.launch { bookmarkHere() }
            ButtonActions.NEXT_CHAPTER -> scope.launch { jumpChapter(+1) }
            ButtonActions.PREVIOUS_CHAPTER -> scope.launch { jumpChapter(-1) }
        }
    }

    /** Chapter starts and titles for what's playing. */
    private suspend fun currentChapters(): List<Pair<Long, String>> {
        val id = bookId ?: return emptyList()
        return if (isEpisode) {
            media.getEpisodeChapters(id.removePrefix(EPISODE_PREFIX)).map { it.startMs to it.title }
        } else {
            dao.getChapters(id).map { it.startMs to it.title }
        }.sortedBy { it.first }
    }

    private suspend fun jumpChapter(direction: Int) {
        val chapters = currentChapters()
        if (chapters.size < 2) {
            if (direction > 0) session?.player?.seekForward() else session?.player?.seekBack()
            return
        }
        val pos = globalPosition()
        val i = chapters.indexOfLast { it.first <= pos }.coerceAtLeast(0)
        val target = if (direction > 0) {
            chapters.getOrNull(i + 1)?.first ?: return
        } else if (pos - chapters[i].first > 3_000 || i == 0) {
            chapters[i].first
        } else {
            chapters[i - 1].first
        }
        val idx = offsets.indexOfLast { it <= target }.coerceAtLeast(0)
        active.seekTo(idx, (target - offsets.getOrElse(idx) { 0L }).coerceAtLeast(0))
    }

    /** A bookmark from your headphones: a short beep says it's saved. */
    private suspend fun bookmarkHere() {
        val id = bookId ?: return
        val pos = globalPosition()
        val title = currentChapters().lastOrNull { it.first <= pos }?.second ?: ""
        dao.insertBookmark(app.ember.data.BookmarkEntity(bookId = id, positionMs = pos, chapterTitle = title))
        try {
            val tone = android.media.ToneGenerator(AudioManager.STREAM_MUSIC, 60)
            tone.startTone(android.media.ToneGenerator.TONE_PROP_ACK, 250)
            delay(600)
            tone.release()
        } catch (e: Exception) {
        }
        PlaybackStatus.notice.value = "Bookmarked at ${app.ember.util.formatClock(pos)}"
    }

    /** The book or episode now loaded, when its track layout is known (so positions are right). */
    private fun loggableId(): String? {
        val id = bookId ?: return null
        if (active.mediaItemCount == 0) return null
        val current = active.currentMediaItem?.mediaId?.let { bookIdFromTrack(it) } ?: return null
        return id.takeIf { it == current }
    }

    private fun globalPosition(): Long =
        offsets.getOrElse(active.currentMediaItemIndex) { 0L } + active.currentPosition.coerceAtLeast(0)

    private suspend fun saveProgress() {
        val id = bookId ?: return
        if (active.mediaItemCount == 0) return
        if (isEpisode) {
            val epId = id.removePrefix(EPISODE_PREFIX)
            media.updateEpisodePosition(epId, active.currentPosition.coerceAtLeast(0), System.currentTimeMillis())
            if (active.duration > 0) media.fillDuration(epId, active.duration)
            return
        }
        dao.updateProgress(id, globalPosition(), System.currentTimeMillis(), BookEntity.STATUS_IN_PROGRESS)
    }

    private suspend fun markFinished() {
        val id = bookId ?: return
        if (isEpisode) {
            container.podcasts.setPlayed(id.removePrefix(EPISODE_PREFIX), true)
            // Up Next: roll straight into the next episode.
            val next = media.getQueue().firstOrNull() ?: return
            val r = resolveEpisode(next.id) ?: return
            active.setMediaItems(forActive(r.mediaItems), r.startIndex, r.startPositionMs)
            active.prepare()
            active.play()
            return
        }
        val book = dao.getBook(id) ?: return
        val now = System.currentTimeMillis()
        dao.updateBook(
            book.copy(
                status = BookEntity.STATUS_FINISHED,
                finishedAt = now,
                lastPlayedAt = now,
                positionMs = book.durationMs,
            ),
        )
        refreshWidget(applicationContext)
        PlaybackStatus.finished.value = PlaybackStatus.Finished(book.id)
        // Up Next comes first, then the next book in the series.
        val queued = settings.upNext.filter { it != book.id }
        for (nextId in queued) {
            val next = dao.getBook(nextId)
            if (next == null || next.isExternal) continue
            val r = resolveBook(nextId) ?: continue
            active.setMediaItems(forActive(r.mediaItems), r.startIndex, r.startPositionMs)
            active.prepare()
            active.play()
            PlaybackStatus.notice.value = "Up Next: ${next.title}"
            return
        }
        if (settings.seriesAutoPlay) playNextInSeries(book)
    }

    /** Finished a book in a series: start the next unfinished one. */
    private suspend fun playNextInSeries(book: BookEntity) {
        val next = nextInSeries(book, dao.getBooks()) ?: return
        val r = resolveBook(next.id) ?: return
        active.setMediaItems(forActive(r.mediaItems), r.startIndex, r.startPositionMs)
        active.prepare()
        active.play()
        PlaybackStatus.notice.value = "Next in ${book.series}: ${next.title}"
    }

    private suspend fun loadTrackState(id: String) {
        val tracks = dao.getTracks(id)
        applyTrackState(id, tracks)
        bookFx = dao.getBook(id)
        bookFxId.value = id
        applyEffects()
        container.settings.setLastBook(id)
    }

    private fun applyTrackState(id: String, tracks: List<TrackEntity>) {
        bookId = id
        PlaybackStatus.loadedId.value = id
        trackDurations = LongArray(tracks.size) { tracks[it].durationMs }
        var acc = 0L
        offsets = LongArray(tracks.size) { i ->
            val o = acc
            acc += tracks[i].durationMs
            o
        }
    }

    // ---- Media items ----

    private suspend fun resolveBook(id: String): MediaSession.MediaItemsWithStartPosition? {
        val book = dao.getBook(id) ?: return null
        if (book.isExternal) return null
        val tracks = dao.getTracks(id)
        if (tracks.isEmpty()) return null
        applyTrackState(id, tracks)
        showFx = null
        showId.value = null
        bookFx = book
        bookFxId.value = id
        applyEffects()
        // Playing a book takes it off Up Next.
        if (id in settings.upNext) container.appScope.launch { container.settings.editUpNext { it - id } }
        val restart = book.status == BookEntity.STATUS_FINISHED || book.positionMs >= book.durationMs - 1_000
        var start = if (restart) 0L else book.positionMs
        // Starting from the very top skips the book's repeated intro too.
        val intro = book.skipIntroSec * 1000L
        if (start == 0L && intro > 0 && tracks.first().durationMs.let { it == 0L || it >= intro + 10_000 }) start = intro
        var idx = 0
        for (i in offsets.indices) if (offsets[i] <= start) idx = i
        active.setPlaybackSpeed(book.speed)
        container.settings.setLastBook(id)
        if (restart) dao.updateProgress(id, 0, System.currentTimeMillis(), BookEntity.STATUS_IN_PROGRESS)
        val items = tracks.map { trackItem(book, it) }
        if (book.source == BookEntity.SOURCE_DRIVE) autoDownloadDrive(book, tracks)
        return MediaSession.MediaItemsWithStartPosition(items, idx, (start - offsets[idx]).coerceAtLeast(0))
    }

    /** Saves the Drive book you're listening to for offline play, on Wi-Fi only. */
    private fun autoDownloadDrive(book: BookEntity, tracks: List<TrackEntity>) {
        container.appScope.launch {
            val token = container.driveAuth.token() ?: return@launch
            tracks.forEach { t ->
                val fileId = t.uri.removePrefix(LibraryScanner.DRIVE_TRACK_PREFIX)
                if (container.downloads.driveLocal(fileId) == null) {
                    container.downloads.downloadDriveFile(fileId, token, "${book.title} · ${t.title}", wifiOnly = true)
                }
            }
        }
    }

    private suspend fun resolveEpisode(epId: String): MediaSession.MediaItemsWithStartPosition? {
        val ep = media.getEpisode(epId) ?: return null
        val show = media.getPodcast(ep.podcastId)
        bookId = EPISODE_PREFIX + epId
        PlaybackStatus.loadedId.value = EPISODE_PREFIX + epId
        trackDurations = longArrayOf(ep.durationMs)
        offsets = longArrayOf(0L)
        active.setPlaybackSpeed(show?.speed ?: 1f)
        container.settings.setLastBook(EPISODE_PREFIX + epId)
        media.removeFromQueue(epId)
        if (ep.chaptersState == 0) container.appScope.launch { container.podcasts.loadChapters(ep.id) }
        showFx = show
        showId.value = show?.id
        bookFx = null
        bookFxId.value = null
        outroHandled = false
        applyEffects()
        val nearEnd = ep.durationMs > 0 && ep.positionMs >= ep.durationMs - 2_000
        var start = if (ep.played || nearEnd) 0L else ep.positionMs
        // Skip the show's intro when starting from the top.
        val intro = (show?.skipIntroSec ?: 0) * 1000L
        if (start == 0L && intro > 0 && (ep.durationMs == 0L || intro < ep.durationMs)) start = intro
        if (ep.played || ep.archived) media.updateEpisode(ep.copy(played = false, playedAt = 0, positionMs = if (ep.played) 0 else ep.positionMs, archived = false))
        return MediaSession.MediaItemsWithStartPosition(listOf(episodeItem(ep, show)), 0, start)
    }

    private fun episodeItem(ep: EpisodeEntity, show: PodcastEntity?): MediaItem {
        val local = ep.localPath?.let { File(it) }?.takeIf { ep.isDownloaded && it.exists() && it.length() > 0 }
        val meta = MediaMetadata.Builder()
            .setTitle(ep.title)
            .setDisplayTitle(ep.title)
            .setArtist(show?.title)
            .setAlbumTitle(show?.title)
            .setSubtitle(show?.title)
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE)
        show?.artworkPath?.let { meta.setArtworkUri(Uri.fromFile(File(it))) }
        return MediaItem.Builder()
            .setMediaId(EPISODE_PREFIX + ep.id)
            .setUri(if (local != null) Uri.fromFile(local) else Uri.parse(ep.audioUrl))
            .setMediaMetadata(meta.build())
            .build()
    }

    private suspend fun resolveTrack(mediaId: String): MediaItem? {
        val parts = mediaId.split(":")
        if (parts.size != 3 || parts[0] != "track") return null
        val book = dao.getBook(parts[1]) ?: return null
        val track = dao.getTracks(book.id).firstOrNull { it.idx.toString() == parts[2] } ?: return null
        return trackItem(book, track)
    }

    private suspend fun trackItem(book: BookEntity, t: TrackEntity): MediaItem {
        val meta = MediaMetadata.Builder()
            .setTitle(book.displayTitle)
            .setDisplayTitle(book.displayTitle)
            .setArtist(book.author.ifBlank { null })
            .setAlbumTitle(book.title)
            .setSubtitle(t.title)
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK_CHAPTER)
        book.coverPath?.let { meta.setArtworkUri(Uri.fromFile(File(it))) }
        return MediaItem.Builder()
            .setMediaId("track:${book.id}:${t.idx}")
            .setUri(playableUri(t.uri))
            .setMediaMetadata(meta.build())
            .build()
    }

    /** Drive tracks play from the downloaded copy when there is one, otherwise stream. */
    private suspend fun playableUri(uri: String): Uri {
        if (!uri.startsWith(LibraryScanner.DRIVE_TRACK_PREFIX)) return Uri.parse(uri)
        val fileId = uri.removePrefix(LibraryScanner.DRIVE_TRACK_PREFIX)
        val local = container.downloads.driveLocal(fileId)
        return if (local != null) Uri.fromFile(local) else Uri.parse(DriveApi.mediaUrl(fileId))
    }

    private fun bookItem(b: BookEntity): MediaItem {
        val meta = MediaMetadata.Builder()
            .setTitle(b.displayTitle)
            .setArtist(b.author.ifBlank { null })
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK)
        b.coverPath?.let { meta.setArtworkUri(Uri.fromFile(File(it))) }
        return MediaItem.Builder().setMediaId("book:${b.id}").setMediaMetadata(meta.build()).build()
    }

    private fun folderItem(id: String, title: String): MediaItem =
        MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_AUDIO_BOOKS)
                    .build(),
            )
            .build()

    private inner class Callback : MediaLibrarySession.Callback {

        /** Ember's own controllers (the watch link) may also send its custom commands. */
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            val base = super.onConnect(session, controller)
            if (controller.packageName != packageName || !base.isAccepted) return base
            val cmds = base.availableSessionCommands.buildUpon()
                .add(androidx.media3.session.SessionCommand(WearLink.CMD_BOOKMARK, android.os.Bundle.EMPTY))
                .add(androidx.media3.session.SessionCommand(WearLink.CMD_RESUME, android.os.Bundle.EMPTY))
                .add(androidx.media3.session.SessionCommand(WearLink.CMD_SLEEP, android.os.Bundle.EMPTY))
                .add(androidx.media3.session.SessionCommand(WearLink.CMD_CHAPTER, android.os.Bundle.EMPTY))
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(cmds)
                .setAvailablePlayerCommands(base.availablePlayerCommands)
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: androidx.media3.session.SessionCommand,
            args: android.os.Bundle,
        ): ListenableFuture<androidx.media3.session.SessionResult> = scope.future {
            handleCustom(customCommand.customAction, args)
            androidx.media3.session.SessionResult(androidx.media3.session.SessionResult.RESULT_SUCCESS)
        }

        /** Buttons on headphones, earbuds and the car. Ember's own notification keeps the default handling. */
        override fun onMediaButtonEvent(
            session: MediaSession,
            controllerInfo: MediaSession.ControllerInfo,
            intent: Intent,
        ): Boolean {
            if (controllerInfo.packageName == packageName) return false
            val ev: android.view.KeyEvent = (
                if (android.os.Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, android.view.KeyEvent::class.java)
                } else {
                    @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
                }
                ) ?: return false
            return handleMediaKey(ev)
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(LibraryResult.ofItem(folderItem(ROOT, "Ember"), params))

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future {
            val books = dao.getBooks().filter { !it.isExternal }
            val items: List<MediaItem> = when (parentId) {
                ROOT -> listOf(
                    folderItem(CONTINUE, "Continue listening"),
                    folderItem(LIBRARY, "Books"),
                    folderItem(PODCASTS, "Podcasts"),
                )
                PODCASTS -> {
                    val shows = media.getPodcasts().associateBy { it.id }
                    val queue = media.getQueue()
                    val latest = media.getLatestUnplayed(40).filter { e -> queue.none { it.id == e.id } }
                    (queue + latest).map { episodeItem(it, shows[it.podcastId]) }
                }
                CONTINUE -> books.filter { it.status == BookEntity.STATUS_IN_PROGRESS }
                    .sortedByDescending { it.lastPlayedAt }
                    .map { bookItem(it) }
                LIBRARY -> books.sortedBy { it.title.lowercase() }.map { bookItem(it) }
                else -> emptyList()
            }
            LibraryResult.ofItemList(items, params)
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> = scope.future {
            if (mediaId.startsWith(EPISODE_PREFIX)) {
                val ep = media.getEpisode(mediaId.removePrefix(EPISODE_PREFIX))
                return@future if (ep != null) {
                    LibraryResult.ofItem(episodeItem(ep, media.getPodcast(ep.podcastId)), null)
                } else {
                    LibraryResult.ofError<MediaItem>(LibraryResult.RESULT_ERROR_BAD_VALUE)
                }
            }
            val b = dao.getBook(mediaId.removePrefix("book:"))
            if (b != null) {
                LibraryResult.ofItem(bookItem(b), null)
            } else {
                LibraryResult.ofError<MediaItem>(LibraryResult.RESULT_ERROR_BAD_VALUE)
            }
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> = scope.future {
            val out = mutableListOf<MediaItem>()
            for (item in mediaItems) {
                val id = item.mediaId
                when {
                    id.startsWith("book:") -> resolveBook(id.removePrefix("book:"))?.let { out.addAll(forActive(it.mediaItems)) }
                    id.startsWith(EPISODE_PREFIX) -> resolveEpisode(id.removePrefix(EPISODE_PREFIX))?.let { out.addAll(forActive(it.mediaItems)) }
                    item.localConfiguration != null -> out.add(item)
                    id.startsWith("track:") -> resolveTrack(id)?.let { out.add(it) }
                }
            }
            out
        }

        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = scope.future {
            val r = resolveForSet(mediaItems, startIndex, startPositionMs)
            if (casting) MediaSession.MediaItemsWithStartPosition(forActive(r.mediaItems), r.startIndex, r.startPositionMs) else r
        }

        private suspend fun resolveForSet(
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): MediaSession.MediaItemsWithStartPosition {
            val first = mediaItems.firstOrNull()
            return if (first != null && first.mediaId.startsWith(EPISODE_PREFIX)) {
                resolveEpisode(first.mediaId.removePrefix(EPISODE_PREFIX))
                    ?: MediaSession.MediaItemsWithStartPosition(emptyList(), C.INDEX_UNSET, C.TIME_UNSET)
            } else if (first != null && first.mediaId.startsWith("book:")) {
                resolveBook(first.mediaId.removePrefix("book:"))
                    ?: MediaSession.MediaItemsWithStartPosition(emptyList(), C.INDEX_UNSET, C.TIME_UNSET)
            } else {
                val resolved = mutableListOf<MediaItem>()
                for (item in mediaItems) {
                    if (item.localConfiguration != null) resolved.add(item)
                    else resolveTrack(item.mediaId)?.let { resolved.add(it) }
                }
                MediaSession.MediaItemsWithStartPosition(
                    resolved,
                    if (startIndex == C.INDEX_UNSET) 0 else startIndex,
                    if (startPositionMs == C.TIME_UNSET) 0 else startPositionMs,
                )
            }
        }

        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = scope.future {
            val id = container.settings.current().lastBookId
            val resumed = when {
                id == null -> null
                id.startsWith(EPISODE_PREFIX) -> resolveEpisode(id.removePrefix(EPISODE_PREFIX))
                else -> resolveBook(id)
            }
            resumed ?: throw UnsupportedOperationException("Nothing to resume")
        }
    }

    companion object {
        private const val ROOT = "root"
        private const val CONTINUE = "continue"
        private const val LIBRARY = "library"
        private const val PODCASTS = "podcasts"
        const val EPISODE_PREFIX = "episode:"
        private const val SHAKE_AFTER_MS = 5 * 60_000L

        /** True while the playback service is alive (the watch link checks it). */
        @Volatile
        var running = false
            private set
        private val wearPoke = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)

        /** The watch app opened: send it what's playing right away. */
        fun requestWearStatus() {
            wearPoke.tryEmit(Unit)
        }

        private val shakePoke = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        private val flipPoke = kotlinx.coroutines.flow.MutableSharedFlow<Boolean>(extraBufferCapacity = 2)
        private const val FLIP_RESUME_MS = 30 * 60_000L

        /** The emulator checks' stand-in for turning the phone face down (true) or back up (false). */
        fun testFlip(faceDown: Boolean) {
            flipPoke.tryEmit(faceDown)
        }

        /** The emulator checks' stand-in for shaking the phone. */
        fun testShake() {
            shakePoke.tryEmit(Unit)
        }

        /** How far smart rewind goes back after a pause of [gapMs], given your setting [baseMs]. */
        fun rewindFor(gapMs: Long, baseMs: Long): Long = when {
            baseMs <= 0 || gapMs < 10_000 -> 0L
            gapMs < 5 * 60_000L -> maxOf(1_000L, baseMs / 2)
            gapMs < 3_600_000L -> baseMs
            gapMs < 8 * 3_600_000L -> baseMs * 2
            else -> baseMs * 3
        }

        /**
         * The book to play after [book] in its series: the next unfinished one by series number
         * (or by title when books aren't numbered). Books in other apps are skipped.
         */
        fun nextInSeries(book: BookEntity, all: List<BookEntity>): BookEntity? {
            val series = book.series.trim()
            if (series.isEmpty()) return null
            val same = all.filter {
                it.id != book.id && !it.isExternal && it.series.trim().equals(series, ignoreCase = true) &&
                    it.status != BookEntity.STATUS_FINISHED
            }
            val idx = book.seriesIndex
            return if (idx != null) {
                same.filter { it.seriesIndex != null && it.seriesIndex > idx }.minByOrNull { it.seriesIndex!! }
            } else {
                same.sortedWith(compareBy<BookEntity> { it.seriesIndex ?: Float.MAX_VALUE }.thenBy { it.title.lowercase() }).firstOrNull()
            }
        }

        /** Book id for a book track, or "episode:<id>" for a podcast episode. */
        fun bookIdFromTrack(mediaId: String): String? {
            if (mediaId.startsWith(EPISODE_PREFIX)) return mediaId
            val parts = mediaId.split(":")
            return if (parts.size == 3 && parts[0] == "track") parts[1] else null
        }

        suspend fun refreshWidget(context: Context) {
            WidgetBus.poke()
            try {
                EmberWidget().updateAll(context)
            } catch (e: Exception) {
            }
        }
    }
}

/**
 * Notices the phone being put face down (screen to the floor, lying flat) and turned back up.
 * Face down counts after a second with the proximity sensor covered, or after a few seconds of
 * lying perfectly still when it isn't (the Fold open, where that sensor faces up). Holding the
 * phone above you in bed isn't still enough, and a pocket isn't flat.
 */
private class FlipDetector(context: Context, private val onFlip: (Boolean) -> Unit) : SensorEventListener {
    private val sensors = context.getSystemService(SensorManager::class.java)
    private var near = false
    private var downSince = 0L
    private var upSince = 0L
    private var isDown = false
    private var lastG = 0f
    private var shakeSum = 0f
    private var samples = 0

    fun start() {
        val sm = sensors ?: return
        val accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        sm.registerListener(this, accel, SensorManager.SENSOR_DELAY_NORMAL)
        sm.getDefaultSensor(Sensor.TYPE_PROXIMITY)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
    }

    fun stop() {
        sensors?.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_PROXIMITY) {
            near = event.values[0] < minOf(event.sensor.maximumRange, 3f)
            return
        }
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        val g = sqrt(x * x + y * y + z * z)
        val now = System.currentTimeMillis()
        val flatDown = z < -8.6f && kotlin.math.abs(x) < 2.2f && kotlin.math.abs(y) < 2.2f
        val faceUp = z > 6.5f
        if (flatDown) {
            if (downSince == 0L) {
                downSince = now
                shakeSum = 0f
                samples = 0
            } else {
                shakeSum += kotlin.math.abs(g - lastG)
                samples++
            }
            val still = samples >= 10 && shakeSum / samples < 0.12f
            val held = now - downSince
            if (!isDown && ((near && held >= 1_000) || (still && held >= 3_000))) {
                isDown = true
                onFlip(true)
            }
        } else {
            downSince = 0L
        }
        if (faceUp && isDown) {
            if (upSince == 0L) upSince = now
            if (now - upSince >= 400) {
                isDown = false
                upSince = 0L
                onFlip(false)
            }
        } else {
            upSince = 0L
        }
        lastG = g
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}

private class ShakeDetector(context: Context, private val onShake: () -> Unit) : SensorEventListener {
    private val sensors = context.getSystemService(SensorManager::class.java)
    private var last = 0L

    fun start() {
        val sm = sensors ?: return
        val accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        sm.registerListener(this, accel, SensorManager.SENSOR_DELAY_UI)
    }

    fun stop() {
        sensors?.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        val g = sqrt(x * x + y * y + z * z) / SensorManager.GRAVITY_EARTH
        val now = System.currentTimeMillis()
        if (g > 2.4f && now - last > 1_500) {
            last = now
            onShake()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
