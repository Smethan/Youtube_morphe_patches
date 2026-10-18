#!/usr/bin/env bash
set -euo pipefail

# Build this fork without access to Morphe's GitHub Packages registry. Keep the
# three source tags in step with settings.gradle.kts and gradle/libs.versions.toml.
repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
source_root=${MORPHE_COMPOSITE_ROOT:-${TMPDIR:-/tmp}/morphe-public-sources}
maven_repo=${MORPHE_LOCAL_MAVEN:-$source_root/.maven}

clone_tag() {
    local name=$1
    local tag=$2
    local target=$source_root/$name
    if [[ -d "$target/.git" ]]; then
        local expected
        expected=$(git -C "$target" rev-parse "refs/tags/$tag^{commit}")
        [[ $(git -C "$target" rev-parse HEAD) == "$expected" ]] || {
            echo "Existing $target is not at $tag" >&2
            exit 1
        }
        return
    fi
    git clone --depth 1 --branch "$tag" "https://github.com/MorpheApp/$name.git" "$target"
}

mkdir -p "$source_root" "$maven_repo"
clone_tag morphe-patches-gradle-plugin v1.3.4
clone_tag morphe-patcher v1.15.1
clone_tag morphe-patches-library v1.9.0-dev.9

export MORPHE_COMPOSITE_ROOT=$source_root
# The upstream settings plugin constructs a fallback GitHub Packages repository
# even when local composite builds provide its artifacts.
export GITHUB_ACTOR=${GITHUB_ACTOR:-public-source-build}
export GITHUB_TOKEN=${GITHUB_TOKEN:-unused}

"$repo_root/gradlew" -p "$source_root/morphe-patches-gradle-plugin" \
    -Dmaven.repo.local="$maven_repo" publishToMavenLocal --no-daemon \
    -x signPluginMavenPublication \
    -x signPatchesSettingsPluginPluginMarkerMavenPublication

if [[ ${1:-} == --prepare-only ]]; then
    exit 0
fi

"$repo_root/gradlew" -p "$repo_root" -Dmaven.repo.local="$maven_repo" \
    :patches:buildAndroid --no-daemon "$@"
