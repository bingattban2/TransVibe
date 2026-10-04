package com.transvibe.app.data.storage

import android.app.Application
import android.os.StatFs
import com.transvibe.app.domain.model.StorageInfo
import com.transvibe.app.domain.repository.StorageInspector
import java.io.File

class StorageRepository(private val app: Application) : StorageInspector {
    override fun inspect(): StorageInfo {
        fun sizeOf(file: File): Long = when {
            !file.exists() -> 0L
            file.isFile -> file.length()
            else -> file.listFiles()?.sumOf(::sizeOf) ?: 0L
        }
        val files = app.filesDir
        val roomDb = app.getDatabasePath("processing_jobs.db")
        val roomBytes = sizeOf(roomDb) + sizeOf(File(roomDb.path + "-wal")) + sizeOf(File(roomDb.path + "-shm"))
        return StorageInfo(
            modelBytes = sizeOf(File(files, "models")),
            sessionBytes = sizeOf(File(files, "sessions")) + roomBytes,
            freeBytes = runCatching { StatFs(files.absolutePath).availableBytes }.getOrDefault(0L)
        )
    }
}
