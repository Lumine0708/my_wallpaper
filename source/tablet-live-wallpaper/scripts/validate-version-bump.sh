#!/usr/bin/env bash
set -euo pipefail

readonly version_file="version.properties"
readonly repository_version_file="$(git rev-parse --show-prefix)${version_file}"
readonly base_sha="${1:?base commit SHA is required}"
readonly head_ref="${2:?pull request head branch is required}"

die() {
  printf 'Version validation failed: %s\n' "$1" >&2
  exit 1
}

read_file_property() {
  local key="$1"
  local count
  local value

  count="$(grep -Ec "^${key}=" "$version_file" || true)"
  [ "$count" -eq 1 ] || die "$version_file must contain exactly one ${key} entry"
  value="$(sed -n "s/^${key}=//p" "$version_file")"
  [ -n "$value" ] || die "$key must not be empty"
  printf '%s\n' "$value"
}

read_git_property() {
  local key="$1"
  local value

  value="$(git show "${base_sha}:${repository_version_file}" | sed -n "s/^${key}=//p")"
  [ -n "$value" ] || die "base ${version_file} is missing ${key}"
  [ "$(printf '%s\n' "$value" | wc -l | tr -d ' ')" -eq 1 ] \
    || die "base ${version_file} contains duplicate ${key} entries"
  printf '%s\n' "$value"
}

readonly current_name="$(read_file_property versionName)"
readonly current_code="$(read_file_property versionCode)"

if git cat-file -e "${base_sha}:${repository_version_file}" 2>/dev/null; then
  readonly base_name="$(read_git_property versionName)"
  readonly base_code="$(read_git_property versionCode)"
else
  # Migration baseline: the latest semantic prerelease predates version.properties.
  readonly base_name="1.0.2"
  readonly base_code="1"
fi

readonly semver_pattern='^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$'
[[ "$base_name" =~ $semver_pattern ]] || die "base versionName is not SemVer: $base_name"
readonly base_major="${BASH_REMATCH[1]}"
readonly base_minor="${BASH_REMATCH[2]}"
readonly base_patch="${BASH_REMATCH[3]}"

[[ "$current_name" =~ $semver_pattern ]] || die "versionName is not SemVer: $current_name"
readonly current_major="${BASH_REMATCH[1]}"
readonly current_minor="${BASH_REMATCH[2]}"
readonly current_patch="${BASH_REMATCH[3]}"

[[ "$base_code" =~ ^[1-9][0-9]*$ ]] || die "base versionCode is not a positive integer: $base_code"
[[ "$current_code" =~ ^[1-9][0-9]*$ ]] || die "versionCode is not a positive integer: $current_code"
[ "$current_code" -eq $((base_code + 1)) ] \
  || die "versionCode must increase by exactly one: $base_code -> $current_code"

bump_type=""
if [ "$current_major" -eq "$base_major" ] \
  && [ "$current_minor" -eq "$base_minor" ] \
  && [ "$current_patch" -eq $((base_patch + 1)) ]; then
  bump_type="patch"
elif [ "$current_major" -eq "$base_major" ] \
  && [ "$current_minor" -eq $((base_minor + 1)) ] \
  && [ "$current_patch" -eq 0 ]; then
  bump_type="minor"
elif [ "$current_major" -eq $((base_major + 1)) ] \
  && [ "$current_minor" -eq 0 ] \
  && [ "$current_patch" -eq 0 ]; then
  bump_type="major"
else
  die "versionName must be one exact patch, minor, or major bump: $base_name -> $current_name"
fi

case "$head_ref" in
  fix/*|codex/fix/*)
    [ "$bump_type" = "patch" ] \
      || die "fix branches require a patch bump, found $bump_type"
    ;;
  feature/*|feat/*|codex/feature/*|codex/feat/*)
    [ "$bump_type" = "minor" ] \
      || die "feature branches require a minor bump, found $bump_type"
    ;;
esac

printf 'Version bump validated: %s (%s) -> %s (%s), type=%s, branch=%s\n' \
  "$base_name" "$base_code" "$current_name" "$current_code" "$bump_type" "$head_ref"
