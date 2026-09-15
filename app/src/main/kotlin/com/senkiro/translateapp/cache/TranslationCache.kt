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
import java.security.MessageDigest
import java.text.Normalizer
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

/**
 * "자동 감지" 모드에서 텍스트 하나의 언어를 판별한 결과만 따로 캐싱한다(번역 결과는
 * 담지 않음). 자동 감지는 실제 언어를 미리 모르는 채로는 [TranslationEntity]의 캐시
 * 키(원문+sourceLang+targetLang)를 만들 수 없어 번역 캐시를 건너뛰는데, 감지 자체를
 * 이 테이블로 따로 캐싱해두면 재방문 시 감지 API 호출(ML Kit 추론 또는 Cloud
 * Translation의 감지 겸용 호출)을 건너뛰고, 감지된 언어로 [TranslationEntity] 캐시를
 * 바로 조회할 수 있다. sourceLang이 없으므로(애초에 이걸 알아내는 게 목적) 원문
 * 텍스트만으로 키를 만든다 — 도착어가 바뀌면 번역 자체는 다시 해야 하지만 "이 텍스트가
 * 무슨 언어인지"는 도착어와 무관하므로 그대로 재사용해도 안전하다.
 */
@Entity(tableName = "language_detection_cache")
data class LanguageDetectionEntity(
    @PrimaryKey val textHash: String,
    /** 감지된 언어 코드. null 판정(감지 실패)은 캐싱하지 않으므로 항상 값이 있다. */
    val detectedLang: String,
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

    @Query("SELECT * FROM language_detection_cache WHERE textHash = :textHash LIMIT 1")
    suspend fun findDetection(textHash: String): LanguageDetectionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDetection(entity: LanguageDetectionEntity)

    @Query("DELETE FROM language_detection_cache WHERE timestamp < :beforeTimestamp")
    suspend fun deleteDetectionsOlderThan(beforeTimestamp: Long)
}

// 캐시 키 해싱 방식이 바뀔 때마다(예: 텍스트 정규화 도입) 버전을 올려 기존 캐시를
// destructiveMigration으로 정리한다 — 옛 방식으로 만들어진 해시가 새 방식과 안 맞아
// 캐시 미스만 계속 나는 것보다, 한 번 비우고 새로 쌓는 게 낫다.
@Database(
    entities = [TranslationEntity::class, LanguageDetectionEntity::class],
    version = 5,
    exportSchema = false
)
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
        dao.deleteDetectionsOlderThan(beforeTimestamp)
    }

    /**
     * 자동 감지 모드에서 이 텍스트의 언어를 이미 판별한 적이 있으면 그 결과를 돌려준다.
     * sourceLang을 모르는 상태에서 조회하는 것이므로 [makeHash]가 아니라 원문만으로
     * 만든 별도 해시를 쓴다(targetLang과도 무관 — 언어 판별은 도착어와 상관없다).
     */
    suspend fun getDetectedLang(text: String): String? {
        return dao.findDetection(makeDetectionHash(text))?.detectedLang
    }

    suspend fun putDetectedLang(text: String, detectedLang: String) {
        dao.insertDetection(
            LanguageDetectionEntity(
                textHash = makeDetectionHash(text),
                detectedLang = detectedLang,
                timestamp = System.currentTimeMillis()
            )
        )
    }

    /**
     * 같은 문장이라도 사이트마다 공백/줄바꿈이 조금씩 다르게 들어있거나(연속 공백,
     * 탭, 개행), 같은 글자를 다른 유니코드 결합 형태(NFC/NFD)로 인코딩하는 경우가 있어
     * 그대로 해싱하면 캐시가 안 맞고 매번 다시 번역을 호출하게 된다. 캐시 키를 만들 때만
     * 정규화하고(원문 표시/번역 결과에는 영향 없음), 히트율을 높인다.
     */
    private fun normalize(text: String): String {
        return Normalizer.normalize(text, Normalizer.Form.NFC)
            .trim()
            .replace(Regex("\\s+"), " ")
    }

    /**
     * String.hashCode()는 32비트 다항식 해시라 서로 다른 문장이 같은 값을 낼 수 있다
     * (충돌 시 완전히 다른 두 문장이 같은 캐시 항목을 공유해 엉뚱한 번역이 나올 수 있음).
     * SHA-256으로 캐시 키를 만들어 충돌 확률을 실질적으로 0에 가깝게 만든다.
     */
    private fun makeHash(text: String, sourceLang: String, targetLang: String): String {
        val input = "${normalize(text)}|$sourceLang|$targetLang"
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun makeDetectionHash(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(normalize(text).toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    companion object {
        val MAX_AGE_MILLIS = TimeUnit.DAYS.toMillis(30)
    }
}
