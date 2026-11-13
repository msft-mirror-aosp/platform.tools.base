#!/bin/bash

# Deletes orphaned files in //prebuilts/tools/common/m2
#
# A file is kept if it lives in (or below) an artifact directory referenced by
# //prebuilts/tools/common/m2:BUILD or by @maven (tools/base/bazel/maven/BUILD.maven),
# or directly referenced by non-Bazel project configs such as lume.yaml and
# studio-lint-plugin/build.gradle.kts.
# LICENSE/NOTICE/OWNERS files in an ancestor of a referenced directory (e.g.
# repository/androidx/NOTICE, repository/com/android/tools/r8/OWNERS) are also
# kept, since LocalMavenRepositoryGenerator copies NOTICE/LICENSE files into
# newly fetched artifacts and OWNERS controls directory review ownership.
#
# With --prune-bzl, unreferenced and shadowed coordinates in
# tools/base/bazel/maven/artifacts.bzl and tools/base/bazel/maven/data.bzl are
# first pruned (respecting Bazel @maven reachability, //prebuilts/tools/common/m2:BUILD,
# Bzlmod modules, Gradle builds under tools/, Lume, Studio lint plugin, and ASwB
# integration test projects), BUILD.maven is regenerated, and then orphaned
# files are removed.
#
# After deleting, //tools/base/bazel:maven_consistency_test is run to verify that
# BUILD.maven can still be regenerated offline from the remaining files. If it
# fails, all deleted files (and any .bzl/.maven edits from --prune-bzl) are restored.
#
# Note: some external non-Bazel builds (e.g. androidx-studio-integration) also
# consume this repository and are not checked here.
#
# Usage: maven_clean.sh [--dry-run] [--prune-bzl]

set -eu
set -o pipefail

usage() {
    echo "Usage: $0 [--dry-run] [--prune-bzl]" >&2
}

dry_run=false
prune_bzl=false
while (( $# > 0 )); do
    case "$1" in
        --dry-run) dry_run=true ;;
        --prune-bzl) prune_bzl=true ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            usage
            exit 2
            ;;
    esac
    shift
done

top=$(cd "$(dirname "$0")/../../../.." && pwd -P)
m2=$top/prebuilts/tools/common/m2
repo=$m2/repository
maven_dir=$top/tools/base/bazel/maven
BAZEL=$top/tools/base/bazel/bazel
workdir=$(mktemp -d)
trap 'rm -rf "$workdir"' EXIT

bzl_backup=""
restore_bzl_backup() {
    if [[ -n "$bzl_backup" && -d "$bzl_backup" ]]; then
        cp -p "$bzl_backup/artifacts.bzl" "$maven_dir/artifacts.bzl"
        cp -p "$bzl_backup/data.bzl" "$maven_dir/data.bzl"
        cp -p "$bzl_backup/BUILD.maven" "$maven_dir/BUILD.maven"
    fi
}

