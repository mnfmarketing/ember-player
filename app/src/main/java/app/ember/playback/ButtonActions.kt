package app.ember.playback

/** What a headphone or car button gesture does (Settings → Headphone and car buttons). */
object ButtonActions {
    const val NOTHING = 0
    const val SKIP_BACK = 1
    const val SKIP_FORWARD = 2
    const val BOOKMARK = 3
    const val NEXT_CHAPTER = 4
    const val PREVIOUS_CHAPTER = 5

    /** In the order they're offered. */
    val choices: List<Pair<Int, String>> = listOf(
        SKIP_BACK to "Skip back",
        SKIP_FORWARD to "Skip forward",
        BOOKMARK to "Add a bookmark",
        PREVIOUS_CHAPTER to "Previous chapter",
        NEXT_CHAPTER to "Next chapter",
        NOTHING to "Nothing",
    )

    fun label(action: Int): String = choices.firstOrNull { it.first == action }?.second ?: "Nothing"
}
