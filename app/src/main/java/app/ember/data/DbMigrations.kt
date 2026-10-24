package app.ember.data

import android.content.Context
import android.os.Build
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.io.File

/**
 * Every change to the shape of Ember's databases, one numbered step at a time.
 *
 * Rules for future changes:
 *  - Never edit a step that has shipped. Add a new one (2 to 3, and so on).
 *  - Each step's SQL is checked in CI against the schema Room generates
 *    (see DbMigrationTest), using the exported files in app/schemas.
 */
object DbMigrations {

    /** Library database (ember.db), version 1 to 2: adds clips. */
    val EMBER_1_2 = listOf(
        "CREATE TABLE IF NOT EXISTS `clips` (" +
            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`mediaId` TEXT NOT NULL, " +
            "`title` TEXT NOT NULL, " +
            "`subtitle` TEXT NOT NULL, " +
            "`startMs` INTEGER NOT NULL, " +
            "`endMs` INTEGER NOT NULL, " +
            "`note` TEXT NOT NULL, " +
            "`chapterTitle` TEXT NOT NULL, " +
            "`coverPath` TEXT, " +
            "`createdAt` INTEGER NOT NULL)",
    )

    /** Library database, version 2 to 3: the listening log, and a book's companion PDF. */
    val EMBER_2_3 = listOf(
        "CREATE TABLE IF NOT EXISTS `listen_log` (" +
            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`bookId` TEXT NOT NULL, " +
            "`at` INTEGER NOT NULL, " +
            "`kind` INTEGER NOT NULL, " +
            "`positionMs` INTEGER NOT NULL, " +
            "`toMs` INTEGER NOT NULL, " +
            "`speed` REAL NOT NULL, " +
            "`reason` INTEGER NOT NULL)",
        "CREATE INDEX IF NOT EXISTS `index_listen_log_bookId` ON `listen_log` (`bookId`)",
        "ALTER TABLE `books` ADD COLUMN `pdfUri` TEXT",
    )

    /** Library database, version 3 to 4: a book's own trim silence and voice boost. */
    val EMBER_3_4 = listOf(
        "ALTER TABLE `books` ADD COLUMN `customEffects` INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE `books` ADD COLUMN `trimSilence` INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE `books` ADD COLUMN `voiceBoost` INTEGER NOT NULL DEFAULT 0",
    )

    /** Podcast database (media.db), version 1 to 2: adds podcast chapters. */
    val MEDIA_1_2 = listOf(
        "ALTER TABLE `episodes` ADD COLUMN `chaptersUrl` TEXT NOT NULL DEFAULT ''",
        "ALTER TABLE `episodes` ADD COLUMN `chaptersState` INTEGER NOT NULL DEFAULT 0",
        "CREATE TABLE IF NOT EXISTS `episode_chapters` (" +
            "`episodeId` TEXT NOT NULL, " +
            "`idx` INTEGER NOT NULL, " +
            "`title` TEXT NOT NULL, " +
            "`startMs` INTEGER NOT NULL, " +
            "`imageUrl` TEXT, " +
            "`url` TEXT, " +
            "PRIMARY KEY(`episodeId`, `idx`))",
    )

    /**
     * Podcast database, version 2 to 3: per-show settings, and episodes can be archived (hidden). Episodes you'd
     * already played start out archived, matching the new auto-archive rule.
     */
    val MEDIA_2_3 = listOf(
        "ALTER TABLE `episodes` ADD COLUMN `archived` INTEGER NOT NULL DEFAULT 0",
        "UPDATE `episodes` SET `archived` = 1 WHERE `played` = 1",
        // Per-show settings.
        "ALTER TABLE `podcasts` ADD COLUMN `autoQueue` INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE `podcasts` ADD COLUMN `autoArchive` INTEGER NOT NULL DEFAULT 1",
        "ALTER TABLE `podcasts` ADD COLUMN `skipIntroSec` INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE `podcasts` ADD COLUMN `skipOutroSec` INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE `podcasts` ADD COLUMN `customEffects` INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE `podcasts` ADD COLUMN `trimSilence` INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE `podcasts` ADD COLUMN `voiceBoost` INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE `podcasts` ADD COLUMN `category` TEXT NOT NULL DEFAULT ''",
    )

    /** All library steps, keyed by (from, to). Kept as plain SQL so tests can run them without Android. */
    val emberSteps: Map<Pair<Int, Int>, List<String>> = mapOf((1 to 2) to EMBER_1_2, (2 to 3) to EMBER_2_3, (3 to 4) to EMBER_3_4)
    val mediaSteps: Map<Pair<Int, Int>, List<String>> = mapOf((1 to 2) to MEDIA_1_2, (2 to 3) to MEDIA_2_3)

    val ember: Array<Migration> = emberSteps.map { (k, sql) -> step(k.first, k.second, sql) }.toTypedArray()
    val media: Array<Migration> = mediaSteps.map { (k, sql) -> step(k.first, k.second, sql) }.toTypedArray()

    private fun step(from: Int, to: Int, sql: List<String>) = object : Migration(from, to) {
        override fun migrate(db: SupportSQLiteDatabase) {
            sql.forEach { db.execSQL(it) }
        }
    }
}

/**
 * Before a new version of Ember opens a database for the first time, a copy of the
 * database files is kept, so an upgrade can never be the thing that loses your library.
 * The last three copies per database are kept in Ember's private storage.
 */
object DbSnapshots {

    private const val KEEP = 3

    fun dir(context: Context): File = File(context.filesDir, "db-snapshots")

    fun beforeOpen(context: Context, name: String) {
        try {
            val prefs = context.getSharedPreferences("db_snapshots", Context.MODE_PRIVATE)
            val current = versionCode(context)
            val seen = prefs.getLong(name, -1L)
            if (seen == current) return
            val db = context.getDatabasePath(name)
            if (db.exists() && db.length() > 0) {
                val stamp = "${name.removeSuffix(".db")}-from-v$seen-${System.currentTimeMillis()}"
                val target = File(dir(context), stamp).apply { mkdirs() }
                for (suffix in listOf("", "-wal", "-shm")) {
                    val f = File(db.path + suffix)
                    if (f.exists()) f.copyTo(File(target, f.name), overwrite = true)
                }
                prune(context, name)
            }
            prefs.edit().putLong(name, current).apply()
        } catch (e: Exception) {
            // A snapshot is a bonus; never block opening the library.
        }
    }

    private fun prune(context: Context, name: String) {
        val prefix = name.removeSuffix(".db") + "-from-"
        dir(context).listFiles { f -> f.isDirectory && f.name.startsWith(prefix) }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(KEEP)
            ?.forEach { it.deleteRecursively() }
    }

    private fun versionCode(context: Context): Long {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
    }
}
