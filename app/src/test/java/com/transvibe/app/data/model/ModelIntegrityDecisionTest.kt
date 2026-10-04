package com.transvibe.app.data.model

import com.transvibe.app.domain.model.ModelIntegrityState
import org.junit.Assert.assertEquals
import org.junit.Test

class ModelIntegrityDecisionTest {
    @Test
    fun `valid model is ready`() {
        assertEquals(
            ModelIntegrityState.READY,
            decide(metadata = LocalMetadataState.CURRENT)
        )
    }

    @Test
    fun `missing model is missing`() {
        assertEquals(
            ModelIntegrityState.MISSING,
            decide(finalExists = false, size = false, sha = false, native = false)
        )
    }

    @Test
    fun `wrong size is binary corrupted`() {
        assertEquals(ModelIntegrityState.BINARY_CORRUPTED, decide(size = false))
    }

    @Test
    fun `wrong sha is binary corrupted`() {
        assertEquals(ModelIntegrityState.BINARY_CORRUPTED, decide(sha = false))
    }

    @Test
    fun `invalid native model is binary corrupted`() {
        assertEquals(ModelIntegrityState.BINARY_CORRUPTED, decide(native = false))
    }

    @Test
    fun `missing metadata is not binary corruption`() {
        assertEquals(
            ModelIntegrityState.METADATA_MISSING,
            decide(metadata = LocalMetadataState.MISSING)
        )
    }

    @Test
    fun `stale metadata is not binary corruption`() {
        assertEquals(
            ModelIntegrityState.METADATA_STALE,
            decide(metadata = LocalMetadataState.STALE)
        )
    }

    private fun decide(
        finalExists: Boolean = true,
        size: Boolean = true,
        sha: Boolean = true,
        native: Boolean = true,
        metadata: LocalMetadataState = LocalMetadataState.CURRENT
    ) = ModelIntegrityDecision.decide(finalExists, size, sha, native, metadata)
}
