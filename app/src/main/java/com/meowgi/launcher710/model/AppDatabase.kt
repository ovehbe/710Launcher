package com.meowgi.launcher710.model

import android.content.Context
import androidx.room.*

@Entity(tableName = "app_stats")
data class AppStats(
    @PrimaryKey val componentName: String,
    val launchCount: Int = 0,
    val isFavorite: Boolean = false,
    val lastLaunched: Long = 0
)

@Dao
interface AppStatsDao {
    @Query("SELECT * FROM app_stats")
    suspend fun getAll(): List<AppStats>

    @Query("SELECT * FROM app_stats WHERE componentName = :name")
    suspend fun get(name: String): AppStats?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(stats: AppStats)

    @Query("UPDATE app_stats SET launchCount = launchCount + 1, lastLaunched = :time WHERE componentName = :name")
    suspend fun incrementLaunch(name: String, time: Long)

    @Query("UPDATE app_stats SET isFavorite = :fav WHERE componentName = :name")
    suspend fun setFavorite(name: String, fav: Boolean)

    @Query("SELECT * FROM app_stats WHERE isFavorite = 1")
    suspend fun getFavorites(): List<AppStats>

    @Query("SELECT * FROM app_stats ORDER BY launchCount DESC LIMIT :limit")
    suspend fun getFrequent(limit: Int = 30): List<AppStats>
}

@Database(entities = [AppStats::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun appStatsDao(): AppStatsDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun get(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "bbos7_launcher.db"
                ).build().also { INSTANCE = it }
            }
        }
    }
}
