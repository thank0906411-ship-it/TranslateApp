package com.senkiro.translateapp.history

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

/**
 * 방문한 URL 하나의 기록. isFavorite이 true면 즐겨찾기로도 취급되어 최근 방문
 * 목록 정리(오래된 것 자동 삭제) 대상에서 제외된다.
 */
@Entity(tableName = "history_entries")
data class HistoryEntry(
    @PrimaryKey val url: String,
    val title: String,
    val lastVisitedAt: Long,
    val isFavorite: Boolean
)

@Dao
interface HistoryDao {
    @Query("SELECT * FROM history_entries ORDER BY lastVisitedAt DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<HistoryEntry>

    @Query("SELECT * FROM history_entries WHERE isFavorite = 1 ORDER BY lastVisitedAt DESC")
    suspend fun getFavorites(): List<HistoryEntry>

    @Query("SELECT * FROM history_entries WHERE url = :url LIMIT 1")
    suspend fun find(url: String): HistoryEntry?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: HistoryEntry)

    @Query("DELETE FROM history_entries WHERE url = :url")
    suspend fun delete(url: String)

    // 즐겨찾기(isFavorite=1)는 방문 기록이 아무리 쌓여도 지우지 않는다 — 사용자가
    // 명시적으로 등록한 항목이라 자동 정리 대상이 아니다.
    @Query(
        "DELETE FROM history_entries WHERE isFavorite = 0 AND url NOT IN " +
            "(SELECT url FROM history_entries WHERE isFavorite = 0 ORDER BY lastVisitedAt DESC LIMIT :keep)"
    )
    suspend fun trimNonFavorites(keep: Int)
}

@Database(entities = [HistoryEntry::class], version = 1, exportSchema = false)
abstract class HistoryDatabase : RoomDatabase() {
    abstract fun historyDao(): HistoryDao

    companion object {
        @Volatile private var INSTANCE: HistoryDatabase? = null

        fun getInstance(context: Context): HistoryDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    HistoryDatabase::class.java,
                    "history.db"
                ).fallbackToDestructiveMigration().build().also { INSTANCE = it }
            }
        }
    }
}

/** 방문 기록/즐겨찾기 CRUD를 담당하는 리포지토리. */
class History(context: Context) {
    private val dao = HistoryDatabase.getInstance(context).historyDao()

    companion object {
        /** 즐겨찾기가 아닌 최근 방문 기록을 이 개수만큼만 유지한다. */
        private const val MAX_NON_FAVORITE_ENTRIES = 50
    }

    suspend fun getRecent(limit: Int = MAX_NON_FAVORITE_ENTRIES): List<HistoryEntry> = dao.getRecent(limit)

    suspend fun getFavorites(): List<HistoryEntry> = dao.getFavorites()

    /** 페이지 방문 시 호출 — 이미 즐겨찾기였던 URL이면 즐겨찾기 상태를 유지한 채 방문 시각/제목만 갱신한다. */
    suspend fun recordVisit(url: String, title: String) {
        val existing = dao.find(url)
        dao.upsert(
            HistoryEntry(
                url = url,
                title = title.ifBlank { url },
                lastVisitedAt = System.currentTimeMillis(),
                isFavorite = existing?.isFavorite ?: false
            )
        )
        dao.trimNonFavorites(MAX_NON_FAVORITE_ENTRIES)
    }

    suspend fun setFavorite(url: String, title: String, isFavorite: Boolean) {
        val existing = dao.find(url)
        dao.upsert(
            HistoryEntry(
                url = url,
                title = existing?.title ?: title.ifBlank { url },
                lastVisitedAt = existing?.lastVisitedAt ?: System.currentTimeMillis(),
                isFavorite = isFavorite
            )
        )
    }

    suspend fun delete(url: String) {
        dao.delete(url)
    }
}
