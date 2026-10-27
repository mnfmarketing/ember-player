package app.ember.sync

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.text.Normalizer

/*
 * Sync between devices (the Fold, an iPhone, an iPad) through a hidden Ember folder on Google Drive.
 *
 * Everything that syncs is a list of small items, each with a key ("b/<book>/pos", "mark/<created>",
 * "ep/<feed>|<guid>"...), a value (canonical JSON text), when it last changed, on which device, and
 * the thing it belongs to (a book, a show, or "*" for the whole library).
 *
 * Each device keeps a "shadow": the items as they were after its last sync. Comparing the library
 * with the shadow tells what changed here; comparing the shadow with the file on Drive tells what
 * changed elsewhere. When both changed, the newest change wins, item by item.
 *
 * This file has no Android code, so the merge rules are unit-tested (SyncMergeTest) and the iOS
 * version follows exactly the same format.
 */

/** One synced item. [v] null means it was deleted (kept so the deletion reaches other devices). */
data class SyncEntry(val v: String?, val t: Long, val d: String, val o: String)

/**
 * An item as it is in this device's library right now. [o] is what it belongs to.
 * [hinted]: the item carries its own time (when the book or episode was last played) in [hint],
 * which is used instead of "now" when it changed.
 */
data class LocalItem(val v: String, val o: String, val hint: Long = 0L, val hinted: Boolean = false)

/** Canonical JSON: sorted keys, no floats, the same text on every device for the same value. */
object Canon {

    /** Builds an object from pairs. Values: String, Int, Long, Boolean, List<String>, or null. */
    fun obj(vararg pairs: Pair<String, Any?>): String {
        val sb = StringBuilder()
        write(sb, JSONObject().apply { pairs.forEach { (k, v) -> put(k, toJson(v)) } })
        return sb.toString()
    }

    private fun toJson(v: Any?): Any = when (v) {
        null -> JSONObject.NULL
        is List<*> -> JSONArray().apply { v.forEach { put(it?.toString() ?: JSONObject.NULL) } }
        is Int, is Long, is Boolean, is String -> v as Any
        else -> v.toString()
    }

    /** The canonical form of any JSON text (null for null or unreadable text). */
    fun of(text: String?): String? {
        if (text == null) return null
        return try {
            val sb = StringBuilder()
            write(sb, JSONTokener(text).nextValue())
            sb.toString()
        } catch (e: Exception) {
            null
        }
    }

    fun same(a: String?, b: String?): Boolean = a == b || (a != null && b != null && of(a) == of(b))

    private fun write(sb: StringBuilder, v: Any?) {
        when (v) {
            null, JSONObject.NULL -> sb.append("null")
            is JSONObject -> {
                sb.append('{')
                v.keys().asSequence().toList().sorted().forEachIndexed { i, k ->
                    if (i > 0) sb.append(',')
                    str(sb, k)
                    sb.append(':')
                    write(sb, v.opt(k))
                }
                sb.append('}')
            }
            is JSONArray -> {
                sb.append('[')
                for (i in 0 until v.length()) {
                    if (i > 0) sb.append(',')
                    write(sb, v.opt(i))
                }
                sb.append(']')
            }
            is Boolean -> sb.append(if (v) "true" else "false")
            is Int, is Long, is Short, is Byte -> sb.append(v.toString())
            is Number -> {
                val d = v.toDouble()
                if (d == Math.floor(d) && !d.isInfinite() && kotlin.math.abs(d) < 9.0E15) sb.append(d.toLong().toString()) else sb.append(d.toString())
            }
            else -> str(sb, v.toString())
        }
    }

    private fun str(sb: StringBuilder, s: String) {
        sb.append('"')
        for (ch in s) {
            when {
                ch == '"' -> sb.append("\\\"")
                ch == '\\' -> sb.append("\\\\")
                ch == '\n' -> sb.append("\\n")
                ch == '\r' -> sb.append("\\r")
                ch == '\t' -> sb.append("\\t")
                ch < ' ' -> sb.append(String.format("\\u%04x", ch.code))
                else -> sb.append(ch)
            }
        }
        sb.append('"')
    }
}

/** Keys that identify the same book or show on every device. */
object SyncKeys {

