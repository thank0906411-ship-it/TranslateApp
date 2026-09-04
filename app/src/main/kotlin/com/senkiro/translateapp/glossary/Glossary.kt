package com.senkiro.translateapp.glossary

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
 * 사용자가 등록한 "원문 용어 -> 고정 번역어" 매핑 하나를 담는다.
 * ML Kit/Cloud Translation 둘 다 커스텀 용어집을 번역 엔진에 직접 넘기는 기능이
 * 없으므로, GlossaryApplier가 번역 전/후로 텍스트를 치환하는 방식으로 흉내낸다.
 *
 * @param key 대소문자를 구분하지 않는 검색/중복 방지용 기본 키(소문자로 정규화됨).
 *   GlossaryApplier가 매칭할 때 ignoreCase로 찾으므로, "Amazon"으로 등록한 뒤
 *   "amazon"을 다시 등록하면 별개의 행이 아니라 같은 항목이 갱신되어야 한다.
 * @param sourceTerm 사용자가 실제로 입력한 표기 그대로 (목록 화면에 이 값을 보여준다).
 */
@Entity(tableName = "glossary_terms")
data class GlossaryTerm(
    @PrimaryKey val key: String,
    val sourceTerm: String,
    val targetTerm: String
)

@Dao
interface GlossaryDao {
    @Query("SELECT * FROM glossary_terms ORDER BY sourceTerm")
    suspend fun getAll(): List<GlossaryTerm>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(term: GlossaryTerm)

    @Query("DELETE FROM glossary_terms WHERE key = :key")
    suspend fun delete(key: String)
}

// 대소문자 무관 중복 방지를 위해 PK를 sourceTerm -> key로 바꾸면서 스키마가 변경되어
// 버전을 올렸다. 이 기능은 아직 배포 초기라 destructiveMigration으로 충분하다.
@Database(entities = [GlossaryTerm::class], version = 2, exportSchema = false)
abstract class GlossaryDatabase : RoomDatabase() {
    abstract fun glossaryDao(): GlossaryDao

    companion object {
        @Volatile private var INSTANCE: GlossaryDatabase? = null

        fun getInstance(context: Context): GlossaryDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    GlossaryDatabase::class.java,
                    "glossary.db"
                ).fallbackToDestructiveMigration().build().also { INSTANCE = it }
            }
        }
    }
}

/** 용어집 CRUD를 담당하는 리포지토리. UI(설정 화면 등)와 GlossaryApplier가 이 클래스만 알면 된다. */
class Glossary(context: Context) {
    private val dao = GlossaryDatabase.getInstance(context).glossaryDao()

    suspend fun getAll(): List<GlossaryTerm> = dao.getAll()

    suspend fun upsert(sourceTerm: String, targetTerm: String) {
        val trimmedSource = sourceTerm.trim()
        dao.upsert(GlossaryTerm(key = trimmedSource.lowercase(), sourceTerm = trimmedSource, targetTerm = targetTerm.trim()))
    }

    suspend fun delete(term: GlossaryTerm) {
        dao.delete(term.key)
    }
}