prune_bzl_files() {
    command -v python3 >/dev/null || {
        echo "ERROR: python3 is required for --prune-bzl" >&2
        exit 1
    }

    echo "Querying reachable @maven targets across workspace ..."
    $BAZEL query 'kind(rule, @maven//...)' > "$workdir/all_maven_rules.txt"

    local rc=0
    $BAZEL query --keep_going \
        'kind(rule, @maven//...) except deps(//... except //prebuilts/tools/common/m2:*)' \
        > "$workdir/unreachable_maven_rules.txt" || rc=$?
    if (( rc != 0 && rc != 3 )); then
        echo "ERROR: bazel query for unreachable @maven rules failed with status $rc." >&2
        exit "$rc"
    fi

    local total_rules unreachable_rules reachable_rules
    total_rules=$(wc -l < "$workdir/all_maven_rules.txt")
    unreachable_rules=$(wc -l < "$workdir/unreachable_maven_rules.txt")
    reachable_rules=$(( total_rules - unreachable_rules ))
    if (( total_rules == 0 || reachable_rules * 100 < total_rules * 50 )); then
        echo "ERROR: only $reachable_rules/$total_rules @maven rules appeared reachable (< 50%); refusing --prune-bzl." >&2
        exit 1
    fi

    $BAZEL query 'kind("source file", deps(kind(rule, //prebuilts/tools/common/m2:*) except //prebuilts/tools/common/m2:all, 1))' \
        | { grep '^//prebuilts/tools/common/m2:repository/' || true; } \
        > "$workdir/m2_file_refs.txt"

    if ! $dry_run; then
        bzl_backup="$workdir/bzl_backup"
        mkdir -p "$bzl_backup"
        cp -p "$maven_dir/artifacts.bzl" "$bzl_backup/artifacts.bzl"
        cp -p "$maven_dir/data.bzl" "$bzl_backup/data.bzl"
        cp -p "$maven_dir/BUILD.maven" "$bzl_backup/BUILD.maven"
    fi

    python3 - "$top" "$maven_dir" "$repo" \
        "$workdir/unreachable_maven_rules.txt" \
        "$workdir/m2_file_refs.txt" \
        "$dry_run" \
        "$workdir/needed_rules.txt" \
        "$workdir/pruned_seed_closures.tsv" \
        "$workdir/pruned_coords.tsv" \
        "$workdir/shadowed_updates.tsv" <<'PY'
import glob
import os
import re
import sys

(
    top,
    maven_dir,
    repo,
    unreach_path,
    m2_refs_path,
    dry_run_str,
    needed_rules_path,
    pruned_closures_path,
    pruned_coords_path,
    shadowed_path,
) = sys.argv[1:11]
dry_run = (dry_run_str == "true")

unreach_rules = set()
with open(unreach_path, encoding="utf-8") as f:
    for line in f:
        m = re.match(r"^@+maven//:(.+)$", line.strip())
        if m:
            unreach_rules.add(m.group(1))

rules = {}
with open(os.path.join(maven_dir, "BUILD.maven"), encoding="utf-8") as f:
    build_maven_txt = f.read()

for m in re.finditer(r'(maven_artifact|maven_import)\(\s*name\s*=\s*"([^"]+)"(.*?)\n\)', build_maven_txt, re.S):
    kind, name, body = m.group(1), m.group(2), m.group(3)
    rp = re.search(r'repo_path\s*=\s*"([^"]+)"', body)
    pa = re.search(r'parent\s*=\s*"([^"]+)"', body)
    sj = re.search(r'srcjar\s*=\s*"([^"]+)"', body)
    edges = set()
    if pa:
        edges.add(pa.group(1))
    for attr in ("deps", "exports", "original_deps"):
        am = re.search(rf'\b{attr}\s*=\s*\[(.*?)\]', body, re.S)
        if am:
            edges.update(re.findall(r'"([^"]+)"', am.group(1)))
    rules[name] = {
        "kind": kind,
        "repo_path": rp.group(1) if rp else None,
        "srcjar_rp": os.path.dirname(sj.group(1)) if sj else None,
        "edges": edges,
    }

rp_to_rules = {}
for rname, info in rules.items():
    if info["repo_path"]:
        rp_to_rules.setdefault(info["repo_path"], set()).add(rname)

def parse_coord(coord):
    parts = coord.split(":")
    if len(parts) == 3:
        return parts[0], parts[1], None, None, parts[2]
    if len(parts) == 4:
        return parts[0], parts[1], parts[2], None, parts[3]
    if len(parts) == 5:
        return parts[0], parts[1], parts[2], parts[3], parts[4]
    raise ValueError(f"Unsupported Maven coordinate format: {coord}")

def artifact_rule_name(coord):
    g, a, _, _, v = parse_coord(coord)
    return f"{g}.{a}_{v}"

def import_rule_name(coord):
    g, a, _, clf, _ = parse_coord(coord)
    return f"{g}.{a}.{clf}" if clf else f"{g}.{a}"

def make_rp(g, a, v):
    return f"{g.replace('.', '/')}/{a}/{v}"

def coord_repo_path(coord):
    g, a, _, _, v = parse_coord(coord)
    return make_rp(g, a, v)

def replace_coord_version(coord, new_ver):
    parts = coord.split(":")
    parts[-1] = new_ver
    return ":".join(parts)

def closure(seed_rule_names):
    visited = {r for r in seed_rule_names if r in rules}
    stack = list(visited)
    while stack:
        cur = stack.pop()
        for nxt in rules[cur]["edges"]:
            if nxt in rules and nxt not in visited:
                visited.add(nxt)
                stack.append(nxt)
    return visited

needed_rules = set(rules.keys()) - unreach_rules
needed_rps = set()
unversioned_ga = set()

# 1. //prebuilts/tools/common/m2:* file references
with open(m2_refs_path, encoding="utf-8") as f:
    for line in f:
        m = re.match(r"^//prebuilts/tools/common/m2:repository/(.+)$", line.strip())
        if m and "/" in m.group(1):
            needed_rps.add(os.path.dirname(m.group(1)))

# 2. Direct path references in bzlmod/*.MODULE.bazel, lume.yaml, studio-lint-plugin
path_consumer_files = sorted(glob.glob(os.path.join(top, "tools/base/bazel/bzlmod/*.MODULE.bazel"))) + [
    os.path.join(top, "tools/vendor/google/lume/.idea/lume.yaml"),
    os.path.join(top, "tools/vendor/google/lume-release/.idea/lume.yaml"),
    os.path.join(top, "tools/vendor/google/studio-lint-plugin/build.gradle.kts"),
]
for cf in path_consumer_files:
    if not os.path.exists(cf):
        continue
    with open(cf, encoding="utf-8", errors="ignore") as f:
        txt = f.read()
    for m in re.finditer(r"repository/([A-Za-z0-9_./-]+)", txt):
        rel = m.group(1)
        if "/" in rel and os.path.dirname(rel):
            needed_rps.add(os.path.dirname(rel))

# 3. tools/buildSrc/base/libs.versions.toml
toml_path = os.path.join(top, "tools/buildSrc/base/libs.versions.toml")
if os.path.exists(toml_path):
    versions = {}
    sec = None
    with open(toml_path, encoding="utf-8") as f:
        for line in f:
            s = line.strip()
            if s.startswith("[") and s.endswith("]"):
                sec = s[1:-1]
                continue
            if sec == "versions" and "=" in s and not s.startswith("#"):
                k, v = s.split("=", 1)
                m = re.search(r'"([^"]+)"', v)
                if m:
                    versions[k.strip()] = m.group(1)
            elif sec in ("libraries", "plugins") and "=" in s and not s.startswith("#"):
                m_mod = re.search(r'(?:module|id)\s*=\s*"([^"]+)"', s)
                m_grp = re.search(r'group\s*=\s*"([^"]+)"', s)
                m_nam = re.search(r'name\s*=\s*"([^"]+)"', s)
                m_vref = re.search(r'version\.ref\s*=\s*"([^"]+)"', s)
                m_ver = re.search(r'version\s*=\s*"([^"]+)"', s)
                ga = m_mod.group(1) if m_mod else (f"{m_grp.group(1)}:{m_nam.group(1)}" if (m_grp and m_nam) else None)
                ver = versions.get(m_vref.group(1)) if m_vref else (m_ver.group(1) if m_ver else None)
                if ga and ver:
                    if sec == "plugins" and ":" not in ga:
                        ga = f"{ga}:{ga}.gradle.plugin"
                    g, a = ga.split(":", 1)
                    needed_rps.add(make_rp(g, a, ver))

# 4. Explicit coordinates in Gradle build scripts under tools/
for base_dir in ("tools/buildSrc", "tools/base", "tools/data-binding", "tools/vendor/google/studio-lint-plugin"):
    full_base = os.path.join(top, base_dir)
    if not os.path.isdir(full_base):
        continue
    for dirpath, dirnames, filenames in os.walk(full_base):
        dirnames[:] = [d for d in dirnames if d not in ("build", ".git", "testData")]
        for fn in filenames:
            if not fn.endswith((".gradle", ".gradle.kts")):
                continue
            with open(os.path.join(dirpath, fn), encoding="utf-8", errors="ignore") as f:
                txt = f.read()
            for rx in (r'"([a-zA-Z0-9_.-]+:[a-zA-Z0-9_.-]+:[0-9][a-zA-Z0-9_.-]*)"',
                       r"'([a-zA-Z0-9_.-]+:[a-zA-Z0-9_.-]+:[0-9][a-zA-Z0-9_.-]*)'"):
                for m in re.finditer(rx, txt):
                    parts = m.group(1).split(":")
                    if len(parts) == 3:
                        needed_rps.add(make_rp(parts[0], parts[1], parts[2]))

# 5. ASwB .bazelignore-excluded test_deps projects (including BOM-resolved unversioned deps)
aswb_boms = []
for mf in sorted(glob.glob(os.path.join(top, "tools/adt/idea/aswb/testing/test_deps/projects/MODULE.bazel*"))):
    with open(mf, encoding="utf-8") as f:
        txt = f.read()
    for m in re.finditer(r'"([a-zA-Z0-9_.-]+:[a-zA-Z0-9_.-]+(?::[a-zA-Z0-9_.-]+)?)"', txt):
        parts = m.group(1).split(":")
        if len(parts) == 3:
            rp = make_rp(parts[0], parts[1], parts[2])
            needed_rps.add(rp)
            if "bom" in parts[1]:
                aswb_boms.append((parts[1], parts[2], rp))
        elif len(parts) == 2:
            unversioned_ga.add((parts[0], parts[1]))

for art, ver, rp in aswb_boms:
    bom_pom = os.path.join(repo, rp, f"{art}-{ver}.pom")
    if os.path.exists(bom_pom):
        with open(bom_pom, encoding="utf-8", errors="ignore") as f:
            btxt = f.read()
        for dm in re.finditer(r"<dependency>\s*<groupId>([^<]+)</groupId>\s*<artifactId>([^<]+)</artifactId>\s*<version>([^<]+)</version>", btxt, re.S):
            g, a, v = dm.group(1).strip(), dm.group(2).strip(), dm.group(3).strip()
            if (g, a) in unversioned_ga:
                needed_rps.add(make_rp(g, a, v))

_mod_redirect_cache = {}

def module_redirect_rps(rp):
    cached = _mod_redirect_cache.get(rp)
    if cached is not None:
        return cached
    out = set()
    d = os.path.join(repo, rp)
    if os.path.isdir(d):
        for fn in os.listdir(d):
            if not fn.endswith(".module"):
                continue
            with open(os.path.join(d, fn), encoding="utf-8", errors="ignore") as mf:
                mtxt = mf.read()
            for url in re.findall(r'"url"\s*:\s*"(\.\./[^"]+)"', mtxt):
                rel_dir = os.path.dirname(url)
                if rel_dir in ("", ".", "..") or rel_dir.endswith("/.."):
                    continue
                cand_rp = os.path.normpath(os.path.join(rp, rel_dir))
                if cand_rp != "." and not cand_rp.startswith("..") and os.path.isdir(os.path.join(repo, cand_rp)):
                    out.add(cand_rp)
    _mod_redirect_cache[rp] = out
    return out

# Propagate needed_rps into BUILD.maven transitive closure and Gradle .module redirects.
while True:
    prev_rules = len(needed_rules)
    prev_rps = len(needed_rps)
    for rp in list(needed_rps):
        if rp in rp_to_rules:
            needed_rules.update(rp_to_rules[rp])
        needed_rps.update(module_redirect_rps(rp))
    needed_rules = closure(needed_rules)
    for r in needed_rules:
        info = rules[r]
        if info["repo_path"]:
            needed_rps.add(info["repo_path"])
        if info["srcjar_rp"]:
            needed_rps.add(info["srcjar_rp"])
    if len(needed_rules) == prev_rules and len(needed_rps) == prev_rps:
        break

# Parse loaded .bzl string constants (e.g. R8_VERSION, KOTLIN_VERSION_AGP_COMPILES_AGAINST)
# and artifacts.bzl / data.bzl entries.
def load_bzl_string_consts(lines):
    bzl_text = "".join(lines)
    consts = {}
    for m in re.finditer(r'load\(\s*"//([^"]+)"\s*,([^)]+)\)', bzl_text, re.S):
        rel_path = m.group(1).replace(":", "/")
        bzl_path = os.path.join(top, rel_path)
        if not os.path.isfile(bzl_path):
            continue
        with open(bzl_path, encoding="utf-8") as bf:
            btxt = bf.read()
        for sym in re.findall(r'"([^"]+)"', m.group(2)):
            sm = re.search(rf'^{re.escape(sym)}\s*=\s*"([^"]+)"', btxt, re.M)
            if sm:
                consts[sym] = sm.group(1)
    return consts

artifacts_path = os.path.join(maven_dir, "artifacts.bzl")
with open(artifacts_path, encoding="utf-8") as f:
    artifacts_lines = f.readlines()

data_path = os.path.join(maven_dir, "data.bzl")
with open(data_path, encoding="utf-8") as f:
    data_lines = f.readlines()

def extract_bzl_coords(lines, section_names, consts):
    entries = []
    cur_section = None
    for idx, line in enumerate(lines):
        stripped = line.strip()
        for sec in section_names:
            if stripped.startswith(f"{sec} = ["):
                cur_section = sec
                break
        if cur_section and stripped == "]":
            cur_section = None
            continue
        if not cur_section or stripped.startswith("#"):
            continue
        cm = re.match(r'^(\s*)"([^"]+:)"\s*\+\s*([A-Za-z_][A-Za-z0-9_]*)\s*,(.*)$', line)
        if cm and cm.group(3) in consts:
            entries.append((idx, cur_section, cm.group(2) + consts[cm.group(3)], True))
            continue
        m = re.match(r'^(\s*)"([^"]+:[^"]+)"\s*,(.*)$', line)
        if m:
            entries.append((idx, cur_section, m.group(2), False))
    return entries

art_consts = load_bzl_string_consts(artifacts_lines)
data_consts = load_bzl_string_consts(data_lines)
art_entries = extract_bzl_coords(artifacts_lines, ("ARTIFACTS",), art_consts)
data_entries = extract_bzl_coords(data_lines, ("_CLASS_JARS", "_SOURCE_JARS"), data_consts)

def seed_root_rules(coord, is_artifact):
    ar = artifact_rule_name(coord)
    rp = coord_repo_path(coord)
    roots = {ar} if ar in rules else set()
    if is_artifact:
        ir = import_rule_name(coord)
        if ir in rules and rules[ir]["repo_path"] == rp:
            roots.add(ir)
    return roots

art_coords = [c for _, _, c, _ in art_entries]
cj_coords = [c for _, sec, c, _ in data_entries if sec == "_CLASS_JARS"]
sj_coords = [c for _, sec, c, _ in data_entries if sec == "_SOURCE_JARS"]

kept_art = set()
shadowed_updates = {}  # old_coord -> new_coord
for _, _, c, is_const in art_entries:
    if is_const:
        kept_art.add(c)
        continue
    rp = coord_repo_path(c)
    sr = seed_root_rules(c, True)
    ir = import_rule_name(c)
    if rp in needed_rps or (sr & needed_rules):
        kept_art.add(c)
    elif ir in needed_rules and rules[ir]["repo_path"]:
        win_ver = os.path.basename(rules[ir]["repo_path"])
        new_c = replace_coord_version(c, win_ver)
        shadowed_updates[c] = new_c
        kept_art.add(c)  # keep (aligned to winner version)

kept_cj = set()
for _, sec, c, is_const in data_entries:
    if sec != "_CLASS_JARS":
        continue
    if is_const:
        kept_cj.add(c)
        continue
    rp = coord_repo_path(c)
    sr = seed_root_rules(c, False)
    if rp in needed_rps or (sr & needed_rules):
        kept_cj.add(c)

# Set-cover rescue pass for KMP root modules and umbrella artifacts whose child modules are needed.
covered = closure(
    set().union(
        *(seed_root_rules(shadowed_updates.get(c, c), True) for c in kept_art),
        *(seed_root_rules(c, False) for c in kept_cj),
    )
)
uncovered = needed_rules - covered
if uncovered:
    for c in art_coords:
        if c not in kept_art and (closure(seed_root_rules(c, True)) & uncovered):
            kept_art.add(c)
    for c in cj_coords:
        if c not in kept_cj and (closure(seed_root_rules(c, False)) & uncovered):
            kept_cj.add(c)

# Recompute covered repo_paths after rescue for deciding _SOURCE_JARS retention.
final_covered = closure(
    set().union(
        *(seed_root_rules(shadowed_updates.get(c, c), True) for c in kept_art),
        *(seed_root_rules(c, False) for c in kept_cj),
    )
)
final_needed_rps = set(needed_rps)
for r in final_covered:
    info = rules[r]
    if info["repo_path"]:
        final_needed_rps.add(info["repo_path"])
    if info["srcjar_rp"]:
        final_needed_rps.add(info["srcjar_rp"])
frontier_rps = set(final_needed_rps)
while frontier_rps:
    next_rps = set()
    for rp in frontier_rps:
        for nrp in module_redirect_rps(rp):
            if nrp not in final_needed_rps:
                final_needed_rps.add(nrp)
                next_rps.add(nrp)
    frontier_rps = next_rps

kept_sj = {c for c in sj_coords if coord_repo_path(c) in final_needed_rps}

pruned_art = [c for c in art_coords if c not in kept_art]
pruned_cj = [c for c in cj_coords if c not in kept_cj]
pruned_sj = [c for c in sj_coords if c not in kept_sj]

print(
    f"Prune summary: artifacts.bzl remove={len(pruned_art)} align_shadowed={len(shadowed_updates)}, "
    f"data.bzl _CLASS_JARS remove={len(pruned_cj)}, _SOURCE_JARS remove={len(pruned_sj)}"
)
for c in pruned_art:
    print(f"  [REMOVE ARTIFACTS] {c}")
for old_c, new_c in sorted(shadowed_updates.items()):
    print(f"  [ALIGN SHADOWED ARTIFACTS] {old_c} -> {new_c}")
for c in pruned_cj:
    print(f"  [REMOVE DATA _CLASS_JARS] {c}")
for c in pruned_sj:
    print(f"  [REMOVE DATA _SOURCE_JARS] {c}")

if dry_run:
    sys.exit(0)

with open(needed_rules_path, "w", encoding="utf-8") as f:
    for r in sorted(needed_rules):
        f.write(r + "\n")

def closure_rps(seed_rules):
    rps = set()
    for r in closure(seed_rules):
        if rules[r]["repo_path"]:
            rps.add(rules[r]["repo_path"])
        if rules[r]["srcjar_rp"]:
            rps.add(rules[r]["srcjar_rp"])
    return sorted(rps)

with open(pruned_closures_path, "w", encoding="utf-8") as f:
    for c in pruned_art:
        sr = seed_root_rules(c, True)
        cov_needed = sorted(closure(sr) & needed_rules)
        cov_rps = closure_rps(sr)
        f.write(f"artifacts.bzl\t{c}\t{','.join(cov_needed)}\t{','.join(cov_rps)}\n")
    for c in pruned_cj:
        sr = seed_root_rules(c, False)
        cov_needed = sorted(closure(sr) & needed_rules)
        cov_rps = closure_rps(sr)
        f.write(f"data.bzl\t{c}\t{','.join(cov_needed)}\t{','.join(cov_rps)}\n")

with open(pruned_coords_path, "w", encoding="utf-8") as f:
    for c in pruned_art:
        f.write(f"artifacts.bzl\t{c}\n")
    for c in pruned_cj:
        f.write(f"data.bzl:_CLASS_JARS\t{c}\n")
    for c in pruned_sj:
        f.write(f"data.bzl:_SOURCE_JARS\t{c}\n")

with open(shadowed_path, "w", encoding="utf-8") as f:
    for old_c, new_c in sorted(shadowed_updates.items()):
        f.write(f"{old_c}\t{new_c}\n")

# Apply updates line-by-line preserving comments and formatting.
art_remove_idxs = {idx for idx, _, c, is_const in art_entries if not is_const and c not in kept_art}
art_align_idxs = {idx: (c, shadowed_updates[c]) for idx, _, c, is_const in art_entries if not is_const and c in shadowed_updates}
new_art_lines = []
for idx, line in enumerate(artifacts_lines):
    if idx in art_remove_idxs:
        continue
    if idx in art_align_idxs:
        old_c, new_c = art_align_idxs[idx]
        line = line.replace(f'"{old_c}"', f'"{new_c}"', 1)
    new_art_lines.append(line)

with open(artifacts_path, "w", encoding="utf-8") as f:
    f.writelines(new_art_lines)

data_remove_idxs = {
    idx
    for idx, sec, c, is_const in data_entries
    if not is_const and ((sec == "_CLASS_JARS" and c not in kept_cj) or (sec == "_SOURCE_JARS" and c not in kept_sj))
}
new_data_lines = [line for idx, line in enumerate(data_lines) if idx not in data_remove_idxs]
with open(data_path, "w", encoding="utf-8") as f:
    f.writelines(new_data_lines)
PY

    if $dry_run; then
        return 0
    fi

    local buildifier="$top/prebuilts/tools/linux-x86/bazel/buildifier"
    if [[ -x "$buildifier" ]]; then
        "$buildifier" "$maven_dir/artifacts.bzl" "$maven_dir/data.bzl"
    fi

    echo "Regenerating tools/base/bazel/maven/BUILD.maven ..."
    if ! $BAZEL run //tools/base/bazel:local_maven_repository_generator; then
        echo "ERROR: local_maven_repository_generator failed after pruning; restoring .bzl and BUILD.maven." >&2
        restore_bzl_backup
        exit 1
    fi

    # Safeguard 1: Verify no needed @maven rules disappeared or lost edges / shifted
    # repo_path due to merged exclusion edges (b/201683107), and automatically
    # rescue pruned seeds (plus matching _SOURCE_JARS) if so.
    local rescue_round rescue_rc
    for rescue_round in 1 2 3 4; do
        rescue_rc=0
        python3 - "$maven_dir" "$bzl_backup" \
            "$workdir/needed_rules.txt" \
            "$workdir/pruned_seed_closures.tsv" \
            "$workdir/pruned_coords.tsv" \
            "$workdir/shadowed_updates.tsv" \
            "$rescue_round" <<'PY' || rescue_rc=$?
import os
import re
import sys

(
    maven_dir,
    bzl_backup,
    needed_path,
    closures_path,
    pruned_coords_path,
    shadowed_path,
    round_str,
) = sys.argv[1:8]
rescue_round = int(round_str)

def parse_build_maven(txt):
    parsed = {}
    for m in re.finditer(r'(maven_artifact|maven_import)\(\s*name\s*=\s*"([^"]+)"(.*?)\n\)', txt, re.S):
        kind, name, body = m.group(1), m.group(2), m.group(3)
        rp = re.search(r'repo_path\s*=\s*"([^"]+)"', body)
        pa = re.search(r'parent\s*=\s*"([^"]+)"', body)
        edges = set()
        if pa:
            edges.add(pa.group(1))
        for attr in ("deps", "exports", "original_deps"):
            am = re.search(rf'\b{attr}\s*=\s*\[(.*?)\]', body, re.S)
            if am:
                edges.update(re.findall(r'"([^"]+)"', am.group(1)))
        parsed[name] = {
            "kind": kind,
            "repo_path": rp.group(1) if rp else None,
            "edges": edges,
        }
    return parsed

def parse_coord(coord):
    parts = coord.split(":")
    if len(parts) == 3:
        return parts[0], parts[1], None, None, parts[2]
    if len(parts) == 4:
        return parts[0], parts[1], parts[2], None, parts[3]
    if len(parts) == 5:
        return parts[0], parts[1], parts[2], parts[3], parts[4]
    raise ValueError(f"Unsupported Maven coordinate format: {coord}")

def coord_repo_path(coord):
    g, a, _, _, v = parse_coord(coord)
    return f"{g.replace('.', '/')}/{a}/{v}"

with open(needed_path, encoding="utf-8") as f:
    needed_rules = {line.strip() for line in f if line.strip()}

with open(os.path.join(bzl_backup, "BUILD.maven"), encoding="utf-8") as f:
    orig_rules = parse_build_maven(f.read())

with open(os.path.join(maven_dir, "BUILD.maven"), encoding="utf-8") as f:
    regen_rules = parse_build_maven(f.read())

missing = needed_rules - set(regen_rules.keys())
degraded = set()
for r in needed_rules & set(regen_rules.keys()):
    if r not in orig_rules:
        continue
    if regen_rules[r]["repo_path"] != orig_rules[r]["repo_path"] or (orig_rules[r]["edges"] - regen_rules[r]["edges"]):
        degraded.add(r)

broken = missing | degraded
if not broken:
    sys.exit(0)

if rescue_round >= 4:
    print(
        f"ERROR: {len(broken)} needed @maven rule(s) still missing or degraded after rescue "
        f"(missing={len(missing)}: {sorted(missing)[:5]}, degraded={len(degraded)}: {sorted(degraded)[:5]})",
        file=sys.stderr,
    )
    sys.exit(1)

with open(pruned_coords_path, encoding="utf-8") as f:
    pruned_pairs = [tuple(line.rstrip("\n").split("\t", 1)) for line in f if line.strip()]
still_pruned_art = {c for kind, c in pruned_pairs if kind == "artifacts.bzl"}
still_pruned_cj = {c for kind, c in pruned_pairs if kind == "data.bzl:_CLASS_JARS"}
still_pruned_sj = {c for kind, c in pruned_pairs if kind == "data.bzl:_SOURCE_JARS"}

candidates = []
with open(closures_path, encoding="utf-8") as f:
    for line in f:
        parts = line.rstrip("\n").split("\t")
        if len(parts) < 3:
            continue
        bzl_file, coord, cov_str = parts[:3]
        rps_str = parts[3] if len(parts) >= 4 else ""
        if bzl_file == "artifacts.bzl" and coord not in still_pruned_art:
            continue
        if bzl_file == "data.bzl" and coord not in still_pruned_cj:
            continue
        cov = {x for x in cov_str.split(",") if x}
        rps = {x for x in rps_str.split(",") if x}
        if cov & broken:
            candidates.append((bzl_file, coord, cov, rps))

to_rescue = []
if rescue_round == 1:
    # Prefer non-.gradle.plugin:pom: seeds first; fall back to plugin markers only
    # for broken rules not covered by any non-plugin seed.
    non_plugin = [(bf, c, cov, rps) for bf, c, cov, rps in candidates if ".gradle.plugin:pom:" not in c]
    covered_non_plugin = set().union(*(cov & broken for _, _, cov, _ in non_plugin)) if non_plugin else set()
    only_plugin_broken = broken - covered_non_plugin
    to_rescue.extend((bf, c, rps) for bf, c, _, rps in non_plugin)
    if only_plugin_broken:
        to_rescue.extend(
            (bf, c, rps)
            for bf, c, cov, rps in candidates
            if ".gradle.plugin:pom:" in c and (cov & only_plugin_broken)
        )
else:
    to_rescue.extend((bf, c, rps) for bf, c, _, rps in candidates)

if not to_rescue:
    print(
        f"ERROR: {len(broken)} needed @maven rule(s) missing or degraded in regenerated BUILD.maven and no "
        f"pruned seed covers them (missing={sorted(missing)[:5]}, degraded={sorted(degraded)[:5]})",
        file=sys.stderr,
    )
    sys.exit(1)

print(
    f"Rescuing {len(to_rescue)} seed(s) covering {len(broken)} broken merged-exclusion rule(s) "
    f"(missing={len(missing)}, degraded={len(degraded)}):"
)
rescued_rps = set()
for bf, coord, rps in to_rescue:
    print(f"  [RESCUE MERGED-EXCLUSION SEED] {bf}: {coord}")
    rescued_rps.update(rps)
    if bf == "artifacts.bzl":
        still_pruned_art.discard(coord)
    elif bf == "data.bzl":
        still_pruned_cj.discard(coord)

for sj_c in sorted(still_pruned_sj):
    if coord_repo_path(sj_c) in rescued_rps:
        print(f"  [RESCUE SOURCE JAR] data.bzl:_SOURCE_JARS: {sj_c}")
        still_pruned_sj.discard(sj_c)

shadowed_updates = {}
with open(shadowed_path, encoding="utf-8") as f:
    for line in f:
        if line.strip():
            old_c, new_c = line.rstrip("\n").split("\t", 1)
            shadowed_updates[old_c] = new_c

with open(pruned_coords_path, "w", encoding="utf-8") as f:
    for c in sorted(still_pruned_art):
        f.write(f"artifacts.bzl\t{c}\n")
    for c in sorted(still_pruned_cj):
        f.write(f"data.bzl:_CLASS_JARS\t{c}\n")
    for c in sorted(still_pruned_sj):
        f.write(f"data.bzl:_SOURCE_JARS\t{c}\n")

def rewrite_bzl(src_path, dst_path, remove_by_section, align_map):
    out = []
    cur_section = None
    with open(src_path, encoding="utf-8") as f:
        for line in f:
            stripped = line.strip()
            for sec in remove_by_section:
                if stripped.startswith(f"{sec} = ["):
                    cur_section = sec
                    break
            if cur_section and stripped == "]":
                cur_section = None
                out.append(line)
                continue
            if cur_section and not stripped.startswith("#") and "+" not in line:
                m = re.match(r'^(\s*)"([^"]+:[^"]+)"\s*,(.*)$', line)
                if m:
                    coord = m.group(2)
                    if coord in remove_by_section[cur_section]:
                        continue
                    if coord in align_map:
                        line = line.replace(f'"{coord}"', f'"{align_map[coord]}"', 1)
            out.append(line)
    with open(dst_path, "w", encoding="utf-8") as f:
        f.writelines(out)

rewrite_bzl(
    os.path.join(bzl_backup, "artifacts.bzl"),
    os.path.join(maven_dir, "artifacts.bzl"),
    {"ARTIFACTS": still_pruned_art},
    shadowed_updates,
)
rewrite_bzl(
    os.path.join(bzl_backup, "data.bzl"),
    os.path.join(maven_dir, "data.bzl"),
    {"_CLASS_JARS": still_pruned_cj, "_SOURCE_JARS": still_pruned_sj},
    {},
)
sys.exit(10)
PY
        if (( rescue_rc == 0 )); then
            break
        elif (( rescue_rc == 10 )); then
            if [[ -x "$buildifier" ]]; then
                "$buildifier" "$maven_dir/artifacts.bzl" "$maven_dir/data.bzl"
            fi
            echo "Re-running local_maven_repository_generator after merged-exclusion rescue ..."
            if ! $BAZEL run //tools/base/bazel:local_maven_repository_generator; then
                echo "ERROR: local_maven_repository_generator failed after rescue; restoring .bzl and BUILD.maven." >&2
                restore_bzl_backup
                exit 1
            fi
        else
            restore_bzl_backup
            exit "$rescue_rc"
        fi
    done

    # Safeguard 2: Verify zero @maven//: targets referenced anywhere in //...
    # are missing before deleting any files in prebuilts/tools/common/m2.
    echo "Verifying post-prune @maven target references across workspace ..."
    local qrc=0
    $BAZEL query --keep_going 'deps(//... except //prebuilts/tools/common/m2:*)' \
        > /dev/null 2> "$workdir/post_prune_query.err" || qrc=$?
    if (( qrc != 0 && qrc != 3 )); then
        echo "ERROR: post-prune bazel query failed with status $qrc; restoring .bzl and BUILD.maven." >&2
        restore_bzl_backup
        exit "$qrc"
    fi
    if grep -qE "no such target '[^']*maven//:" "$workdir/post_prune_query.err"; then
        echo "ERROR: post-prune BUILD.maven is missing @maven targets referenced in workspace:" >&2
        grep -E "no such target '[^']*maven//:" "$workdir/post_prune_query.err" >&2
        restore_bzl_backup
        exit 1
    fi
}

