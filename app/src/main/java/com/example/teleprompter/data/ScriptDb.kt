package com.example.teleprompter.data

import android.content.Context
import androidx.room.*
import com.example.teleprompter.domain.Script
import com.example.teleprompter.domain.ScriptRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Entity(tableName = "scripts")
data class ScriptEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val body: String,
    val folder: String = "",
    val updatedAt: Long = System.currentTimeMillis()
)

@Dao
interface ScriptDao {
    @Query("SELECT * FROM scripts ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<ScriptEntity>>

    @Query("SELECT * FROM scripts WHERE id = :id")
    suspend fun get(id: Long): ScriptEntity?

    @Upsert suspend fun upsert(s: ScriptEntity): Long
    @Delete suspend fun delete(s: ScriptEntity)
}

@Database(entities = [ScriptEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun scriptDao(): ScriptDao

    companion object {
        @Volatile private var inst: AppDatabase? = null
        fun get(ctx: Context): AppDatabase = inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(ctx.applicationContext, AppDatabase::class.java, "scripts.db")
                .build().also { inst = it }
        }
    }
}

class ScriptRepositoryImpl(private val dao: ScriptDao) : ScriptRepository {
    private fun ScriptEntity.toDomain() = Script(id, title, body, folder, updatedAt)
    private fun Script.toEntity() = ScriptEntity(id, title, body, folder, System.currentTimeMillis())

    override fun observeAll(): Flow<List<Script>> = dao.observeAll().map { l -> l.map { it.toDomain() } }
    override suspend fun get(id: Long) = dao.get(id)?.toDomain()
    override suspend fun save(script: Script) = dao.upsert(script.toEntity())
    override suspend fun delete(script: Script) = dao.delete(script.toEntity())
}
