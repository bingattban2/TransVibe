package com.transvibe.app.data.model

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal fun interface AtomicMoveExecutor {
    fun move(source: File, target: File)
}

/**
 * Installs complete files by rename/move only. It never copies bytes into the final path.
 * Atomic replacement is preferred; filesystems that do not support it fall back to a
 * normal replacement move.
 */
internal class SafeFileMover(
    private val atomicMove: AtomicMoveExecutor = AtomicMoveExecutor { source, target ->
        Files.move(
            source.toPath(),
            target.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING
        )
    }
) {
    fun replace(source: File, target: File) {
        require(source.isFile) { "Source file does not exist: ${source.absolutePath}" }
        target.parentFile?.mkdirs()
        try {
            atomicMove.move(source, target)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (_: UnsupportedOperationException) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
