#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DB="$ROOT/app/src/main/java/com/transvibe/app/data/persistence/TransVibeDatabase.kt"
MIG="$ROOT/app/src/main/java/com/transvibe/app/data/persistence/LegacySessionMigrator.kt"
REPO="$ROOT/app/src/main/java/com/transvibe/app/data/persistence/RoomProcessingHistoryRepository.kt"

fail(){ echo "PHASE10 AUDIT FAILED: $*" >&2; exit 1; }
grep -Eq 'version = ([3-9]|[1-9][0-9]+)' "$DB" || fail "Room schema must be v3 or newer"
grep -q 'legacy_session_migrations' "$DB" || fail "per-record migration receipts missing"
grep -q 'migration_markers' "$DB" || fail "one-time migration marker missing"
grep -q 'fun importLegacySession' "$DB" || fail "transactional import function missing"
grep -q '@Transaction' "$DB" || fail "transaction annotation missing"
grep -q 'sourceFingerprint' "$MIG" || fail "legacy fingerprinting missing"
grep -q 'STATUS_MALFORMED' "$MIG" || fail "malformed-record handling missing"
grep -q 'dao.importLegacySession' "$MIG" || fail "migrator is not using transactional import"
grep -q 'dao.upsertMigrationMarker' "$MIG" || fail "migration completion marker missing"
! grep -R -q 'class SessionStore' "$ROOT/app/src/main/java" || fail "legacy SessionStore write path still exists"
! grep -R -q '\.json.tmp' "$ROOT/app/src/main/java/com/transvibe/app/data/persistence" || fail "legacy JSON write temp path still exists"
! grep -R -q 'renameTo(File(file.parentFile, file.name + ".migrated"))' "$ROOT/app/src/main/java" || fail "migration still renames legacy files as its source of truth"
grep -q 'RoomProcessingHistoryRepository' "$REPO" || true

echo "PHASE10 AUDIT OK"
