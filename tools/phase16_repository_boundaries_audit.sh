#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/app/src/main/java/com/transvibe/app"
REPOS="$SRC/domain/repository/Repositories.kt"

for contract in ProcessingRepository ModelRepository SessionRepository SettingsRepository TranslationPackRepository; do
  grep -q "interface $contract" "$REPOS" || { echo "Missing $contract"; exit 1; }
done

if grep -RIn --include='*.kt' '^import android\.' "$SRC/domain"; then
  echo "Android framework type leaked into domain layer"
  exit 1
fi

if grep -RIn --include='*.kt' -E '^import com\.transvibe\.app\.data\..*Repository' "$SRC/presentation"; then
  echo "Presentation imports a concrete data repository"
  exit 1
fi

for vm in HomeViewModel ModelsViewModel ProcessingViewModel HistoryViewModel PlayerViewModel SettingsViewModel; do
  file="$(grep -Rl --include='*.kt' "class $vm" "$SRC/presentation" | head -n1)"
  test -n "$file" || { echo "Missing $vm"; exit 1; }
  if grep -q 'AndroidViewModel' "$file" || grep -q 'TransVibeApplication' "$file"; then
    echo "$vm still uses application/service-locator injection"
    exit 1
  fi
done

echo "PHASE 16 repository boundary audit: PASS"
