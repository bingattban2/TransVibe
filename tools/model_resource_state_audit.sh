#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MANAGER="$ROOT/app/src/main/java/com/transvibe/app/data/download/ModelDownloadManager.kt"
STORE="$ROOT/app/src/main/java/com/transvibe/app/data/model/ModelStore.kt"
OUTCOME="$ROOT/app/src/main/java/com/transvibe/app/data/download/ModelTransferOutcomeStore.kt"
TEST="$ROOT/app/src/test/java/com/transvibe/app/data/download/ModelTransferTerminalEventTest.kt"

require() { grep -Fq "$2" "$1" || { echo "FAIL: missing '$2' in $1"; exit 1; }; }

require "$MANAGER" 'finishTransfer(model, state, ModelTransferTerminalEvent.DOWNLOAD_CANCELLED)'
require "$MANAGER" 'finishTransfer(model, state, ModelTransferTerminalEvent.IMPORT_CANCELLED)'
require "$MANAGER" 'ModelTransferTerminalEvent.DOWNLOAD_FAILED'
require "$MANAGER" 'ModelTransferTerminalEvent.IMPORT_FAILED'
require "$MANAGER" 'ModelTransferTerminalEvent.VERIFICATION_FAILED'
require "$MANAGER" 'ModelTransferTerminalEvent.INSTALLATION_FAILED'
require "$MANAGER" 'store.clearTransferArtifacts(model)'
require "$MANAGER" 'val diskState = resources.refreshModel(model)'
require "$MANAGER" 'outcomes.record(model, event, message)'
require "$STORE" 'rollbackInstall(model, final, backup, hadInstalledModel, candidateMoved)'
require "$OUTCOME" 'model_transfer_outcomes'
require "$TEST" 'downloadCancellationIsTerminalCancelled'
require "$TEST" 'importCancellationIsTerminalCancelled'
require "$TEST" 'downloadVerificationFailureIsTerminalFailed'
require "$TEST" 'downloadInstallationFailureIsTerminalFailed'

echo "MODEL RESOURCE STATE AUDIT PASSED"
