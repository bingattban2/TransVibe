#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
fail(){ printf 'FAIL: %s\n' "$1" >&2; exit 1; }
pass(){ printf 'PASS: %s\n' "$1"; }
SRC=app/src/main/java

! grep -RIn 'GlobalScope' "$SRC" --include='*.kt' >/dev/null || fail 'GlobalScope is forbidden'
pass 'no GlobalScope usage'

! grep -RIn 'rememberCoroutineScope' "$SRC/com/transvibe/app/presentation" --include='*.kt' >/dev/null || fail 'presentation layer still owns business coroutine work via rememberCoroutineScope'
pass 'presentation business work is not owned by rememberCoroutineScope'

scope_sites="$(grep -RIn 'CoroutineScope(' "$SRC" --include='*.kt' || true)"
scope_count="$(printf '%s\n' "$scope_sites" | sed '/^$/d' | wc -l)"
[[ "$scope_count" -eq 2 ]] || { printf '%s\n' "$scope_sites" >&2; fail "expected only application and service lifecycle scopes, found $scope_count"; }
printf '%s\n' "$scope_sites" | grep -q 'AppGraph.kt' || fail 'application-lifetime scope missing'
printf '%s\n' "$scope_sites" | grep -q 'SubtitleProcessingService.kt' || fail 'service-owned processing scope missing'
pass 'only intentional application/service CoroutineScope owners remain'

VM_ROOT="$SRC/com/transvibe/app/presentation"
vm_launches="$(grep -RIn 'viewModelScope.launch' "$VM_ROOT" --include='*ViewModel.kt' || true)"
[[ -n "$vm_launches" ]] || fail 'ViewModel work is not owned by viewModelScope'
grep -q 'Channel<Unit>(Channel.CONFLATED)\|Channel<Unit>(capacity = Channel.CONFLATED)' "$VM_ROOT/home/HomeViewModel.kt" || fail 'processing-ready event channel must remain bounded/conflated'
pass 'ViewModel work uses viewModelScope and a bounded event channel'

DOWNLOADS="$SRC/com/transvibe/app/data/download/ModelDownloadManager.kt"
grep -q 'jobs.remove(modelId, ownedJob)' "$DOWNLOADS" || fail 'download job registry does not remove by owner identity'
grep -q 'CoroutineStart.LAZY' "$DOWNLOADS" || fail 'download job ownership is not installed before execution'
pass 'model download jobs have identity-safe ownership'

SERVICE="$SRC/com/transvibe/app/service/SubtitleProcessingService.kt"
python3 - "$SERVICE" <<'PY'
import sys
s=open(sys.argv[1],encoding='utf-8').read()
required=[
    'cancelNativeInference()\n        activeJob?.cancel(CancellationException("Foreground service timeout"))',
    'cancelNativeInference()\n        val job = activeJob',
    'cancelNativeInference()\n        activeJob?.cancel()'
]
for pattern in required:
    if pattern not in s:
        raise SystemExit('native cancellation is not wired ahead of every service-job cancellation path')
if 'catch (ce: CancellationException) {\n            cancelNativeInference()' not in s or 'throw ce' not in s:
    raise SystemExit('service CancellationException boundary does not propagate cancellation')
PY
pass 'service cancellation reaches native inference and propagates'

python3 - "$SRC" <<'PY'
import pathlib,re,sys
root=pathlib.Path(sys.argv[1])
# Broad catches in suspend/withContext/launch-heavy files must have an explicit cancellation catch nearby.
async_files=[]
for p in root.rglob('*.kt'):
    text=p.read_text(encoding='utf-8')
    if any(x in text for x in ('suspend fun','withContext(','launch {','launch(')):
        async_files.append((p,text))
violations=[]
for p,text in async_files:
    for m in re.finditer(r'catch\s*\([^)]*:\s*(Throwable|Exception)\s*\)',text):
        prefix=text[max(0,m.start()-700):m.start()]
        # Specialized synchronous/native wrapper files are not coroutine boundaries unless they contain suspend/launch/withContext near this catch.
        local=text[max(0,m.start()-700):m.start()+100]
        if not any(x in local for x in ('suspend fun','withContext(','launch {','launch(')):
            continue
        if 'CancellationException' not in prefix:
            violations.append(f'{p}:{text.count(chr(10),0,m.start())+1}')
if violations:
    raise SystemExit('broad async catches without nearby CancellationException handling: '+', '.join(violations))
PY
pass 'broad asynchronous catches preserve CancellationException'

printf 'COROUTINE SOURCE AUDIT PASSED\n'
