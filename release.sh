#!/usr/bin/env bash
# Bump app versions, tag and publish a GitHub release; CI attaches the APK and desktop installers.
# Usage: ./release.sh [patch|minor|major|X.Y.Z]   (no argument: choose interactively)
set -euo pipefail
cd "$(dirname "$0")"

gradle=android/app/build.gradle.kts
tauri=desktop/src-tauri/tauri.conf.json
die() { echo "$*" >&2; exit 1; }

[ -z "$(git status --porcelain)" ] || die "Working tree is not clean"
gh auth status > /dev/null 2>&1 || die "GitHub CLI is missing or not logged in; run gh auth login"
git fetch -q origin
[ "$(git rev-list --count 'HEAD..@{u}')" = 0 ] || die "Branch is behind its upstream; pull first"

current=$(sed -n 's/^ *versionName = "\(.*\)"$/\1/p' "$gradle")
code=$(( $(sed -n 's/^ *versionCode = \([0-9]*\)$/\1/p' "$gradle") + 1 ))
IFS=. read -r major minor patch <<< "$current"
bumps=("$major.$minor.$((patch + 1))" "$major.$((minor + 1)).0" "$((major + 1)).0.0")
echo "Current version: $current (versionCode $((code - 1)))"
choice=${1:-}
if [ -z "$choice" ]; then
  echo "  1) patch -> ${bumps[0]}"
  echo "  2) minor -> ${bumps[1]}"
  echo "  3) major -> ${bumps[2]}"
  read -r -p "Choose 1-3, or type a version: " choice
fi
case "$choice" in
  1|patch) version=${bumps[0]} ;;
  2|minor) version=${bumps[1]} ;;
  3|major) version=${bumps[2]} ;;
  *) version=$choice ;;
esac
[[ $version =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || die "Invalid version: $version"
! git rev-parse -q --verify "refs/tags/v$version" > /dev/null || die "Tag v$version already exists"

read -r -p "Release $current -> $version (versionCode $((code - 1)) -> $code) on $(git branch --show-current)? [y/N] " answer
[[ $answer == [yY] ]] || die "Aborted"

perl -pi -e "s/^(\s*versionName = )\".*\"\$/\${1}\"$version\"/; s/^(\s*versionCode = )\d+\$/\${1}$code/" "$gradle"
perl -pi -e "s/^(\s*\"version\": )\".*\"/\${1}\"$version\"/" "$tauri"
git commit -q -m "chore(release): v$version" "$gradle" "$tauri"
git tag "v$version"
git push --atomic origin HEAD "v$version"
gh release create "v$version" --title "v$version" --generate-notes
