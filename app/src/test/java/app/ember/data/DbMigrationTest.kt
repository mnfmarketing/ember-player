package app.ember.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/**
 * Proves each database upgrade step produces exactly the tables Room expects for the
 * new version, and that existing rows survive. Uses the schema files Room exports at
 * compile time (app/schemas), so a mismatch fails CI before it can reach a phone.
 */
class DbMigrationTest {

    private fun schema(db: String, version: Int): JSONObject {
        val f = File("schemas/$db/$version.json")
        assertTrue("Missing exported schema ${f.absolutePath}", f.exists())
        return JSONObject(f.readText()).getJSONObject("database")
    }

    private fun open(): Connection = DriverManager.getConnection("jdbc:sqlite::memory:")

    /** Creates every table (and index) of a schema version, as Room would on a fresh install. */
    private fun create(conn: Connection, schema: JSONObject) {
        val entities = schema.getJSONArray("entities")
        conn.createStatement().use { st ->
            for (i in 0 until entities.length()) {
                val e = entities.getJSONObject(i)
                val table = e.getString("tableName")
                st.execute(e.getString("createSql").replace("\${TABLE_NAME}", table))
                val idx = e.optJSONArray("indices") ?: continue
                for (j in 0 until idx.length()) {
                    st.execute(idx.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                }
            }
        }
    }

    private data class Col(val name: String, val type: String, val notNull: Int, val default: String?, val pk: Int)

    private fun columns(conn: Connection, table: String): Set<Col> =
        conn.createStatement().use { st ->
            st.executeQuery("PRAGMA table_info(`$table`)").use { rs ->
                buildSet {
                    while (rs.next()) {
                        add(Col(rs.getString("name"), rs.getString("type").uppercase(), rs.getInt("notnull"), rs.getString("dflt_value"), rs.getInt("pk")))
                    }
                }
            }
        }

    private fun indexes(conn: Connection, table: String): Set<String> =
        conn.createStatement().use { st ->
            st.executeQuery("PRAGMA index_list(`$table`)").use { rs ->
                buildSet {
                    while (rs.next()) {
                        val name = rs.getString("name")
                        if (!name.startsWith("sqlite_autoindex")) add(name + ":" + rs.getInt("unique"))
                    }
                }
            }
        }

    private fun tables(conn: Connection): Set<String> =
        conn.createStatement().use { st ->
            st.executeQuery("SELECT name FROM sqlite_master WHERE type = 'table'").use { rs ->
                buildSet {
                    while (rs.next()) {
                        val n = rs.getString(1)
                        if (n != "sqlite_sequence" && n != "room_master_table" && n != "android_metadata") add(n)
                    }
                }
            }
        }

    private fun count(conn: Connection, table: String): Int =
        conn.createStatement().use { st -> st.executeQuery("SELECT COUNT(*) FROM `$table`").use { it.next(); it.getInt(1) } }

    /** Puts a row of plausible data in every table so we can check nothing is lost. */
    private fun seed(conn: Connection, schema: JSONObject): Map<String, Int> {
        val entities = schema.getJSONArray("entities")
        val counts = mutableMapOf<String, Int>()
        conn.createStatement().use { st ->
            for (i in 0 until entities.length()) {
                val e = entities.getJSONObject(i)
                val table = e.getString("tableName")
                val fields = e.getJSONArray("fields")
                val names = mutableListOf<String>()
                val values = mutableListOf<String>()
                for (j in 0 until fields.length()) {
                    val f = fields.getJSONObject(j)
                    names += "`" + f.getString("columnName") + "`"
                    values += when (f.getString("affinity")) {
                        "INTEGER" -> (j + 1).toString()
                        "REAL" -> "1.5"
                        else -> "'v$j'"
                    }
                }
                st.execute("INSERT INTO `$table` (${names.joinToString()}) VALUES (${values.joinToString()})")
                counts[table] = 1
            }
        }
        return counts
    }

    private fun checkAllSteps(db: String, steps: Map<Pair<Int, Int>, List<String>>) {
        val latest = File("schemas/$db").listFiles()!!.mapNotNull { it.nameWithoutExtension.toIntOrNull() }.max()
        assertTrue("$db: no upgrade steps", steps.isNotEmpty())
        for (from in 1 until latest) {
            val sql = steps[from to from + 1] ?: throw AssertionError("$db: no upgrade step from $from to ${from + 1}")
            val old = schema(db, from)
            val new = schema(db, from + 1)
            open().use { migrated ->
                create(migrated, old)
                val seeded = seed(migrated, old)
                migrated.createStatement().use { st -> sql.forEach { st.execute(it) } }
                open().use { fresh ->
                    create(fresh, new)
                    assertEquals("$db $from to ${from + 1}: tables", tables(fresh), tables(migrated))
                    for (t in tables(fresh)) {
                        assertEquals("$db $from to ${from + 1}: columns of $t", columns(fresh, t), columns(migrated, t))
                        assertEquals("$db $from to ${from + 1}: indexes of $t", indexes(fresh, t), indexes(migrated, t))
                    }
                }
                seeded.forEach { (t, n) -> assertEquals("$db $from to ${from + 1}: rows kept in $t", n, count(migrated, t)) }
            }
            println("$db: upgrade $from to ${from + 1} matches Room's schema and keeps every row")
        }
        // Run the whole chain from the very first version too.
        open().use { conn ->
            create(conn, schema(db, 1))
            seed(conn, schema(db, 1))
            for (from in 1 until latest) conn.createStatement().use { st -> steps.getValue(from to from + 1).forEach { st.execute(it) } }
            open().use { fresh ->
                create(fresh, schema(db, latest))
                for (t in tables(fresh)) assertEquals("$db 1 to $latest: columns of $t", columns(fresh, t), columns(conn, t))
            }
        }
    }

    @Test fun libraryUpgrades() = checkAllSteps("app.ember.data.EmberDatabase", DbMigrations.emberSteps)

    @Test fun podcastUpgrades() = checkAllSteps("app.ember.podcasts.MediaDatabase", DbMigrations.mediaSteps)
}
