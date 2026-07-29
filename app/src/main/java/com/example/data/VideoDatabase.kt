package com.example.data

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "saved_videos")
data class SavedVideo(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val title: String,
    val url: String,
    val isFavorite: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
)

@Dao
interface VideoDao {
    @Query("SELECT * FROM saved_videos ORDER BY timestamp DESC")
    fun getAllSavedVideos(): Flow<List<SavedVideo>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertVideo(video: SavedVideo)

    @Delete
    suspend fun deleteVideo(video: SavedVideo)

    @Query("DELETE FROM saved_videos WHERE id = :id")
    suspend fun deleteVideoById(id: Int)
}

@Database(entities = [SavedVideo::class], version = 1, exportSchema = false)
abstract class VideoDatabase : RoomDatabase() {
    abstract fun videoDao(): VideoDao

    companion object {
        @Volatile
        private var INSTANCE: VideoDatabase? = null

        fun getDatabase(context: Context): VideoDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    VideoDatabase::class.java,
                    "video_player_database"
                )
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

class VideoRepository(private val videoDao: VideoDao) {
    val allVideos: Flow<List<SavedVideo>> = videoDao.getAllSavedVideos()

    suspend fun insert(video: SavedVideo) = videoDao.insertVideo(video)

    suspend fun delete(video: SavedVideo) = videoDao.deleteVideo(video)

    suspend fun deleteById(id: Int) = videoDao.deleteVideoById(id)
}
