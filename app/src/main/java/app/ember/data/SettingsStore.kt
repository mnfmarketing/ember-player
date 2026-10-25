package app.ember.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

data class Settings(
    val skipBackSec: Int = 15,
    val skipForwardSec: Int = 30,
    val skipSilence: Boolean = false,
    val autoRewindSec: Int = 5,
    val dailyGoalMin: Int = 30,
    val yearlyGoalBooks: Int = 24,
    val lastBookId: String? = null,
    val podcastListView: Boolean = false,
    /** The player's time-left label counts down the whole book instead of the chapter. */
    val bookTimeLeft: Boolean = false,
    /** Share of the unfolded screen the player column takes. */
    val playerPaneFraction: Float = 0.33f,
    val eqEnabled: Boolean = false,
    val eqPreset: String = "spoken",
    val eqCustom: List<Int> = listOf(0, 0, 0, 0, 0),
    val narrationBoost: Boolean = false,
    /** Start playing again when headphones or a car connect. */
    val resumeOnConnect: Boolean = false,
    /** When a book in a series ends, start the next one. */
    val seriesAutoPlay: Boolean = true,
    /** How much audio a clip captures, counting back from when you tap Clip. */
    val clipLengthSec: Int = 30,
    /** Most unplayed episodes kept downloaded per show by auto-download. */
    val autoDownloadCount: Int = 2,
    /** Torrent downloads only run on Wi-Fi (or other unmetered networks). */
    val torrentWifiOnly: Boolean = true,
    /** Where finished torrent downloads are moved (a folder you picked). */
    val torrentFolderUri: String = "",
    val torrentFolderName: String = "",
    /** A folder you picked for nightly backup copies (empty = Ember's own storage only). */
    val backupFolderUri: String = "",
    val backupFolderName: String = "",
    val lastBackupAt: Long = 0,
    /** Open car mode when the car's Bluetooth connects. */
    val carModeAuto: Boolean = true,
    /** Bluetooth addresses you've marked as your car (besides devices that say they're car audio). */
    val carDevices: String = "",
    /** When a book finishes, ask for a rating and offer StoryGraph / Goodreads. */
    val rateOnFinish: Boolean = true,
    /** Headphone and car buttons: what each gesture does (see ButtonActions). */
    val btnDouble: Int = 1,
    val btnTriple: Int = 2,
    val btnLong: Int = 3,
    val btnNext: Int = 2,
    val btnPrevious: Int = 1,
    /** The sleep timer lowers the volume over its last seconds (off: it just pauses). */
    val sleepFade: Boolean = false,
    /** Pause when the phone is put face down; resume when it's turned back up. */
    val flipToPause: Boolean = false,
    /** Bedtime: playing between these times starts the sleep timer by itself. */
    val bedtimeOn: Boolean = false,
    /** Minutes after midnight. */
    val bedtimeFromMin: Int = 22 * 60,
    val bedtimeToMin: Int = 6 * 60,
    val bedtimeTimerMin: Int = 30,
    /** Books to play next, in order (book ids). */
    val upNext: List<String> = emptyList(),
    /** Appearance: "system" follows the phone's dark mode (and its night schedule), or "light" / "dark". */
    val appearance: String = "system",
    /** One of the themes in Theme.kt (each has a light and a dark version). */
    val themeId: String = "ember",
    /** Dark mode on pure black. */
    val trueBlack: Boolean = false,
    /** "book" (the playing book's cover color), "theme" (the theme's own) or "fixed" ([accentColor]). */
    val accentMode: String = "book",
    val accentColor: Int = 0xFFF2A65A.toInt(),
    /** Your name, for the profile circle ("" until you set it). */
    val profileName: String = "",
    /** When your profile photo was last changed (0 = no photo; the first letter of your name shows instead). */
    val profilePhotoAt: Long = 0,
)

class SettingsStore(private val context: Context) {

