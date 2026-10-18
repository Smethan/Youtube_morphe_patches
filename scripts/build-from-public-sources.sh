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

# The public plugin tag signs every Maven publication. A local composite build
# needs the plugin artifacts but has no release signing key. Restore the public
# source file after publication, including when Gradle exits with an error.
plugin_build_file=$source_root/morphe-patches-gradle-plugin/build.gradle.kts
plugin_build_backup=$(mktemp)
cp -p "$plugin_build_file" "$plugin_build_backup"
restore_plugin_build() {
    cp -p "$plugin_build_backup" "$plugin_build_file"
    rm -f "$plugin_build_backup"
}
trap restore_plugin_build EXIT
python3 - "$plugin_build_file" <<'PY'
from pathlib import Path
import sys

path = Path(sys.argv[1])
source = path.read_text()
plugin_line = "    signing\n"
signing_block = "signing {\n    useGpgCmd()\n\n    sign(publishing.publications)\n}\n"
if plugin_line in source and signing_block in source:
    path.write_text(source.replace(plugin_line, "", 1).replace(signing_block, "", 1))
elif plugin_line in source or signing_block in source:
    raise SystemExit("Unexpected upstream plugin signing configuration")
PY

export MORPHE_COMPOSITE_ROOT=$source_root
# The upstream settings plugin constructs a fallback GitHub Packages repository
# even when local composite builds provide its artifacts.
export GITHUB_ACTOR=${GITHUB_ACTOR:-public-source-build}
export GITHUB_TOKEN=${GITHUB_TOKEN:-unused}

"$repo_root/gradlew" -p "$source_root/morphe-patches-gradle-plugin" \
    -Dmaven.repo.local="$maven_repo" publishToMavenLocal --no-daemon
restore_plugin_build
trap - EXIT

if [[ ${1:-} == --prepare-only ]]; then
    exit 0
fi

"$repo_root/gradlew" -p "$repo_root" -Dmaven.repo.local="$maven_repo" \
    :patches:buildAndroid --no-daemon "$@"
