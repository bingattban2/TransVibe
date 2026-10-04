package com.transvibe.app.service

import java.util.concurrent.ConcurrentHashMap

/**
 * Process-local liveness registry only. This is not processing state and is never exposed to UI.
 * Room remains the authoritative state store; this registry only prevents startup reconciliation
 * from interrupting a RUNNING row whose service is demonstrably active in the current process.
 */
object ProcessingServiceRuntimeRegistry {
    private val activeJobIds = ConcurrentHashMap.newKeySet<String>()

    fun markActive(jobId: String) {
        activeJobIds += jobId
    }

    fun markInactive(jobId: String) {
        activeJobIds -= jobId
    }

    fun isActive(jobId: String): Boolean = jobId in activeJobIds
}
