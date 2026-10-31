package com.meowgi.launcher710.model

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Entity(tableName = "app_stats")
data class AppStats(
    @PrimaryKey val componentName: String,
    val launchCount: Int = 0,
    val isFavorite: Boolean = false,
    val lastLaunched: Long = 0,
    /** Decayed launch score as of [frecencyAt]; see SearchRanker. */
    @ColumnInfo(defaultValue = "0") val frecency: Double = 0.0,
    @ColumnInfo(defaultValue = "0") val frecencyAt: Long = 0
)

@Dao
interface AppStatsDao {
    @Query("SELECT * FROM app_stats")
    suspend fun getAll(): List<AppStats>

    @Query("SELECT * FROM app_stats WHERE componentName = :name")
    suspend fun get(name: String): AppStats?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(stats: AppStats)

    @Query("UPDATE app_stats SET launchCount = 0, lastLaunched = 0, frecency = 0, frecencyAt = 0")
    suspend fun resetUsage()
    @Query("UPDATE app_stats SET isFavorite = :fav WHERE componentName = :name")
    suspend fun setFavorite(name: String, fav: Boolean)

    @Query("SELECT * FROM app_stats WHERE isFavorite = 1")
    suspend fun getFavorites(): List<AppStats>

    @Query("SELECT * FROM app_stats ORDER BY launchCount DESC LIMIT :limit")
    suspend fun getFrequent(limit: Int = 30): List<AppStats>

    @Query("DELETE FROM app_stats")
    suspend fun deleteAll()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(stats: List<AppStats>)
}

@Database(entities = [AppStats::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun appStatsDao(): AppStatsDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /** Seeds frecency from the existing counts so upgrading users keep their ranking. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE app_stats ADD COLUMN frecency REAL NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE app_stats ADD COLUMN frecencyAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE app_stats SET frecency = launchCount, frecencyAt = lastLaunched")
            }
        }

        fun get(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "bbos7_launcher.db"
                ).addMigrations(MIGRATION_1_2).build().also { INSTANCE = it }
            }
        }
    }
}
