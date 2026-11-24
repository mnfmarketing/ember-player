package app.ember.data

/**
 * Time you've actually spent listening to a book (real time, whatever the speed), from its
 * listening log: each Play up to the Pause that follows it, per device. A play with no pause
 * after it (Ember was closed) is left out, except the one playing right now.
 */
object ListenTime {
    private const val MAX_SESSION_MS = 12 * 60 * 60 * 1000L

    fun total(log: List<ListenEventEntity>, now: Long = System.currentTimeMillis(), playingHere: Boolean = false): Long {
        var sum = 0L
        for ((device, events) in log.groupBy { it.device }) {
            var start = -1L
            for (e in events.sortedWith(compareBy<ListenEventEntity> { it.at }.thenBy { it.id })) {
                when (e.kind) {
                    ListenEventEntity.PLAY -> start = e.at
                    ListenEventEntity.PAUSE -> {
                        if (start >= 0) sum += (e.at - start).takeIf { it in 1..MAX_SESSION_MS } ?: 0
                        start = -1
                    }
                }
            }
            if (start >= 0 && device.isEmpty() && playingHere) sum += (now - start).takeIf { it in 1..MAX_SESSION_MS } ?: 0
        }
        return sum
    }
}