    /** Lower case, no accents, letters and digits only ("The Lantern Thief" → "thelanternthief"). */
    fun norm(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase()
            .filter { it.isLetterOrDigit() }

    /** A book's key: its title and author. Books in other apps get an "x|" in front. Null when there's no title. */
    fun book(title: String, author: String, external: Boolean): String? {
        val t = norm(title)
        if (t.isEmpty()) return null
        return (if (external) "x|" else "") + t + "|" + norm(author)
    }

    fun show(feedUrl: String) = "f|" + feedUrl.trim()

    fun episode(feedUrl: String, guid: String) = feedUrl.trim() + "|" + guid

    /** The name of a book's log file on Drive. */
    fun logFile(bookKey: String): String {
        val md = java.security.MessageDigest.getInstance("SHA-1").digest(bookKey.toByteArray())
        return "log-" + md.joinToString("") { "%02x".format(it) }.take(20) + ".json"
    }

    /** Item kinds whose deletion on one device deletes them everywhere. */
    fun deletable(key: String): Boolean =
        key.startsWith("mark/") || key.startsWith("clip/") || key.startsWith("shelf/") ||
            key.startsWith("shelfbook/") || key.startsWith("show/")
}

/** The synced state as stored in state.json on Drive (and as the shadow on each device). */
class SyncState(
    val items: MutableMap<String, SyncEntry> = HashMap(),
    /** Device id → (name, last seen). */
    val devices: MutableMap<String, Pair<String, Long>> = HashMap(),
    /** The shadow only: what this device had at its last sync (books and shows present). */
    val owners: MutableSet<String> = HashSet(),
    /** The shadow only: items that couldn't be put into this library yet (a show that wouldn't load). */
    val unapplied: MutableSet<String> = HashSet(),
) {
    fun toJson(): String {
        val o = JSONObject()
        o.put("app", "Ember")
        o.put("sync", FORMAT)
        val items = JSONObject()
        this.items.toSortedMap().forEach { (k, e) ->
            val j = JSONObject().put("t", e.t).put("d", e.d).put("o", e.o)
            if (e.v != null) j.put("v", e.v)
            items.put(k, j)
        }
        o.put("items", items)
        val devs = JSONObject()
        devices.forEach { (id, p) -> devs.put(id, JSONObject().put("name", p.first).put("seen", p.second)) }
        o.put("devices", devs)
        if (owners.isNotEmpty()) o.put("owners", JSONArray(owners.sorted()))
        if (unapplied.isNotEmpty()) o.put("unapplied", JSONArray(unapplied.sorted()))
        return o.toString()
    }

    companion object {
        const val FORMAT = 1

        fun parse(text: String?): SyncState {
            val s = SyncState()
            if (text.isNullOrBlank()) return s
            val o = try {
                JSONObject(text)
            } catch (e: Exception) {
                return s
            }
            o.optJSONObject("items")?.let { items ->
                items.keys().forEach { k ->
                    val j = items.optJSONObject(k) ?: return@forEach
                    val v = if (j.has("v") && !j.isNull("v")) j.optString("v") else null
                    s.items[k] = SyncEntry(v, j.optLong("t"), j.optString("d"), j.optString("o"))
                }
            }
            o.optJSONObject("devices")?.let { devs ->
                devs.keys().forEach { id ->
                    val j = devs.optJSONObject(id) ?: return@forEach
                    s.devices[id] = j.optString("name") to j.optLong("seen")
                }
            }
            o.optJSONArray("owners")?.let { a -> for (i in 0 until a.length()) s.owners += a.optString(i) }
            o.optJSONArray("unapplied")?.let { a -> for (i in 0 until a.length()) s.unapplied += a.optString(i) }
            return s
        }
    }
}

object SyncMerge {

    /** Deleted items are forgotten after this long. */
    const val TOMBSTONE_DAYS = 120L

    class Result(
        /** What everyone should have now. */
        val merged: Map<String, SyncEntry>,
        /** Items this device must change in its library to match. */
        val apply: Map<String, SyncEntry>,
        /** Items changed here that won (they go to Drive). */
        val won: Set<String>,
        /** True when Drive's copy needs writing. */
        val upload: Boolean,
    )

