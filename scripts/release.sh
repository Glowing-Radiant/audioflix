#!/usr/bin/env bash
# Cuts a release: moves the Unreleased changelog entries under the new version, bumps the app
# version, commits and tags. Push with `git push --follow-tags` to publish it.
# Usage: scripts/release.sh 1.2.3
set -euo pipefail
cd "$(dirname "$0")/.."

version="${1:?usage: scripts/release.sh <major.minor.patch>}"
[[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo "Version must look like 1.2.3" >&2; exit 1; }
git diff --quiet && git diff --cached --quiet || { echo "Commit or stash your changes first." >&2; exit 1; }
git rev-parse -q --verify "refs/tags/v$version" >/dev/null && { echo "Tag v$version already exists." >&2; exit 1; }
grep -q "^## \[$version\]" CHANGELOG.md && { echo "CHANGELOG.md already has a $version section." >&2; exit 1; }

notes="$(scripts/changelog-section.sh Unreleased)"
[[ -n "${notes//[[:space:]]/}" ]] || { echo "Add the changes for $version under ## [Unreleased] in CHANGELOG.md first." >&2; exit 1; }

today="$(date +%Y-%m-%d)"
sed -i.bak "s/^## \[Unreleased\]$/## [Unreleased]\n\n## [$version] - $today/" CHANGELOG.md && rm CHANGELOG.md.bak
sed -i.bak "s/^val appVersion = \".*\"$/val appVersion = \"$version\"/" app/build.gradle.kts && rm app/build.gradle.kts.bak
grep -q "^val appVersion = \"$version\"$" app/build.gradle.kts || { echo "Couldn't update appVersion in app/build.gradle.kts" >&2; exit 1; }

git add CHANGELOG.md app/build.gradle.kts
git commit -m "Release $version"
git tag -a "v$version" -m "AudioFlix $version"
echo "Tagged v$version. Publish it with: git push --follow-tags"
