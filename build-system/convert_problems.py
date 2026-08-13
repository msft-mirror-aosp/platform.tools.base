#!/usr/bin/env python3
import argparse
import json
import os
import re
import shutil
import sys

def normalize(path):
    # Sandbox normalization
    path = re.sub(r'^/b/f/w/', '', path)
    path = re.sub(r'^/proc/self/cwd/', '', path)
    # Normalize bazel-out
    path = re.sub(r'^bazel-out/[^/]+/bin/', 'bazel-bin/', path)
    path = re.sub(r'^/b/f/w/bazel-out/[^/]+/bin/', 'bazel-bin/', path)

    # Normalize bazel-bin
    path = re.sub(r'^bazel-bin/[^/]+/_build/build/', '<bazel-bin>/', path)


    return path

def serialize_location(location):
    if 'path' in location:
        path = normalize(location['path'])
        if 'line' in location:
            return f"{path}:{location['line']}"
        return path
    elif 'taskPath' in location:
        return f"task:{location['taskPath']}"
    elif 'pluginId' in location:
        return f"plugin:{location['pluginId']}"
    else:
        return str(location)

def serialize_problem(diagnostic):
    prob_id = diagnostic.get('problemId', [])
    prob_id_str = " > ".join([p.get('name', 'unknown') for p in prob_id])

    locations = diagnostic.get('locations', [])
    loc_strs = [serialize_location(l) for l in locations]
    # Sort locations to be stable
    loc_strs.sort()

    loc_part = ", ".join(loc_strs)

    solutions = diagnostic.get('solutions', [])
    if solutions:
        sol_part = " | " + ", ".join(solutions)
    else:
        sol_part = ""

    return f"{prob_id_str} | {loc_part}{sol_part}"

def extract_problems(html_path):
    if not os.path.exists(html_path):
        print(f"Can't find html at: {html_path}", file=sys.stderr)
        sys.exit(1)

    with open(html_path, 'r', encoding='utf-8') as f:
        lines = f.readlines()

    json_lines = []
    in_report = False
    for line in lines:
        cleaned = line.strip()
        if cleaned == "// end-report-data":
            in_report = False
        if in_report:
            json_lines.append(line)
        if cleaned == "// begin-report-data":
            in_report = True

    if not json_lines:
        lines = "\n".join(lines)
        print(f"Can't find report-data section in:\n{lines}")
        sys.exit(1)

    try:
        data = json.loads("".join(json_lines))
    except json.JSONDecodeError as e:
        print(f"Error parsing JSON from {html_path}, json ${json_lines}, error: {e}", file=sys.stderr)
        sys.exit(1)

    diagnostics = data.get('diagnostics', [])
    problems = list()
    for d in diagnostics:
        problem = serialize_problem(d)
        # Filter out compilation problems
        if not problem.startswith("compilation >"):
            problems.append(serialize_problem(d))

    return sorted(problems)

def main():
    parser = argparse.ArgumentParser(description="Convert Gradle problems report to a flat list of problems.")
    parser.add_argument("--html_report", required=True, help="Path to the problems-report.html")
    parser.add_argument("--output_path", required=True, help="Path to write the new problems file")

    args = parser.parse_args()

    try:
        current_problems = extract_problems(args.html_report)
    except Exception as e:
        print(f"Error extracting problems: {e}", file=sys.stderr)
        sys.exit(1)

    # Always write the current problems to the new problems file.
    try:
        with open(args.output_path, 'w', encoding='utf-8') as f:
            for p in current_problems:
                f.write(p + '\n')
    except Exception as e:
        print(f"Error writing new problems: {e}", file=sys.stderr)
        sys.exit(1)

if __name__ == "__main__":
    main()