    private object Keys {
        val skipBack = intPreferencesKey("skip_back")
        val skipForward = intPreferencesKey("skip_forward")
        val skipSilence = booleanPreferencesKey("skip_silence")
        val autoRewind = intPreferencesKey("auto_rewind")
        val dailyGoal = intPreferencesKey("daily_goal")
        val yearlyGoal = intPreferencesKey("yearly_goal")
        val lastBook = stringPreferencesKey("last_book")
        val podcastList = booleanPreferencesKey("podcast_list_view")
        val bookTimeLeft = booleanPreferencesKey("book_time_left")
        val paneFraction = floatPreferencesKey("player_pane_fraction")
        val eqEnabled = booleanPreferencesKey("eq_enabled")
        val eqPreset = stringPreferencesKey("eq_preset")
        val eqCustom = stringPreferencesKey("eq_custom")
        val boost = booleanPreferencesKey("narration_boost")
        val resumeOnConnect = booleanPreferencesKey("resume_on_connect")
        val seriesAutoPlay = booleanPreferencesKey("series_auto_play")
        val clipLength = intPreferencesKey("clip_length")
        val autoDownloadCount = intPreferencesKey("auto_download_count")
        val torrentWifiOnly = booleanPreferencesKey("torrent_wifi_only")
        val torrentFolder = stringPreferencesKey("torrent_folder")
        val torrentFolderName = stringPreferencesKey("torrent_folder_name")
        val backupFolder = stringPreferencesKey("backup_folder")
        val backupFolderName = stringPreferencesKey("backup_folder_name")
        val lastBackup = androidx.datastore.preferences.core.longPreferencesKey("last_backup")
        val carModeAuto = booleanPreferencesKey("car_mode_auto")
        val carDevices = stringPreferencesKey("car_devices")
        val rateOnFinish = booleanPreferencesKey("rate_on_finish")
        val btnDouble = intPreferencesKey("btn_double")
        val btnTriple = intPreferencesKey("btn_triple")
        val btnLong = intPreferencesKey("btn_long")
        val btnNext = intPreferencesKey("btn_next")
        val btnPrevious = intPreferencesKey("btn_previous")
        val sleepFade = booleanPreferencesKey("sleep_fade")
        val flipToPause = booleanPreferencesKey("flip_to_pause")
        val profileName = stringPreferencesKey("profile_name")
        val appearance = stringPreferencesKey("appearance")
        val themeId = stringPreferencesKey("theme_id")
        val trueBlack = booleanPreferencesKey("true_black")
        val accentMode = stringPreferencesKey("accent_mode")
        val accentColor = intPreferencesKey("accent_color")
        val profilePhotoAt = androidx.datastore.preferences.core.longPreferencesKey("profile_photo_at")
        val bedtimeOn = booleanPreferencesKey("bedtime_on")
        val bedtimeFrom = intPreferencesKey("bedtime_from")
        val bedtimeTo = intPreferencesKey("bedtime_to")
        val bedtimeTimer = intPreferencesKey("bedtime_timer")
        val upNext = stringPreferencesKey("up_next")
    }

    val flow: Flow<Settings> = context.dataStore.data.map { p -> p.toSettings() }

    suspend fun current(): Settings = flow.first()

    private fun Preferences.toSettings() = Settings(
        skipBackSec = this[Keys.skipBack] ?: 15,
        skipForwardSec = this[Keys.skipForward] ?: 30,
        skipSilence = this[Keys.skipSilence] ?: false,
        autoRewindSec = this[Keys.autoRewind] ?: 5,
        dailyGoalMin = this[Keys.dailyGoal] ?: 30,
        yearlyGoalBooks = this[Keys.yearlyGoal] ?: 24,
        lastBookId = this[Keys.lastBook],
        podcastListView = this[Keys.podcastList] ?: false,
        bookTimeLeft = this[Keys.bookTimeLeft] ?: false,
        playerPaneFraction = this[Keys.paneFraction] ?: 0.33f,
        eqEnabled = this[Keys.eqEnabled] ?: false,
        eqPreset = this[Keys.eqPreset] ?: "spoken",
        eqCustom = this[Keys.eqCustom]?.split(",")?.mapNotNull { it.trim().toIntOrNull() }?.takeIf { it.size == 5 } ?: listOf(0, 0, 0, 0, 0),
        narrationBoost = this[Keys.boost] ?: false,
        resumeOnConnect = this[Keys.resumeOnConnect] ?: false,
        seriesAutoPlay = this[Keys.seriesAutoPlay] ?: true,
        clipLengthSec = this[Keys.clipLength] ?: 30,
        autoDownloadCount = this[Keys.autoDownloadCount] ?: 2,
        torrentWifiOnly = this[Keys.torrentWifiOnly] ?: true,
        torrentFolderUri = this[Keys.torrentFolder] ?: "",
        torrentFolderName = this[Keys.torrentFolderName] ?: "",
        backupFolderUri = this[Keys.backupFolder] ?: "",
        backupFolderName = this[Keys.backupFolderName] ?: "",
        lastBackupAt = this[Keys.lastBackup] ?: 0L,
        carModeAuto = this[Keys.carModeAuto] ?: true,
        carDevices = this[Keys.carDevices] ?: "",
        rateOnFinish = this[Keys.rateOnFinish] ?: true,
        btnDouble = this[Keys.btnDouble] ?: 1,
        btnTriple = this[Keys.btnTriple] ?: 2,
        btnLong = this[Keys.btnLong] ?: 3,
        btnNext = this[Keys.btnNext] ?: 2,
        btnPrevious = this[Keys.btnPrevious] ?: 1,
        sleepFade = this[Keys.sleepFade] ?: false,
        flipToPause = this[Keys.flipToPause] ?: false,
        profileName = this[Keys.profileName] ?: "",
        appearance = this[Keys.appearance] ?: "system",
        themeId = this[Keys.themeId] ?: "ember",
        trueBlack = this[Keys.trueBlack] ?: false,
        accentMode = this[Keys.accentMode] ?: "book",
        accentColor = this[Keys.accentColor] ?: 0xFFF2A65A.toInt(),
        profilePhotoAt = this[Keys.profilePhotoAt] ?: 0L,
        bedtimeOn = this[Keys.bedtimeOn] ?: false,
        bedtimeFromMin = this[Keys.bedtimeFrom] ?: (22 * 60),
        bedtimeToMin = this[Keys.bedtimeTo] ?: (6 * 60),
        bedtimeTimerMin = this[Keys.bedtimeTimer] ?: 30,
        upNext = this[Keys.upNext]?.split(',')?.filter { it.isNotBlank() } ?: emptyList(),
    )