if $prune_bzl; then
    prune_bzl_files
    if $dry_run; then
        exit 0
    fi
fi

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

# Also preserve files directly referenced outside BUILD files (lume.yaml, studio-lint-plugin).
for non_bazel_ref_file in \
    "$top/tools/vendor/google/lume/.idea/lume.yaml" \
    "$top/tools/vendor/google/lume-release/.idea/lume.yaml" \
    "$top/tools/vendor/google/studio-lint-plugin/build.gradle.kts"; do
    if [[ -f "$non_bazel_ref_file" ]]; then
        grep -oE 'repository/[A-Za-z0-9_./-]+' "$non_bazel_ref_file" \
            | while read -r rel; do
                candidate="$repo/${rel#repository/}"
                if [[ -f "$candidate" ]]; then
                    echo "$candidate"
                fi
            done >> "$workdir/file_refs.txt" || true
    fi
done

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
    restore_bzl_backup
    exit 1
fi

# Referenced directories, with a trailing slash so that ".../foo/1.0/" does
# not also match ".../foo/1.0.1/" or ".../foo/1.0-rc1/".
sed 's|/[^/]*$|/|' "$workdir/file_refs.txt" | sort -u \
    | { grep -xvF "$repo/" || true; } \
    > "$workdir/dir_refs.txt"

