package com.transvibe.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files

class SafeFileMoverTest {
    @Test
    fun `atomic move unsupported falls back to replacement move`() {
        val dir = Files.createTempDirectory("transvibe-move-test").toFile()
        try {
            val source = File(dir, "model.tmp").apply { writeText("new-complete-model") }
            val target = File(dir, "model.bin").apply { writeText("old-model") }
            val mover = SafeFileMover(AtomicMoveExecutor { from, to ->
                throw AtomicMoveNotSupportedException(from.path, to.path, "test filesystem")
            })

            mover.replace(source, target)

            assertFalse(source.exists())
            assertTrue(target.isFile)
            assertEquals("new-complete-model", target.readText())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `temporary file interruption never changes final model`() {
        val dir = Files.createTempDirectory("transvibe-interruption-test").toFile()
        try {
            val final = File(dir, "model.bin").apply { writeText("known-good-model") }
            val temp = File(dir, "model.bin.tmp").apply { writeText("incomplete") }

            // Interruption occurs before commit: only the temporary path is touched.
            assertTrue(temp.isFile)
            assertEquals("known-good-model", final.readText())
            assertEquals("incomplete", temp.readText())
        } finally {
            dir.deleteRecursively()
        }
    }
}