    suspend fun setSkipBack(sec: Int) = context.dataStore.edit { it[Keys.skipBack] = sec }
    suspend fun setSkipForward(sec: Int) = context.dataStore.edit { it[Keys.skipForward] = sec }
    suspend fun setSkipSilence(on: Boolean) = context.dataStore.edit { it[Keys.skipSilence] = on }
    suspend fun setAutoRewind(sec: Int) = context.dataStore.edit { it[Keys.autoRewind] = sec }
    suspend fun setDailyGoal(min: Int) = context.dataStore.edit { it[Keys.dailyGoal] = min }
    suspend fun setYearlyGoal(books: Int) = context.dataStore.edit { it[Keys.yearlyGoal] = books }
    suspend fun setEqEnabled(on: Boolean) = context.dataStore.edit { it[Keys.eqEnabled] = on }
    suspend fun setEqPreset(preset: String) = context.dataStore.edit { it[Keys.eqPreset] = preset }
    suspend fun setEqCustom(gains: List<Int>) = context.dataStore.edit {
        it[Keys.eqCustom] = gains.joinToString(",")
        it[Keys.eqPreset] = "custom"
    }
    suspend fun setNarrationBoost(on: Boolean) = context.dataStore.edit { it[Keys.boost] = on }

    suspend fun setPlayerPaneFraction(f: Float) = context.dataStore.edit { it[Keys.paneFraction] = f }
    suspend fun setPodcastListView(on: Boolean) = context.dataStore.edit { it[Keys.podcastList] = on }
    suspend fun setBookTimeLeft(on: Boolean) = context.dataStore.edit { it[Keys.bookTimeLeft] = on }

    suspend fun setResumeOnConnect(on: Boolean) = context.dataStore.edit { it[Keys.resumeOnConnect] = on }
    suspend fun setSeriesAutoPlay(on: Boolean) = context.dataStore.edit { it[Keys.seriesAutoPlay] = on }
    suspend fun setClipLength(sec: Int) = context.dataStore.edit { it[Keys.clipLength] = sec }
    suspend fun setAutoDownloadCount(n: Int) = context.dataStore.edit { it[Keys.autoDownloadCount] = n }
    suspend fun setTorrentWifiOnly(on: Boolean) = context.dataStore.edit { it[Keys.torrentWifiOnly] = on }
    suspend fun setTorrentFolder(uri: String, name: String) = context.dataStore.edit {
        it[Keys.torrentFolder] = uri
        it[Keys.torrentFolderName] = name
    }
    suspend fun setBackupFolder(uri: String, name: String) = context.dataStore.edit {
        it[Keys.backupFolder] = uri
        it[Keys.backupFolderName] = name
    }
    suspend fun setLastBackup(at: Long) = context.dataStore.edit { it[Keys.lastBackup] = at }
    suspend fun setCarModeAuto(on: Boolean) = context.dataStore.edit { it[Keys.carModeAuto] = on }
    suspend fun setCarDevices(addresses: Set<String>) = context.dataStore.edit { it[Keys.carDevices] = addresses.joinToString(",") }
    suspend fun setRateOnFinish(on: Boolean) = context.dataStore.edit { it[Keys.rateOnFinish] = on }
    suspend fun setSleepFade(on: Boolean) = context.dataStore.edit { it[Keys.sleepFade] = on }
    suspend fun setFlipToPause(on: Boolean) = context.dataStore.edit { it[Keys.flipToPause] = on }
    suspend fun setAppearance(mode: String) = context.dataStore.edit { it[Keys.appearance] = mode }
    suspend fun setTheme(id: String) = context.dataStore.edit { it[Keys.themeId] = id }
    suspend fun setTrueBlack(on: Boolean) = context.dataStore.edit { it[Keys.trueBlack] = on }
    suspend fun setAccent(mode: String, color: Int? = null) = context.dataStore.edit {
        it[Keys.accentMode] = mode
        if (color != null) it[Keys.accentColor] = color
    }
    suspend fun setProfileName(name: String) = context.dataStore.edit { it[Keys.profileName] = name.trim() }
    suspend fun setProfilePhotoAt(at: Long) = context.dataStore.edit { it[Keys.profilePhotoAt] = at }
    suspend fun setBedtime(on: Boolean, fromMin: Int, toMin: Int, timerMin: Int) = context.dataStore.edit {
        it[Keys.bedtimeOn] = on
        it[Keys.bedtimeFrom] = fromMin
        it[Keys.bedtimeTo] = toMin
        it[Keys.bedtimeTimer] = timerMin
    }

