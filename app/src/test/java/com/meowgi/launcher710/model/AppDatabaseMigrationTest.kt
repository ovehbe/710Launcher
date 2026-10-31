package com.meowgi.launcher710.model

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager

/** Runs [AppDatabase.MIGRATION_1_2_SQL] on a v1 table exactly as Room 2.6 created it. */
class AppDatabaseMigrationTest {

    private lateinit var db: Connection

    @Before
    fun setUp() {
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        db.createStatement().use {
            it.execute(
                "CREATE TABLE IF NOT EXISTS `app_stats` (`componentName` TEXT NOT NULL, `launchCount` INTEGER NOT NULL, " +
                    "`isFavorite` INTEGER NOT NULL, `lastLaunched` INTEGER NOT NULL, PRIMARY KEY(`componentName`))"
            )
            it.execute("INSERT INTO app_stats VALUES ('com.a/.Main', 12, 1, 1793000000123)")
            it.execute("INSERT INTO app_stats VALUES ('com.b/.Main', 0, 1, 0)")
            it.execute("INSERT INTO app_stats VALUES ('com.c/.Main', 3, 0, 1700000000000)")
        }
    }

    @After
    fun tearDown() = db.close()

    private fun migrate() = db.createStatement().use { st -> AppDatabase.MIGRATION_1_2_SQL.forEach { st.execute(it) } }

    @Test
    fun keepsCountsAndSeedsFrecencyFromThem() {
        migrate()
        val rows = mutableMapOf<String, List<Any>>()
        db.createStatement().use { st ->
            st.executeQuery("SELECT componentName, launchCount, isFavorite, lastLaunched, frecency, frecencyAt FROM app_stats").use { rs ->
                while (rs.next()) rows[rs.getString(1)] = listOf(rs.getInt(2), rs.getInt(3), rs.getLong(4), rs.getDouble(5), rs.getLong(6))
            }
        }
        assertEquals(listOf<Any>(12, 1, 1793000000123L, 12.0, 1793000000123L), rows["com.a/.Main"])
        assertEquals(listOf<Any>(0, 1, 0L, 0.0, 0L), rows["com.b/.Main"])
        assertEquals(listOf<Any>(3, 0, 1700000000000L, 3.0, 1700000000000L), rows["com.c/.Main"])
    }

    @Test
    fun newColumnsMatchWhatRoomExpects() {
        migrate()
        val cols = mutableMapOf<String, Triple<String, Boolean, String?>>()
        db.createStatement().use { st ->
            st.executeQuery("PRAGMA table_info(app_stats)").use { rs ->
                while (rs.next()) cols[rs.getString("name")] = Triple(rs.getString("type"), rs.getInt("notnull") == 1, rs.getString("dflt_value"))
            }
        }
        // Entity: Double / Long with @ColumnInfo(defaultValue = "0").
        assertEquals(Triple("REAL", true, "0"), cols["frecency"])
        assertEquals(Triple("INTEGER", true, "0"), cols["frecencyAt"])
        assertEquals(6, cols.size)
    }

    @Test
    fun rowsInsertedAfterMigrationGetDefaults() {
        migrate()
        db.createStatement().use { st ->
            st.execute("INSERT INTO app_stats (componentName, launchCount, isFavorite, lastLaunched) VALUES ('com.d/.Main', 0, 0, 0)")
            st.executeQuery("SELECT frecency, frecencyAt FROM app_stats WHERE componentName = 'com.d/.Main'").use { rs ->
                rs.next()
                assertEquals(0.0, rs.getDouble(1), 0.0)
                assertEquals(0L, rs.getLong(2))
            }
        }
    }
}
