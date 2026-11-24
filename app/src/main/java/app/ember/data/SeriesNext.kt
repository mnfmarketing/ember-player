package app.ember.data

/**
 * For each series you've finished a book in and aren't in the middle of, the next book
 * you haven't started (after the highest-numbered one you finished). Most recent finish first.
 */
fun nextInSeries(books: List<BookEntity>): List<BookEntity> =
    books.filter { it.series.isNotBlank() }
        .groupBy { it.series.trim().lowercase() }.values
        .mapNotNull { g ->
            if (g.any { it.status == BookEntity.STATUS_IN_PROGRESS }) return@mapNotNull null
            val done = g.filter { it.status == BookEntity.STATUS_FINISHED }
            if (done.isEmpty()) return@mapNotNull null
            val ordered = g.sortedWith(compareBy<BookEntity> { it.seriesIndex ?: Float.MAX_VALUE }.thenBy { it.title.lowercase() })
            val lastDone = ordered.indexOfLast { it.status == BookEntity.STATUS_FINISHED }
            val next = ordered.drop(lastDone + 1).firstOrNull { it.status == BookEntity.STATUS_NEW }
                ?: ordered.firstOrNull { it.status == BookEntity.STATUS_NEW }
            next?.let { it to done.maxOf { d -> d.finishedAt } }
        }
        .sortedByDescending { it.second }
        .map { it.first }

