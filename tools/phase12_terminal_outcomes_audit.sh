#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DB="$ROOT/app/src/main/java/com/transvibe/app/data/persistence/TransVibeDatabase.kt"
REPO="$ROOT/app/src/main/java/com/transvibe/app/data/persistence/ProcessingRepository.kt"
SERVICE="$ROOT/app/src/main/java/com/transvibe/app/service/SubtitleProcessingService.kt"
APP="$ROOT/app/src/main/java/com/transvibe/app/TransVibeApplication.kt"
REG="$ROOT/app/src/main/java/com/transvibe/app/service/ProcessingServiceRuntimeRegistry.kt"
HISTORY="$ROOT/app/src/main/java/com/transvibe/app/data/persistence/RoomProcessingHistoryRepository.kt"

need() { grep -Fq "$2" "$1" || { echo "MISSING: $2 in $1" >&2; exit 1; }; }
for f in "$DB" "$REPO" "$SERVICE" "$APP" "$REG" "$HISTORY"; do test -f "$f" || { echo "MISSING FILE: $f" >&2; exit 1; }; done

need "$DB" "fun finishJob(jobId: String, state: String, errorCode: String?, timestamp: Long): Int"
need "$DB" "AND state IN ('QUEUED', 'RUNNING')"
need "$DB" "fun listRunningJobs(): List<ProcessingJobEntity>"
need "$DB" "AND state = 'RUNNING'"
need "$DB" "WHERE state IN ('COMPLETED', 'FAILED', 'CANCELLED', 'INTERRUPTED')"
need "$REPO" "fun finish("
need "$REPO" "fun reconcileRunningJobs("
need "$REPO" "ERROR_PROCESS_INTERRUPTED"
need "$SERVICE" "ProcessingServiceRuntimeRegistry.markActive(jobId)"
need "$SERVICE" "ProcessingServiceRuntimeRegistry.markInactive(jobId)"
need "$SERVICE" "ProcessingJobState.CANCELLED"
need "$SERVICE" "ProcessingJobState.FAILED"
need "$APP" "processingRepository.reconcileRunningJobs(ProcessingServiceRuntimeRegistry::isActive)"
need "$REG" "ConcurrentHashMap.newKeySet<String>()"
need "$HISTORY" "dao.listTerminalSummaries"

if grep -Fq "markUnfinishedJobsInterrupted" "$APP" "$REPO" "$DB"; then
  echo "STALE PHASE11 startup interruption path still present" >&2
  exit 1
fi

echo "PHASE12 TERMINAL OUTCOMES AUDIT PASSED"