    /** Changes Up Next in one step (so two quick changes can't undo each other). */
    suspend fun editUpNext(change: (List<String>) -> List<String>) = context.dataStore.edit {
        val cur = it[Keys.upNext]?.split(',')?.filter { id -> id.isNotBlank() } ?: emptyList()
        it[Keys.upNext] = change(cur).distinct().joinToString(",")
    }

    suspend fun setButton(which: String, action: Int) = context.dataStore.edit {
        when (which) {
            "double" -> it[Keys.btnDouble] = action
            "triple" -> it[Keys.btnTriple] = action
            "long" -> it[Keys.btnLong] = action
            "next" -> it[Keys.btnNext] = action
            "previous" -> it[Keys.btnPrevious] = action
        }
    }

    /** Applies settings from a backup (everything that's about how you like to listen). */
    suspend fun restore(s: Settings) = context.dataStore.edit {
        it[Keys.skipBack] = s.skipBackSec
        it[Keys.skipForward] = s.skipForwardSec
        it[Keys.skipSilence] = s.skipSilence
        it[Keys.autoRewind] = s.autoRewindSec
        it[Keys.dailyGoal] = s.dailyGoalMin
        it[Keys.yearlyGoal] = s.yearlyGoalBooks
        it[Keys.podcastList] = s.podcastListView
        it[Keys.bookTimeLeft] = s.bookTimeLeft
        it[Keys.paneFraction] = s.playerPaneFraction
        it[Keys.eqEnabled] = s.eqEnabled
        it[Keys.eqPreset] = s.eqPreset
        it[Keys.eqCustom] = s.eqCustom.joinToString(",")
        it[Keys.boost] = s.narrationBoost
        it[Keys.resumeOnConnect] = s.resumeOnConnect
        it[Keys.seriesAutoPlay] = s.seriesAutoPlay
        it[Keys.clipLength] = s.clipLengthSec
        it[Keys.autoDownloadCount] = s.autoDownloadCount
        it[Keys.torrentWifiOnly] = s.torrentWifiOnly
        it[Keys.carModeAuto] = s.carModeAuto
        it[Keys.rateOnFinish] = s.rateOnFinish
        it[Keys.btnDouble] = s.btnDouble
        it[Keys.btnTriple] = s.btnTriple
        it[Keys.btnLong] = s.btnLong
        it[Keys.btnNext] = s.btnNext
        it[Keys.btnPrevious] = s.btnPrevious
        it[Keys.sleepFade] = s.sleepFade
        it[Keys.flipToPause] = s.flipToPause
        it[Keys.appearance] = s.appearance
        it[Keys.themeId] = s.themeId
        it[Keys.trueBlack] = s.trueBlack
        it[Keys.accentMode] = s.accentMode
        it[Keys.accentColor] = s.accentColor
        it[Keys.bedtimeOn] = s.bedtimeOn
        it[Keys.bedtimeFrom] = s.bedtimeFromMin
        it[Keys.bedtimeTo] = s.bedtimeToMin
        it[Keys.bedtimeTimer] = s.bedtimeTimerMin
        it[Keys.upNext] = s.upNext.joinToString(",")
    }

    suspend fun setLastBook(id: String?) = context.dataStore.edit {
        if (id == null) it.remove(Keys.lastBook) else it[Keys.lastBook] = id
    }
}
