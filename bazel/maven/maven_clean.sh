#!/bin/bash

# Deletes orphaned files in //prebuilts/tools/common/m2
#
# A file is kept if it lives in (or below) an artifact directory referenced by
# //prebuilts/tools/common/m2:BUILD or by @maven (tools/base/bazel/maven/BUILD.maven).
# LICENSE/NOTICE files in an ancestor of a referenced directory (e.g.
# repository/androidx/NOTICE) are also kept, since LocalMavenRepositoryGenerator
# copies them into newly fetched artifacts.
#
# After deleting, //tools/base/bazel:maven_consistency_test is run to verify that
# BUILD.maven can still be regenerated offline from the remaining files. If it
# fails, all deleted files are restored.
#
# Note: some non-Bazel builds (e.g. androidx-studio-integration and the tools/
# Gradle build) also consume this repository and are not checked here.
#
# Usage: maven_clean.sh [--dry-run]

set -eu
set -o pipefail

dry_run=false
case "${1:-}" in
    "") ;;
    --dry-run) dry_run=true ;;
    *) echo "Usage: $0 [--dry-run]" >&2; exit 2 ;;
esac

top=$(cd "$(dirname "$0")/../../../.." && pwd -P)
m2=$top/prebuilts/tools/common/m2
repo=$m2/repository
BAZEL=$top/tools/base/bazel/bazel
workdir=$(mktemp -d)
trap 'rm -rf "$workdir"' EXIT

# Every source file directly referenced by a rule in the m2 package (except the
# catch-all :all filegroup) or in @maven, regardless of attribute name.
$BAZEL query 'kind("source file", deps(kind(rule, //prebuilts/tools/common/m2:*) except //prebuilts/tools/common/m2:all, 1))' \
    | { grep '^//prebuilts/tools/common/m2:repository/' || true; } \
    | sed "s|^//prebuilts/tools/common/m2:repository/|$repo/|" \
    > "$workdir/file_refs.txt"

$BAZEL query 'kind("source file", deps(@maven//..., 1))' \
    | { grep -E '^@+maven//:' || true; } \
    | sed -E "s|^@+maven//:|$repo/|" \
    >> "$workdir/file_refs.txt"

find "$repo" -type f ! -name .gitignore > "$workdir/all_files.txt"

# Guard against deleting (almost) everything if, e.g., the label format printed
# by bazel query changes and the queries above silently match nothing. Today
# nearly every file in the repository is referenced, so a low ratio means the
# queries are broken rather than that the repository needs cleaning.
num_refs=$(wc -l < "$workdir/file_refs.txt")
num_files=$(wc -l < "$workdir/all_files.txt")
min_ref_percent=40
if (( num_refs * 100 < num_files * min_ref_percent )); then
    echo "ERROR: only $num_refs referenced files found for $num_files files in the" \
         "repository (less than $min_ref_percent%); refusing to continue." >&2
    exit 1
fi

# Referenced directories, with a trailing slash so that ".../foo/1.0/" does
# not also match ".../foo/1.0.1/" or ".../foo/1.0-rc1/".
sed 's|/[^/]*$|/|' "$workdir/file_refs.txt" | sort -u \
    | { grep -xvF "$repo/" || true; } \
    > "$workdir/dir_refs.txt"

# All strict ancestors of referenced directories (for parent-level NOTICE files).
awk -v repo="$repo" '{
    d = $0; sub(/\/$/, "", d)
    while (1) { sub(/\/[^\/]*$/, "", d); if (length(d) <= length(repo)) break; print d "/" }
}' "$workdir/dir_refs.txt" | sort -u > "$workdir/ancestor_dirs.txt"

echo "Computing orphaned maven artifacts ..."
# BOMs pulled in via <scope>import</scope> are needed by the resolver but are
# not emitted into BUILD.maven, so keep anything that looks like a BOM.
{ grep -v -- '-bom/' "$workdir/all_files.txt" || true; } \
    | { grep -vF -f "$workdir/dir_refs.txt" || true; } \
    | awk -v anc="$workdir/ancestor_dirs.txt" '
        BEGIN { while ((getline l < anc) > 0) keep[l] = 1 }
        {
            n = split($0, p, "/"); base = p[n]
            dir = substr($0, 1, length($0) - length(base))
            if (base ~ /^(LICENSE|LICENSE\.txt|NOTICE|NOTICE\.txt)$/ && (dir in keep)) next
            print
        }' \
    > "$workdir/unused_files.txt"

echo "found $(wc -l < "$workdir/unused_files.txt") unused files"
if $dry_run; then
    sed "s|^$repo/||" "$workdir/unused_files.txt"
    exit 0
fi
if [[ ! -s "$workdir/unused_files.txt" ]]; then
    echo "Nothing to do."
    exit 0
fi

echo "Size before: "
du -hs "$m2"

# Move (rather than delete) files into a backup so they can be restored if the
# consistency test fails.
backup="$workdir/backup"
while read -r file; do
    rel=${file#"$repo/"}
    mkdir -p "$backup/$(dirname "$rel")"
    mv "$file" "$backup/$rel"
done < "$workdir/unused_files.txt"
find "$repo" -empty -type d -delete

echo "Running maven_consistency_test ..."
if ! $BAZEL test //tools/base/bazel:maven_consistency_test --test_output=errors; then
    # A failure here means this script considered a file unused that the maven
    # resolver still needs, i.e. a bug in this script. Leave the repository as
    # it was and ask for a bug report rather than a partial cleanup.
    while read -r file; do
        rel=${file#"$repo/"}
        mkdir -p "$(dirname "$file")"
        mv "$backup/$rel" "$file"
    done < "$workdir/unused_files.txt"
    report=$(mktemp "${TMPDIR:-/tmp}/maven_clean_failure.XXXXXX")
    sed "s|^$repo/||" "$workdir/unused_files.txt" > "$report"
    cat >&2 <<EOF
ERROR: maven_consistency_test failed after removing the files listed in
  $report
All files have been restored; prebuilts/tools/common/m2 is unchanged.

This means maven_clean.sh removed a file that is still needed. Please file a
bug in component 1507221 (http://b/issues/new?component=1507221) and attach
the file list above and the test log
  $top/bazel-testlogs/tools/base/bazel/maven_consistency_test/test.log
EOF
    exit 1
fi

echo "Size after: "
du -hs "$m2"
echo "Done."
