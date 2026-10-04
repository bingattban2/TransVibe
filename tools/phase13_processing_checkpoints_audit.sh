#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DB="$ROOT/app/src/main/java/com/transvibe/app/data/persistence/TransVibeDatabase.kt"
REPO="$ROOT/app/src/main/java/com/transvibe/app/data/persistence/ProcessingRepository.kt"
SERVICE="$ROOT/app/src/main/java/com/transvibe/app/service/SubtitleProcessingService.kt"
DECODER="$ROOT/app/src/main/java/com/transvibe/app/media/decoder/AudioDecoder.kt"
VM="$ROOT/app/src/main/java/com/transvibe/app/presentation/processing/ProcessingViewModel.kt"
SCREEN="$ROOT/app/src/main/java/com/transvibe/app/presentation/processing/ProcessingScreen.kt"
need(){ grep -Fq "$2" "$1" || { echo "MISSING: $2 in $1" >&2; exit 1; }; }
need "$DB" 'last_completed_media_timestamp_ms'
need "$DB" 'last_persisted_segment_index'
need "$DB" 'fun persistCompletedChunk('
need "$DB" 'detected_language = COALESCE(:detectedLanguage, detected_language)'
need "$DB" 'MIGRATION_4_5'
need "$REPO" 'fun prepareResume('
need "$REPO" 'fun persistCompletedChunk('
need "$REPO" 'fun requeueCurrentInterrupted('
need "$SERVICE" 'prepareResume(jobId, RESUME_OVERLAP_MS)'
need "$SERVICE" 'persistChunkCheckpoint(jobId, rollingSegments, rollingStartIndex, chunk.endMs, recognizeProgress, detected)'
need "$SERVICE" 'private const val RESUME_OVERLAP_MS = 3_000L'
need "$DECODER" 'startMs: Long = 0L'
need "$DECODER" 'MediaExtractor.SEEK_TO_PREVIOUS_SYNC'
need "$DECODER" 'info.presentationTimeUs >= requestedStartUs'
need "$VM" 'fun resume()'
need "$SCREEN" 'Text("استئناف المعالجة")'
if grep -R -E 'serialize.*whisper_context|restore.*whisper_context|write.*nativeHandle|read.*nativeHandle' "$ROOT/app/src/main/java" >/dev/null; then
  echo 'Native whisper context persistence detected' >&2; exit 1
fi
echo 'PHASE13 PROCESSING CHECKPOINTS AUDIT PASSED'
