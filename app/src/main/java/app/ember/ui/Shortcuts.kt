package app.ember.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import app.ember.R
import app.ember.container
import app.ember.data.CoverStore
import app.ember.playback.PlaybackService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Long-press Ember's icon on the home screen: Resume (the last book, with its cover),
 * Sleep timer and Search. Resume follows whatever was played last.
 */
object Shortcuts {

    const val ACTION = "app.ember.SHORTCUT"
    const val EXTRA = "ember_shortcut"

    fun watch(context: Context) {
        val c = context.container
        c.appScope.launch {
            c.settings.flow.map { it.lastBookId }.distinctUntilChanged().collect { id ->
                runCatching { update(context.applicationContext, id) }
            }
        }
    }

    private fun intent(context: Context, what: String) =
        Intent(context, MainActivity::class.java)
            .setAction(ACTION)
            .putExtra(EXTRA, what)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    private suspend fun update(context: Context, lastId: String?) {
        val (title, coverPath) = withContext(Dispatchers.IO) { describe(context, lastId) }
        val list = mutableListOf<ShortcutInfoCompat>()
        if (title != null) {
            val cover: Bitmap? = coverPath?.let { withContext(Dispatchers.IO) { CoverStore.decodeFile(it, 192) } }
            list += ShortcutInfoCompat.Builder(context, "resume")
                .setShortLabel("Resume")
                .setLongLabel("Resume ${title.take(20)}")
                .setIcon(cover?.let { IconCompat.createWithBitmap(squared(it)) } ?: IconCompat.createWithResource(context, R.drawable.ic_shortcut_play))
                .setIntent(intent(context, "resume"))
                .setRank(0)
                .build()
        }
        list += ShortcutInfoCompat.Builder(context, "car")
            .setShortLabel("Car mode")
            .setIcon(IconCompat.createWithResource(context, R.drawable.ic_shortcut_car))
            .setIntent(intent(context, "car"))
            .setRank(1)
            .build()
        list += ShortcutInfoCompat.Builder(context, "sleep")
            .setShortLabel("Sleep timer")
            .setIcon(IconCompat.createWithResource(context, R.drawable.ic_shortcut_sleep))
            .setIntent(intent(context, "sleep"))
            .setRank(2)
            .build()
        list += ShortcutInfoCompat.Builder(context, "search")
            .setShortLabel("Search")
            .setLongLabel("Search Ember")
            .setIcon(IconCompat.createWithResource(context, R.drawable.ic_shortcut_search))
            .setIntent(intent(context, "search"))
            .setRank(3)
            .build()
        ShortcutManagerCompat.setDynamicShortcuts(context, list)
    }

    private suspend fun describe(context: Context, id: String?): Pair<String?, String?> {
        if (id == null) return null to null
        val c = context.container
        if (id.startsWith(PlaybackService.EPISODE_PREFIX)) {
            val ep = c.media.dao().getEpisode(id.removePrefix(PlaybackService.EPISODE_PREFIX)) ?: return null to null
            return ep.title to c.media.dao().getPodcast(ep.podcastId)?.artworkPath
        }
        val b = c.db.dao().getBook(id) ?: return null to null
        return b.title to b.coverPath
    }

    private fun squared(src: Bitmap): Bitmap {
        val side = minOf(src.width, src.height)
        return Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side)
    }
}
