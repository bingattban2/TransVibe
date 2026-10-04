package com.transvibe.app.data.model

import com.transvibe.app.domain.model.ModelIntegrityState

internal enum class LocalMetadataState { CURRENT, MISSING, STALE }

/** Pure integrity decision used by ModelStore and covered by JVM tests. */
internal object ModelIntegrityDecision {
    fun decide(
        finalExists: Boolean,
        sizeMatches: Boolean,
        shaMatches: Boolean,
        nativeValid: Boolean,
        metadataState: LocalMetadataState
    ): ModelIntegrityState {
        if (!finalExists) return ModelIntegrityState.MISSING
        if (!sizeMatches || !shaMatches || !nativeValid) return ModelIntegrityState.BINARY_CORRUPTED
        return when (metadataState) {
            LocalMetadataState.CURRENT -> ModelIntegrityState.READY
            LocalMetadataState.MISSING -> ModelIntegrityState.METADATA_MISSING
            LocalMetadataState.STALE -> ModelIntegrityState.METADATA_STALE
        }
    }
}
