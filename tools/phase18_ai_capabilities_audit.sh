#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MODEL="$ROOT/app/src/main/java/com/transvibe/app/domain/model/Models.kt"
SRC="$ROOT/app/src/main/java"
fail() { echo "PHASE 18 FAIL: $1" >&2; exit 1; }

for capability in SPEECH_RECOGNITION SPEECH_TO_ENGLISH_TRANSLATION TARGET_LANGUAGE_TRANSLATION VAD; do
  grep -q "$capability" "$MODEL" || fail "Missing capability: $capability"
done
if grep -R -n 'translationReady' "$SRC"; then fail "Legacy combined translationReady flag remains"; fi
grep -q 'ProcessingCapabilityPlanner' "$MODEL" || fail "Pipeline capability planner missing"
grep -q 'source == "auto"' "$MODEL" || fail "Automatic-language conditional branch missing"
grep -q '"en" -> required += AiCapability.TARGET_LANGUAGE_TRANSLATION' "$MODEL" || fail "English->Arabic requirement missing"
grep -q 'required += AiCapability.SPEECH_TO_ENGLISH_TRANSLATION' "$MODEL" || fail "Other-language speech->English requirement missing"
grep -q 'checkPipeline(model, request)' "$SRC/com/transvibe/app/data/processing/ProcessVideoPreflight.kt" || fail "Preflight is not request-aware"
grep -q 'pipelineReadiness is ProcessingResourceReadiness.Ready' "$SRC/com/transvibe/app/presentation/home/HomeScreen.kt" || fail "UI enablement is not pipeline-aware"
echo "PHASE 18 PASS: AI capabilities are independent and readiness is pipeline-derived."
