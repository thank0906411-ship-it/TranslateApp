package com.senkiro.translateapp.cache

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase

@Entity(tableName = "translation_cache")
data class TranslationEntity(
    @PrimaryKey val urlHash: String,
    val sourceUrl: String,
    val sourceLang: String,
    val targetLang: String,
    val originalText: String,
    val translatedText: String,
    val timestamp: Long
)

@Dao
interface TranslationDao {

    @Query("SELECT * FROM translation_cache WHERE urlHash = :urlHash LIMIT 1")
    suspend fun find(urlHash: String): TranslationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: TranslationEntity)

    @Query("DELETE FROM translation_cache WHERE timestamp < :beforeTimestamp")
    suspend fun deleteOlderThan(beforeTimestamp: Long)
}

@Database(entities = [TranslationEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun translationDao(): TranslationDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "translate_cache.db"
                ).build().also { INSTANCE = it }
            }
        }
    }
}

/**
 * 같은 URL을 반복 번역하지 않도록 캐싱을 담당하는 리포지토리 계층.
 * MainActivity/ViewModel은 이 클래스만 알면 되고, Room 세부사항은 몰라도 된다.
 */
class TranslationCache(context: Context) {

    private val dao = AppDatabase.getInstance(context).translationDao()

    suspend fun get(url: String, sourceLang: String, targetLang: String): TranslationEntity? {
        return dao.find(makeHash(url, sourceLang, targetLang))
    }

    suspend fun put(
        url: String,
        sourceLang: String,
        targetLang: String,
        originalText: String,
        translatedText: String
    ) {
        dao.insert(
            TranslationEntity(
                urlHash = makeHash(url, sourceLang, targetLang),
                sourceUrl = url,
                sourceLang = sourceLang,
                targetLang = targetLang,
                originalText = originalText,
                translatedText = translatedText,
                timestamp = System.currentTimeMillis()
            )
        )
    }

    private fun makeHash(url: String, sourceLang: String, targetLang: String): String {
        return "$url|$sourceLang|$targetLang".hashCode().toString()
    }
}
