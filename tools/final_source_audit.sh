#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
fail(){ printf 'FAIL: %s\n' "$1" >&2; exit 1; }
pass(){ printf 'PASS: %s\n' "$1"; }

MANIFEST=app/src/main/AndroidManifest.xml
CMAKE=app/src/main/cpp/CMakeLists.txt
WHISPER_KT=app/src/main/java/com/transvibe/app/nativebridge/whisper/WhisperNative.kt
MODELS=app/src/main/java/com/transvibe/app/domain/model/Models.kt
SRC=app/src/main/java

for p in READ_EXTERNAL_STORAGE WRITE_EXTERNAL_STORAGE MANAGE_EXTERNAL_STORAGE; do
  ! grep -q "$p" "$MANIFEST" || fail "dangerous storage permission present: $p"
done
pass "no broad/legacy storage permissions"

grep -q 'android:exported="false"' "$MANIFEST" || fail "processing service is not explicitly non-exported"
pass "processing service is explicitly non-exported"

grep -q 'usesCleartextTraffic="false"' "$MANIFEST" || fail "cleartext traffic is not disabled"
pass "cleartext traffic disabled"

lib_kt="$(sed -n 's/.*System.loadLibrary("\([^"]*\)").*/\1/p' "$WHISPER_KT" | head -1)"
lib_cmake="$(sed -n 's/.*OUTPUT_NAME "\([^"]*\)".*/\1/p' "$CMAKE" | head -1)"
[[ -n "$lib_kt" && "$lib_kt" == "$lib_cmake" ]] || fail "System.loadLibrary ($lib_kt) does not match CMake OUTPUT_NAME ($lib_cmake)"
pass "native library name matches Kotlin and CMake: $lib_kt"

grep -q 'RegisterNatives' app/src/main/cpp/native-lib.cpp || fail "RegisterNatives missing"
for method in nativeCreateContext nativeDetectLanguage nativeTranscribeJson nativeCancel nativeReleaseContext; do
  grep -q "private external fun $method" "$WHISPER_KT" || fail "Kotlin JNI declaration missing: $method"
  grep -Fq "{const_cast<char*>(\"$method\")" app/src/main/cpp/native-lib.cpp || fail "RegisterNatives entry missing: $method"
done
pass "JNI declarations and registrations are present for all bridge methods"

python3 - "$MODELS" <<'PY'
import re, sys
s=open(sys.argv[1],encoding='utf-8').read()
urls=re.findall(r'downloadUrl\s*=\s*"([^"]+)"',s)
sha=re.findall(r'sha256\s*=\s*"([0-9a-fA-F]+)"',s)
if not urls or any(not u.startswith('https://') for u in urls): raise SystemExit('model URL is not HTTPS')
if len(sha)!=len(urls) or any(len(x)!=64 for x in sha): raise SystemExit('model SHA-256 metadata invalid')
print(f'PASS: {len(urls)} model descriptors use HTTPS and trusted-length SHA-256 values')
PY

# Scan only TransVibe-owned production source. Vendored whisper.cpp has upstream TODO/FIXME markers by design.
if grep -RInE 'TODO|FIXME|NotImplemented|NotImplementedError|dummy|fake|mock|placeholder|example\.com|hardcoded subtitle|artificial progress' "$SRC" --include='*.kt'; then
  fail "placeholder/development marker found in production Kotlin source"
fi
pass "no implementation placeholders in TransVibe-owned production Kotlin"

# Exact empty returns are allowed only when they represent mathematically valid empty output.
empty_hits="$(grep -RInE 'return[[:space:]]+""|return[[:space:]]+emptyList\(\)' "$SRC" --include='*.kt' || true)"
if [[ -n "$empty_hits" ]]; then
  printf 'INFO: legitimate empty-return sites require review:\n%s\n' "$empty_hits"
fi

# Phase 15: focused screen ViewModels replace the former AppViewModel.
for vm in HomeViewModel ProcessingViewModel ModelsViewModel HistoryViewModel PlayerViewModel SettingsViewModel; do
  [[ "$(grep -RIl "class $vm" "$SRC" --include='*.kt' | wc -l)" -eq 1 ]] || fail "expected exactly one $vm"
done
[[ "$(grep -RIl 'class AppViewModel' "$SRC" --include='*.kt' | wc -l)" -eq 0 ]] || fail "legacy AppViewModel must be removed"
[[ "$(grep -RIl 'class SessionStore' "$SRC" --include='*.kt' | wc -l)" -eq 0 ]] || fail "legacy SessionStore must be removed after Room migration"
[[ "$(grep -RIl 'class RoomProcessingHistoryRepository' "$SRC" --include='*.kt' | wc -l)" -eq 1 ]] || fail "RoomProcessingHistoryRepository must have exactly one implementation"
[[ "$(grep -RIl 'class ModelDownloadManager' "$SRC" --include='*.kt' | wc -l)" -eq 1 ]] || fail "duplicate ModelDownloadManager implementation"
[[ "$(grep -RIl 'class AudioDecoder' "$SRC" --include='*.kt' | wc -l)" -eq 1 ]] || fail "duplicate AudioDecoder implementation"
pass "focused ViewModels and single authoritative download/history/audio implementations"

bash tools/coroutine_source_audit.sh

printf 'SOURCE AUDIT PASSED\n'

"$ROOT/tools/compose_lifecycle_audit.sh"
bash "$ROOT/tools/model_resource_state_audit.sh"
