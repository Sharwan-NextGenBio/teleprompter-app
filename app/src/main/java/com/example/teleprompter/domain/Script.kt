package com.example.teleprompter.domain

import kotlinx.coroutines.flow.Flow

data class Script(
    val id: Long = 0,
    val title: String,
    val body: String,
    val folder: String = "",
    val updatedAt: Long = System.currentTimeMillis()
)

interface ScriptRepository {
    fun observeAll(): Flow<List<Script>>
    suspend fun get(id: Long): Script?
    suspend fun save(script: Script): Long
    suspend fun delete(script: Script)
}
