#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/app/src/main/java/com/transvibe/app"
P="$SRC/presentation"
fail(){ echo "PHASE15 AUDIT FAILED: $*" >&2; exit 1; }
for spec in \
  "home/HomeViewModel.kt:class HomeViewModel" \
  "processing/ProcessingViewModel.kt:class ProcessingViewModel" \
  "models/ModelsViewModel.kt:class ModelsViewModel" \
  "history/HistoryViewModel.kt:class HistoryViewModel" \
  "player/PlayerViewModel.kt:class PlayerViewModel" \
  "settings/SettingsViewModel.kt:class SettingsViewModel"; do
  f="${spec%%:*}"; pat="${spec#*:}"
  grep -q "$pat" "$P/$f" || fail "$pat missing"
done
! grep -R -q 'class AppViewModel\|AppViewModel' "$P" || fail 'legacy AppViewModel reference remains'
VM_FILES=$(find "$P" -name '*ViewModel.kt' -type f)
for forbidden in 'processingDao' 'WhisperNative' 'nativebridge' 'AudioDecoder' 'SubtitleProcessingService' 'createEnglishToArabic' 'MlKitTranslationEngine' 'ModelStore' 'NativeWhisperModelBinaryValidator' 'SubtitleExporter' 'contentResolver' 'StatFs'; do
  if grep -nH "$forbidden" $VM_FILES >/dev/null; then
    grep -nH "$forbidden" $VM_FILES >&2
    fail "ViewModel directly owns forbidden responsibility: $forbidden"
  fi
done
grep -q 'class ProcessingTaskController' "$SRC/data/processing/ProcessingTaskController.kt" || fail 'processing service orchestration was not moved out of ViewModels'
grep -q 'class ModelManagementRepository' "$SRC/data/model/ModelManagementRepository.kt" || fail 'model/translation management repository missing'
grep -q 'class PlayerRepository' "$SRC/data/player/PlayerRepository.kt" || fail 'player export/source-access repository missing'
grep -q 'class StorageRepository' "$SRC/data/storage/StorageRepository.kt" || fail 'storage inspection repository missing'
grep -q 'class VideoSelectionRepository' "$SRC/data/media/VideoSelectionRepository.kt" || fail 'video selection repository missing'
echo 'PHASE15 SPLIT VIEWMODEL AUDIT PASSED'
