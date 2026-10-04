#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DB="$ROOT/app/src/main/java/com/transvibe/app/data/persistence/TransVibeDatabase.kt"
REPO="$ROOT/app/src/main/java/com/transvibe/app/data/persistence/ProcessingRepository.kt"
PROCESSING_VM="$ROOT/app/src/main/java/com/transvibe/app/presentation/processing/ProcessingViewModel.kt"
HISTORY_VM="$ROOT/app/src/main/java/com/transvibe/app/presentation/history/HistoryViewModel.kt"
SERVICE="$ROOT/app/src/main/java/com/transvibe/app/service/SubtitleProcessingService.kt"
APP="$ROOT/app/src/main/java/com/transvibe/app/TransVibeApplication.kt"

fail(){ echo "PHASE11 AUDIT FAILED: $*" >&2; exit 1; }

grep -q 'class ProcessingRepository' "$REPO" || fail "ProcessingRepository missing"
grep -q 'fun observeCurrentJob(): Flow<ProcessingJobEntity?>' "$DB" || fail "Room current-job Flow missing"
grep -Eq 'fun observe(Completed|Terminal)Summaries\(limit: Int\): Flow' "$DB" || fail "Room history Flow missing"
grep -q 'stage TEXT NOT NULL' "$DB" || fail "durable processing stage migration missing"
grep -q 'is_current INTEGER NOT NULL' "$DB" || fail "durable current-job selection missing"
grep -q 'MIGRATION_3_4' "$DB" || fail "Room v3->v4 migration missing"
grep -q 'processingRepository.currentState.stateIn' "$PROCESSING_VM" || fail "ProcessingViewModel is not observing repository state"
grep -q 'repository.observeHistory().stateIn' "$HISTORY_VM" || fail "HistoryViewModel history is not Room Flow backed"
grep -q 'processingRepository.updateCheckpoint' "$SERVICE" || fail "service does not persist state through repository"
grep -Eq 'processingRepository\.(saveCompleted|completePersistedSession)' "$SERVICE" || fail "service completion does not persist through repository"
grep -Eq 'processingRepository\.(markUnfinishedJobsInterrupted|reconcileRunningJobs)' "$APP" || fail "process recreation recovery missing"
! grep -R -q 'ProcessBus' "$ROOT/app/src/main/java" || fail "ProcessBus still participates in production state"
! grep -R -q 'graph\.processingJobs\|graph\.sessions' "$ROOT/app/src/main/java" || fail "legacy direct processing stores remain in use"

echo "PHASE11 ROOM SOURCE-OF-TRUTH AUDIT PASSED"
