#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/app/src/main/java"
fail() { echo "PHASE 17 FAIL: $1" >&2; exit 1; }

if grep -R -nE 'app\.graph|application.*\.graph|TransVibeViewModelFactory\([^)]*graph' "$SRC"; then
  fail "AppGraph is still used as a service locator"
fi
if grep -R -n 'AppGraph' "$SRC/com/transvibe/app/presentation"; then
  fail "Presentation layer depends on AppGraph"
fi
if grep -R -n 'AppGraph' "$SRC/com/transvibe/app/service"; then
  fail "Service layer depends on AppGraph"
fi
grep -q 'private lateinit var graph: AppGraph' "$SRC/com/transvibe/app/TransVibeApplication.kt" || fail "Application does not privately own AppGraph"
grep -q 'private val .* by lazy' "$SRC/com/transvibe/app/AppGraph.kt" || fail "AppGraph dependencies are not lazy/application-owned"
grep -q 'fun inject(service: SubtitleProcessingService)' "$SRC/com/transvibe/app/AppGraph.kt" || fail "Android service injection boundary missing"
echo "PHASE 17 PASS: AppGraph is a private composition root with explicit injection."
