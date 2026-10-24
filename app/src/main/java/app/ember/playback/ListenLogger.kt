package app.ember.playback

import app.ember.data.LibraryDao
import app.ember.data.ListenEventEntity
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.abs

/**
 * Writes the listening log: every play, pause, skip and jump, with where you were.
 * A burst of skips (tap, tap, tap) is kept as one entry that remembers where it started.
 */
class ListenLogger(private val dao: LibraryDao) {

    private val mutex = Mutex()
    private var writes = 0

    suspend fun play(bookId: String, positionMs: Long, speed: Float) = mutex.withLock {
        val last = dao.lastListenEvent(bookId)
        // Buffering hiccups can flip play on twice; one entry is enough.
        if (last != null && last.kind == ListenEventEntity.PLAY && System.currentTimeMillis() - last.at < 3_000) return@withLock
        insert(ListenEventEntity(bookId = bookId, at = System.currentTimeMillis(), kind = ListenEventEntity.PLAY, positionMs = positionMs, speed = speed))
    }

    /** A pause. For the sleep timer, [awakeMs] is where you were when you last set or shook it. */
    suspend fun pause(bookId: String, positionMs: Long, speed: Float, reason: Int, awakeMs: Long = -1L) = mutex.withLock {
        val last = dao.lastListenEvent(bookId)
        if (last != null && last.kind == ListenEventEntity.PAUSE && abs(last.positionMs - positionMs) < 2_000 && System.currentTimeMillis() - last.at < 3_000) return@withLock
        insert(ListenEventEntity(bookId = bookId, at = System.currentTimeMillis(), kind = ListenEventEntity.PAUSE, positionMs = positionMs, toMs = awakeMs, speed = speed, reason = reason))
    }

    /**
     * A skip or jump from [fromMs] to [toMs]. Returns the logged entry (merged with the one
     * before it when you skip several times in a row), or null when the move was too small to log.
     */
    suspend fun moved(bookId: String, fromMs: Long, toMs: Long, speed: Float, skipBackMs: Long, skipForwardMs: Long): ListenEventEntity? = mutex.withLock {
        val delta = toMs - fromMs
        if (abs(delta) < 1_500) return@withLock null
        val isSkip = abs(delta - skipForwardMs) < 1_500 || abs(delta + skipBackMs) < 1_500
        val kind = if (isSkip) ListenEventEntity.SKIP else ListenEventEntity.JUMP
        val now = System.currentTimeMillis()
        val last = dao.lastListenEvent(bookId)
        // Merge with the move just before when it's a quick follow-on in the same direction
        // (playback keeps running between taps, so allow for the time that passed).
        val lastDelta = if (last != null) last.toMs - last.positionMs else 0L
        val elapsed = if (last != null) ((now - last.at) * speed).toLong() else 0L
        if (last != null &&
            (last.kind == ListenEventEntity.SKIP || last.kind == ListenEventEntity.JUMP) &&
            now - last.at < 8_000 &&
            fromMs - last.toMs in -2_000..(elapsed + 2_000) &&
            (lastDelta > 0) == (delta > 0)
        ) {
            val merged = last.copy(
                at = now,
                toMs = toMs,
                kind = if (last.kind == ListenEventEntity.SKIP && isSkip) ListenEventEntity.SKIP else ListenEventEntity.JUMP,
            )
            dao.updateListenEvent(merged)
            return@withLock merged
        }
        val e = ListenEventEntity(bookId = bookId, at = now, kind = kind, positionMs = fromMs, toMs = toMs, speed = speed)
        e.copy(id = insert(e))
    }

    private suspend fun insert(e: ListenEventEntity): Long {
        val id = dao.insertListenEvent(e)
        if (++writes % 25 == 0) dao.trimListenLog(e.bookId)
        return id
    }
}
