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
 */
@Entity(tableName = "glossary_terms")
data class GlossaryTerm(
    @PrimaryKey val sourceTerm: String,
    val targetTerm: String
)

@Dao
interface GlossaryDao {
    @Query("SELECT * FROM glossary_terms ORDER BY sourceTerm")
    suspend fun getAll(): List<GlossaryTerm>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(term: GlossaryTerm)

    @Query("DELETE FROM glossary_terms WHERE sourceTerm = :sourceTerm")
    suspend fun delete(sourceTerm: String)
}

@Database(entities = [GlossaryTerm::class], version = 1, exportSchema = false)
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
                ).build().also { INSTANCE = it }
            }
        }
    }
}

/** 용어집 CRUD를 담당하는 리포지토리. UI(설정 화면 등)와 GlossaryApplier가 이 클래스만 알면 된다. */
class Glossary(context: Context) {
    private val dao = GlossaryDatabase.getInstance(context).glossaryDao()

    suspend fun getAll(): List<GlossaryTerm> = dao.getAll()

    suspend fun upsert(sourceTerm: String, targetTerm: String) {
        dao.upsert(GlossaryTerm(sourceTerm.trim(), targetTerm.trim()))
    }

    suspend fun delete(sourceTerm: String) {
        dao.delete(sourceTerm)
    }
}
