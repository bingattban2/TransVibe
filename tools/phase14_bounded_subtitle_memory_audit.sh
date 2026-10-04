#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SERVICE="$ROOT/app/src/main/java/com/transvibe/app/service/SubtitleProcessingService.kt"
REPO="$ROOT/app/src/main/java/com/transvibe/app/data/persistence/ProcessingRepository.kt"
DB="$ROOT/app/src/main/java/com/transvibe/app/data/persistence/TransVibeDatabase.kt"
EXPORTER="$ROOT/app/src/main/java/com/transvibe/app/data/subtitle/SubtitleExporter.kt"
BUILD="$ROOT/app/build.gradle.kts"
need(){ grep -Fq "$2" "$1" || { echo "MISSING: $2 in $1" >&2; exit 1; }; }
need "$BUILD" 'ndkVersion = "29.0.14206865"'
[[ "$(grep -Fc 'ndkVersion = "29.0.14206865"' "$BUILD")" -eq 1 ]] || { echo 'ndkVersion must appear exactly once' >&2; exit 1; }
need "$BUILD" 'ndk { abiFilters += "arm64-v8a" }'
need "$BUILD" 'jniLibs.directories.add("src/main/jniLibs")'
need "$BUILD" 'useLegacyPackaging = false'
need "$BUILD" 'keepDebugSymbols += "**/*.so"'
need "$SERVICE" 'private const val ROLLING_WINDOW_SEGMENTS = 12'
need "$SERVICE" 'private const val TRANSLATION_BATCH_SEGMENTS = 48'
need "$SERVICE" 'private const val EXPORT_BATCH_SEGMENTS = 64'
need "$SERVICE" 'val rollingSegments = resume.rollingSegments.toMutableList()'
need "$SERVICE" 'val overflow = (rollingSegments.size - ROLLING_WINDOW_SEGMENTS).coerceAtLeast(0)'
need "$SERVICE" 'loadSegmentBatch(jobId, afterIndex, TRANSLATION_BATCH_SEGMENTS)'
need "$SERVICE" 'writeSrtBatches'
need "$SERVICE" 'writeVttBatches'
need "$REPO" 'fun loadSegmentBatch('
need "$REPO" 'fun segmentBatches('
need "$DB" 'fun getRecentSegments('
need "$DB" 'fun getSegmentBatchAfter('
need "$EXPORTER" 'fun writeSrtBatches('
need "$EXPORTER" 'fun writeVttBatches('
if grep -Eq 'val[[:space:]]+all[[:space:]]*=.*toMutableList|SubtitlePostProcessor\.assemble\(all\)|ArrayList<SubtitleSegment>\(prepared\.size\)' "$SERVICE"; then
  echo 'Unbounded processing subtitle list detected' >&2; exit 1
fi
if grep -Fq 'jniLibs.srcDirs(' "$BUILD"; then
  echo 'Deprecated jniLibs.srcDirs configuration remains' >&2; exit 1
fi
if grep -Fq 'jniLibs.directories.add(file(' "$BUILD"; then
  echo 'Wrong AGP 9.4 jniLibs directories overload detected' >&2; exit 1
fi
echo 'PHASE14 BOUNDED SUBTITLE MEMORY AUDIT PASSED'