# Preserve sibling directories referenced by Gradle .module relative URLs
# (e.g. guava-33.2.1-jre.module -> ../33.2.1-android/guava-33.2.1-android.jar
# or KMP available-at redirects).
if command -v python3 >/dev/null; then
    python3 - "$repo" "$workdir/dir_refs.txt" <<'PY'
import os
import re
import sys

repo = os.path.normpath(sys.argv[1])
dir_refs_path = sys.argv[2]
with open(dir_refs_path, encoding="utf-8") as f:
    dir_refs = {line.rstrip("\n") for line in f if line.strip()}

frontier = set(dir_refs)
while frontier:
    next_frontier = set()
    for d in frontier:
        d_clean = d.rstrip("/")
        if not os.path.isdir(d_clean):
            continue
        for fn in os.listdir(d_clean):
            if not fn.endswith(".module"):
                continue
            with open(os.path.join(d_clean, fn), encoding="utf-8", errors="ignore") as mf:
                mtxt = mf.read()
            for url in re.findall(r'"url"\s*:\s*"(\.\./[^"]+)"', mtxt):
                rel_dir = os.path.dirname(url)
                if rel_dir in ("", ".", "..") or rel_dir.endswith("/.."):
                    continue
                cand = os.path.normpath(os.path.join(d_clean, rel_dir))
                if cand.startswith(repo + os.sep) and os.path.isdir(cand):
                    cand_ref = cand + "/"
                    if cand_ref not in dir_refs:
                        dir_refs.add(cand_ref)
                        next_frontier.add(cand_ref)
    frontier = next_frontier

with open(dir_refs_path, "w", encoding="utf-8") as f:
    for d in sorted(dir_refs):
        f.write(d + "\n")
PY
fi

# All strict ancestors of referenced directories (for parent-level NOTICE/OWNERS files).
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
            if (base ~ /^(LICENSE|LICENSE\.txt|NOTICE|NOTICE\.txt|OWNERS)$/ && (dir in keep)) next
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
    restore_bzl_backup
    report=$(mktemp "${TMPDIR:-/tmp}/maven_clean_failure.XXXXXX")
    sed "s|^$repo/||" "$workdir/unused_files.txt" > "$report"
    cat >&2 <<EOF
ERROR: maven_consistency_test failed after removing the files listed in
  $report
All files have been restored; repository state is unchanged.

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
