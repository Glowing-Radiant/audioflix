#!/usr/bin/env bash
# Prints the body of a version's section from CHANGELOG.md, without its "## [x.y.z]" heading.
# Usage: scripts/changelog-section.sh 1.2.3 [CHANGELOG.md]
set -euo pipefail
version="$1"
file="${2:-CHANGELOG.md}"
awk -v version="$version" '
  /^## \[/ {
    if (found) exit
    if (index($0, "## [" version "]") == 1) { found = 1; next }
  }
  found { print }
' "$file" | sed -e '/./,$!d' | sed -e ':a' -e '/^\n*$/{$d;N;ba' -e '}'
