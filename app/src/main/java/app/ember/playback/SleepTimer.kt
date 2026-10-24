package app.ember.playback

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Shared sleep timer state. The playback service enforces it; the UI sets it. */
object SleepTimer {

    data class State(
        val endsAt: Long? = null,
        val durationMs: Long = 0,
        /** Pause when global book position reaches this point (end of chapter). */
        val stopAtPositionMs: Long? = null,
        /** Started by the bedtime setting rather than by hand. */
        val auto: Boolean = false,
    ) {
        val active: Boolean get() = endsAt != null || stopAtPositionMs != null
        fun remainingMs(now: Long = System.currentTimeMillis()): Long? = endsAt?.let { (it - now).coerceAtLeast(0) }
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    /** When you last turned the timer off by hand (bedtime then leaves you alone for the night). */
    @Volatile
    var userCancelledAt = 0L
        private set

    /** The timer that last ran out, and when: a shake soon after brings it back. */
    @Volatile
    var lastExpired: State? = null
        private set

    @Volatile
    var expiredAt = 0L
        private set

    fun start(minutes: Int, auto: Boolean = false) {
        val d = minutes * 60_000L
        _state.value = State(endsAt = System.currentTimeMillis() + d, durationMs = d, auto = auto)
    }

    /** Seconds rather than minutes: only for the emulator checks. */
    fun startSeconds(seconds: Int) {
        val d = seconds * 1000L
        _state.value = State(endsAt = System.currentTimeMillis() + d, durationMs = d)
    }

    fun endOfChapter(chapterEndMs: Long) {
        _state.value = State(stopAtPositionMs = chapterEndMs)
    }

    /** Restarts a timed sleep timer for its full length (used by shake to extend). */
    fun extend(): Boolean {
        val s = _state.value
        if (s.endsAt == null || s.durationMs <= 0) return false
        _state.value = s.copy(endsAt = System.currentTimeMillis() + s.durationMs)
        return true
    }

    /** Turned off by hand. */
    fun cancel() {
        if (_state.value.active) userCancelledAt = System.currentTimeMillis()
        _state.value = State()
    }

    /** Ran out (playback paused). */
    fun expire() {
        lastExpired = _state.value.takeIf { it.active }
        expiredAt = System.currentTimeMillis()
        _state.value = State()
    }

    /** Shaken after it ran out: don't offer it again. */
    fun forgetExpired() {
        lastExpired = null
        expiredAt = 0
    }
}

/**
 * Where you probably fell asleep: when the sleep timer pauses a book, the spot where you last
 * set or shook the timer (you were awake then). The player offers to go back there.
 */
object SleepMarks {
    data class Mark(val bookId: String, val awakeMs: Long, val stoppedMs: Long, val at: Long)

    private val _mark = MutableStateFlow<Mark?>(null)
    val mark: StateFlow<Mark?> = _mark
    private var loaded = false

    private fun prefs(context: Context) = context.getSharedPreferences("sleep_mark", Context.MODE_PRIVATE)

    fun load(context: Context) {
        if (loaded) return
        loaded = true
        val p = prefs(context)
        val id = p.getString("bookId", null) ?: return
        val m = Mark(id, p.getLong("awake", 0), p.getLong("stopped", 0), p.getLong("at", 0))
        // Only useful for a day or so.
        if (System.currentTimeMillis() - m.at < 36 * 3_600_000L) _mark.value = m
    }

    fun set(context: Context, m: Mark) {
        _mark.value = m
        loaded = true
        prefs(context).edit().putString("bookId", m.bookId).putLong("awake", m.awakeMs).putLong("stopped", m.stoppedMs).putLong("at", m.at).apply()
    }

    fun clear(context: Context) {
        _mark.value = null
        prefs(context).edit().clear().apply()
    }
}

/** Lightweight shared playback status for the widget. */
object PlaybackStatus {
    val isPlaying = MutableStateFlow(false)

    /** While previewing a clip: pause when the book reaches the end of this range. */
    val clipRange = MutableStateFlow<Pair<Long, Long>?>(null)

    /** A one-off message for the app to show (for example, the next book in a series starting). */
    val notice = MutableStateFlow<String?>(null)

    /** A big jump (the scrubber, a chapter): the app offers to go back to where you were. */
    data class Jump(val bookId: String, val fromMs: Long, val toMs: Long, val stamp: Long = System.nanoTime())

    val jumped = MutableStateFlow<Jump?>(null)

    /** A book played to its end (for the "rate it" prompt). */
    data class Finished(val bookId: String, val stamp: Long = System.nanoTime())

    val finished = MutableStateFlow<Finished?>(null)
}
