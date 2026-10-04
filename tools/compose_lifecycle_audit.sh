#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PRESENTATION="$ROOT/app/src/main/java/com/transvibe/app/presentation"
BUILD="$ROOT/app/build.gradle.kts"
VERSIONS="$ROOT/gradle/libs.versions.toml"

fail() { echo "FAIL: $1" >&2; exit 1; }
pass() { echo "PASS: $1"; }

if rg -n 'collectAsState\(' "$PRESENTATION" >/dev/null; then
  rg -n 'collectAsState\(' "$PRESENTATION" >&2
  fail "lifecycle-unaware collectAsState remains in presentation code"
fi
pass "no lifecycle-unaware collectAsState remains in presentation code"

rg -q 'androidx-lifecycle-runtime-compose' "$VERSIONS" || fail "lifecycle-runtime-compose catalog dependency missing"
rg -q 'implementation\(libs\.androidx\.lifecycle\.runtime\.compose\)' "$BUILD" || fail "lifecycle-runtime-compose app dependency missing"
pass "lifecycle-runtime-compose dependency is configured"

state_count="$(rg -c 'collectAsStateWithLifecycle\(' "$PRESENTATION" | awk -F: '{s+=$2} END {print s+0}')"
[ "$state_count" -gt 0 ] || fail "no lifecycle-aware state collectors found"
pass "Compose StateFlow/Flow state collectors use collectAsStateWithLifecycle ($state_count collectors)"

HOME="$PRESENTATION/home/HomeScreen.kt"
rg -q 'repeatOnLifecycle\(Lifecycle\.State\.STARTED\)' "$HOME" || fail "processingReady event flow is not lifecycle-gated"
pass "processingReady event flow is collected only while STARTED"

echo "COMPOSE LIFECYCLE AUDIT PASSED"
