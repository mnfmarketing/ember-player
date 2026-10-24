package app.ember.playback

import androidx.media3.common.PlaybackException
import androidx.media3.datasource.HttpDataSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** A playback problem explained in plain words, with the one thing most likely to fix it. */
data class PlaybackIssue(
    val title: String,
    val detail: String,
    val action: Action,
    /** Book id, or "episode:<id>". */
    val itemId: String?,
    val at: Long = System.currentTimeMillis(),
) {
    enum class Action(val label: String?) {
        RETRY("Try again"),
        RESCAN("Rescan library"),
        RECONNECT_DRIVE("Reconnect Drive"),
        STREAM("Stream instead"),
        NONE(null),
    }
}

object PlaybackIssues {

    private val _current = MutableStateFlow<PlaybackIssue?>(null)
    val current: StateFlow<PlaybackIssue?> = _current

    fun report(issue: PlaybackIssue) {
        _current.value = issue
    }

    fun clear() {
        _current.value = null
    }

    /**
     * Turns ExoPlayer's error into something a person can act on.
     * [source] says where the audio was coming from.
     */
    fun describe(error: PlaybackException, itemId: String?, source: Source): PlaybackIssue {
        val code = error.errorCode
        val http = generateSequence(error.cause) { it.cause }
            .filterIsInstance<HttpDataSource.InvalidResponseCodeException>()
            .firstOrNull()?.responseCode
        val episode = source == Source.EPISODE_STREAM || source == Source.EPISODE_DOWNLOAD
        return when {
            code == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS && source == Source.DRIVE && (http == 401 || http == 403) ->
                PlaybackIssue(
                    "Google Drive needs you to sign in again",
                    "Ember's access to your Drive has expired. Reconnect in Settings and the book will play again.",
                    PlaybackIssue.Action.RECONNECT_DRIVE,
                    itemId,
                )
            code == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS && (http == 404 || http == 410) ->
                if (source == Source.DRIVE) {
                    PlaybackIssue(
                        "This file isn't in your Google Drive anymore",
                        "It may have been moved or deleted. Rescan to update your library.",
                        PlaybackIssue.Action.RESCAN,
                        itemId,
                    )
                } else {
                    PlaybackIssue(
                        "This episode is no longer available",
                        "The podcast has removed or moved its audio. Refreshing the show may pick up a new link.",
                        PlaybackIssue.Action.RETRY,
                        itemId,
                    )
                }
            code == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
                PlaybackIssue(
                    "The server didn't send the audio",
                    "It answered with error ${http ?: "?"}. This is usually temporary.",
                    PlaybackIssue.Action.RETRY,
                    itemId,
                )
            code == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                code == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
                PlaybackIssue(
                    "No connection",
                    if (episode) {
                        "This episode streams from the internet. Downloaded episodes play offline."
                    } else {
                        "This book streams from Google Drive. Download it from its page to listen offline."
                    },
                    PlaybackIssue.Action.RETRY,
                    itemId,
                )
            code == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ||
                code == PlaybackException.ERROR_CODE_IO_NO_PERMISSION ||
                code == PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE ->
                when (source) {
                    Source.EPISODE_DOWNLOAD -> PlaybackIssue(
                        "The downloaded episode is missing",
                        "Ember can stream it instead.",
                        PlaybackIssue.Action.STREAM,
                        itemId,
                    )
                    else -> PlaybackIssue(
                        "Ember can't open this book's file",
                        "It may have been moved, renamed or deleted, or Android removed Ember's access to its folder. " +
                            "Rescan, or check the folder in Settings.",
                        PlaybackIssue.Action.RESCAN,
                        itemId,
                    )
                }
            code in PARSING_AND_DECODING ->
                PlaybackIssue(
                    "Ember can't play this file",
                    "It may be damaged, not fully copied yet, or a protected format such as Audible's AAX. " +
                        "Try opening it in another player to check.",
                    PlaybackIssue.Action.NONE,
                    itemId,
                )
            code == PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED ||
                code == PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED ->
                PlaybackIssue(
                    "The speaker or headphones didn't respond",
                    "Another app may be holding the audio. Try again.",
                    PlaybackIssue.Action.RETRY,
                    itemId,
                )
            else ->
                PlaybackIssue(
                    "Playback stopped unexpectedly",
                    "Something went wrong (${error.errorCodeName}). Trying again usually works.",
                    PlaybackIssue.Action.RETRY,
                    itemId,
                )
        }
    }

    enum class Source { LOCAL, DRIVE, EPISODE_STREAM, EPISODE_DOWNLOAD }

    private val PARSING_AND_DECODING = setOf(
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
    )
}
