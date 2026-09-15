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
import org.json.JSONArray
import org.json.JSONObject

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

    /**
     * 전체 용어집을 JSON 배열 문자열로 내보낸다. key(소문자 정규화값)는 sourceTerm에서
     * 항상 재계산 가능하므로 내보내지 않는다 — 다른 기기/버전으로 옮길 때 굳이 옛
     * 정규화 규칙에 묶이지 않고 가져오기 시점의 [upsert] 규칙을 그대로 타게 하기 위함이다.
     */
    suspend fun exportToJson(): String {
        val array = JSONArray()
        getAll().forEach { term ->
            array.put(
                JSONObject().apply {
                    put("sourceTerm", term.sourceTerm)
                    put("targetTerm", term.targetTerm)
                }
            )
        }
        return array.toString(2)
    }

    /**
     * JSON 배열 문자열에서 용어집을 가져와 병합한다(기존 항목을 지우지 않음 — 같은
     * sourceTerm이 있으면 upsert 규칙에 따라 갱신됨). 개별 항목이 형식에 안 맞으면
     * (sourceTerm/targetTerm 누락 등) 그 항목만 건너뛰고 계속 진행한다 — 파일 일부가
     * 깨졌다고 가져오기 전체를 실패시키면 정상인 나머지 항목까지 못 쓰게 되기 때문이다.
     * @return 실제로 가져온(성공적으로 upsert된) 항목 수.
     */
    suspend fun importFromJson(json: String): Int {
        val array = JSONArray(json)
        var count = 0
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val sourceTerm = obj.optString("sourceTerm").takeIf { it.isNotBlank() } ?: continue
            val targetTerm = obj.optString("targetTerm").takeIf { it.isNotBlank() } ?: continue
            upsert(sourceTerm, targetTerm)
            count++
        }
        return count
    }
}