    /**
     * [remote]: the items on Drive. [shadow]: this device's items after its last sync.
     * [local]: the library now. [ownersNow]/[ownersBefore]: books and shows present now / at the last
     * sync. [frozen]: items not to read from the library this time (a question is waiting about them).
     */
    fun merge(
        remote: Map<String, SyncEntry>,
        shadow: Map<String, SyncEntry>,
        local: Map<String, LocalItem>,
        ownersNow: Set<String>,
        ownersBefore: Set<String>,
        me: String,
        now: Long,
        frozen: Set<String> = emptySet(),
    ): Result {
        val merged = HashMap(remote)
        // Anything Drive lost (a deleted file) comes back from what this device last had.
        for ((k, e) in shadow) if (k !in merged) merged[k] = e

        // What changed here since the last sync.
        val changes = HashMap<String, SyncEntry>()
        for ((k, li) in local) {
            if (k in frozen) continue
            val sv = shadow[k]
            if (sv != null && Canon.same(sv.v, li.v)) continue
            val firstSeen = li.o !in ownersBefore
            val stamp = when {
                li.hinted -> li.hint
                firstSeen -> 0L
                else -> now
            }
            changes[k] = SyncEntry(li.v, stamp, me, li.o)
        }
        // Marking a book finished or starting it over moves its place too, even though it wasn't played.
        for ((k, e) in changes.toMap()) {
            if (!k.endsWith("/status") || e.t != now) continue
            val pk = k.removeSuffix("/status") + "/pos"
            val li = local[pk] ?: continue
            if (pk in frozen) continue
            changes[pk] = SyncEntry(li.v, maxOf(now, li.hint), me, li.o)
        }
        // Deleted here: bookmarks, clips, shelves and shows that were here at the last sync and are gone now.
        for ((k, se) in shadow) {
            if (se.v == null || k in local || k in frozen) continue
            if (!SyncKeys.deletable(k)) continue
            if (se.o !in ownersNow || se.o !in ownersBefore) continue
            changes[k] = SyncEntry(null, now, me, se.o)
        }

        // Newest wins; a tie goes to this device.
        val won = HashSet<String>()
        for ((k, le) in changes) {
            val r = merged[k]
            if (r != null && Canon.same(r.v, le.v)) continue
            if (r == null || le.t >= r.t) {
                merged[k] = le
                won += k
            }
        }

        // Forget deletions old enough that every device has seen them.
        val cutoff = now - TOMBSTONE_DAYS * 86_400_000L
        merged.entries.removeIf { it.value.v == null && it.value.t < cutoff }

        // What this library must change to match.
        val apply = HashMap<String, SyncEntry>()
        for ((k, e) in merged) {
            if (k in won) continue
            val li = local[k]
            if (e.v == null) {
                if (li != null) apply[k] = e
            } else if (li == null || !Canon.same(li.v, e.v)) {
                apply[k] = e
            }
        }

        val upload = merged.size != remote.size || merged.any { (k, e) ->
            val r = remote[k]
            r == null || r.t != e.t || r.d != e.d || !Canon.same(r.v, e.v)
        }
        return Result(merged, apply, won, upload)
    }
}

/** One listening log entry as stored on Drive. [u] is unique everywhere: "<device>:<number>". */
data class LogEntry(
    val u: String,
    val d: String,
    val at: Long,
    val k: Int,
    val p: Long,
    val to: Long,
    /** Speed × 100. */
    val s: Int,
    val r: Int,
)

/** A book's log file on Drive. */
class LogFile(val bookKey: String, val cleared: Long, val entries: List<LogEntry>) {

    fun toJson(): String {
        val a = JSONArray()
        entries.forEach { e -> a.put(JSONArray().put(e.u).put(e.d).put(e.at).put(e.k).put(e.p).put(e.to).put(e.s).put(e.r)) }
        return JSONObject().put("app", "Ember").put("sync", SyncState.FORMAT).put("book", bookKey).put("cleared", cleared).put("e", a).toString()
    }

    companion object {
        fun parse(text: String?): LogFile? {
            if (text.isNullOrBlank()) return null
            return try {
                val o = JSONObject(text)
                val a = o.optJSONArray("e") ?: JSONArray()
                val list = (0 until a.length()).mapNotNull { i ->
                    val r = a.optJSONArray(i) ?: return@mapNotNull null
                    LogEntry(r.optString(0), r.optString(1), r.optLong(2), r.optInt(3), r.optLong(4), r.optLong(5, -1), r.optInt(6, 100), r.optInt(7))
                }
                LogFile(o.optString("book"), o.optLong("cleared"), list)
            } catch (e: Exception) {
                null
            }
        }
    }
}

object LogMerge {

    const val KEEP = 400

    /**
     * Both devices' entries together, newest first: the newest [KEEP], minus anything from before a
     * "Clear all" on either device. When an entry was changed (quick skips merge into one), the
     * version with the later time wins.
     */
    fun merge(remote: List<LogEntry>, remoteCleared: Long, local: List<LogEntry>, localCleared: Long, keep: Int = KEEP): Pair<List<LogEntry>, Long> {
        val cleared = maxOf(remoteCleared, localCleared)
        val byId = LinkedHashMap<String, LogEntry>()
        for (e in remote + local) {
            val have = byId[e.u]
            if (have == null || e.at > have.at || (e.at == have.at && e.to != have.to)) byId[e.u] = e
        }
        val list = byId.values
            .filter { it.at > cleared }
            .sortedWith(compareByDescending<LogEntry> { it.at }.thenByDescending { it.u })
            .take(keep)
        return list to cleared
    }
}
