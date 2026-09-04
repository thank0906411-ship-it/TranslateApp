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
import java.util.concurrent.TimeUnit

/**
 * 원문 문장 하나 단위로 캐싱한다 (URL 단위가 아님).
 * 같은 문장이 여러 페이지에 반복 등장하는 사이트(메뉴, 공통 문구 등)에서
 * 재번역을 피할 수 있고, 페이지 구조가 조금 바뀌어도 캐시가 계속 유효하다.
 */
@Entity(tableName = "translation_cache")
data class TranslationEntity(
    @PrimaryKey val textHash: String,
    val sourceLang: String,
    val targetLang: String,
    val originalText: String,
    val translatedText: String,
    val timestamp: Long
)

@Dao
interface TranslationDao {

    @Query("SELECT * FROM translation_cache WHERE textHash = :textHash LIMIT 1")
    suspend fun find(textHash: String): TranslationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: TranslationEntity)

    @Query("DELETE FROM translation_cache WHERE timestamp < :beforeTimestamp")
    suspend fun deleteOlderThan(beforeTimestamp: Long)
}

@Database(entities = [TranslationEntity::class], version = 2, exportSchema = false)
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
                ).fallbackToDestructiveMigration().build().also { INSTANCE = it }
            }
        }
    }
}

/** 문단/블록 단위 번역 캐시. WebView에서 뽑아낸 블록(p, li, h1~h6 등)마다 이걸 거쳐간다. */
class TranslationCache(context: Context) {

    private val dao = AppDatabase.getInstance(context).translationDao()

    suspend fun get(text: String, sourceLang: String, targetLang: String): String? {
        return dao.find(makeHash(text, sourceLang, targetLang))?.translatedText
    }

    suspend fun put(text: String, sourceLang: String, targetLang: String, translatedText: String) {
        dao.insert(
            TranslationEntity(
                textHash = makeHash(text, sourceLang, targetLang),
                sourceLang = sourceLang,
                targetLang = targetLang,
                originalText = text,
                translatedText = translatedText,
                timestamp = System.currentTimeMillis()
            )
        )
    }

    /** 지정한 기간보다 오래된 캐시 항목을 지운다. */
    suspend fun deleteOlderThan(beforeTimestamp: Long) {
        dao.deleteOlderThan(beforeTimestamp)
    }

    private fun makeHash(text: String, sourceLang: String, targetLang: String): String {
        return "$text|$sourceLang|$targetLang".hashCode().toString()
    }

    companion object {
        val MAX_AGE_MILLIS = TimeUnit.DAYS.toMillis(30)
    }
}
