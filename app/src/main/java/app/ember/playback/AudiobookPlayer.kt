package app.ember.playback

import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi

/**
 * Wraps ExoPlayer so "next" and "previous" (headphones, car, notification) skip
 * forward and back by the user's chosen seconds instead of jumping files, and so
 * skips cross file boundaries in multi-file books.
 */
@UnstableApi
class AudiobookPlayer(
    private val exo: Player,
    private val backMs: () -> Long,
    private val forwardMs: () -> Long,
    private val trackDuration: (Int) -> Long,
) : ForwardingPlayer(exo) {

    private val skipCommands = intArrayOf(
        Player.COMMAND_SEEK_TO_NEXT,
        Player.COMMAND_SEEK_TO_PREVIOUS,
        Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
        Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
        Player.COMMAND_SEEK_BACK,
        Player.COMMAND_SEEK_FORWARD,
    )

    override fun seekBack() = seekBy(-backMs())
    override fun seekForward() = seekBy(forwardMs())
    override fun getSeekBackIncrement(): Long = backMs()
    override fun getSeekForwardIncrement(): Long = forwardMs()
    override fun seekToNext() = seekBy(forwardMs())
    override fun seekToPrevious() = seekBy(-backMs())
    override fun seekToNextMediaItem() = seekBy(forwardMs())
    override fun seekToPreviousMediaItem() = seekBy(-backMs())

    override fun isCommandAvailable(command: Int): Boolean {
        if (command in skipCommands) return exo.mediaItemCount > 0
        return super.isCommandAvailable(command)
    }

    override fun getAvailableCommands(): Player.Commands {
        val base = super.getAvailableCommands()
        if (exo.mediaItemCount == 0) return base
        return base.buildUpon().addAll(*skipCommands).build()
    }

    private fun seekBy(delta: Long) {
        if (exo.mediaItemCount == 0) return
        val idx = exo.currentMediaItemIndex
        val dur = exo.duration.takeIf { it > 0 } ?: trackDuration(idx)
        val target = exo.currentPosition + delta
        when {
            target < 0 && idx > 0 -> {
                val prevDur = trackDuration(idx - 1)
                exo.seekTo(idx - 1, (prevDur + target).coerceAtLeast(0))
            }
            dur > 0 && target > dur && idx < exo.mediaItemCount - 1 ->
                exo.seekTo(idx + 1, (target - dur).coerceAtLeast(0))
            else -> {
                val max = if (dur > 0) (dur - 500).coerceAtLeast(0) else Long.MAX_VALUE
                exo.seekTo(target.coerceIn(0, max))
            }
        }
    }
}
