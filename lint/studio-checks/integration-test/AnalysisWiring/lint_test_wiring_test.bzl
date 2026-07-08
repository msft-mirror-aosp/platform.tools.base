# Copyright (C) 2026 The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

"""Checks how a lint_test target is wired for partial analysis.

The other integration-test packages check lint's findings end to end; this checks the
build-graph decisions that findings cannot reveal: whether a lint test reports on the
aspect's shared per-module analysis or registers an analysis of its own module (a module
analyzed twice is a silent performance regression), what its runfiles carry, and what its
project descriptor and launcher say. Everything is read off the lint_test target's
DefaultInfo, so lint_test needs no test-only providers.
"""

def _owned_by(file, targets):
    for target in targets:
        if file.owner == target.label:
            return True
    return False

def _add_greps(lines, file, must_contain, must_lack):
    """Appends shell lines checking file for the given texts (fixed strings)."""
    for text in must_contain:
        lines.append("grep -qF -- '%s' '%s' || { echo 'expected in %s: %s'; status=1; }" % (text, file.short_path, file.basename, text))
    for text in must_lack:
        lines.append("grep -qF -- '%s' '%s' && { echo 'unexpected in %s: %s'; status=1; }" % (text, file.short_path, file.basename, text))

def _lint_test_wiring_test_impl(ctx):
    lint_test = ctx.attr.lint_test
    runfiles = lint_test[DefaultInfo].default_runfiles
    files = runfiles.files.to_list() + [s.target_file for s in runfiles.symlinks.to_list()]
    partial_dirs = [f for f in files if f.is_directory and f.basename.endswith(".lint_partial_results")]
    own_dirs = [f for f in partial_dirs if f.owner == lint_test.label]
    dep_jars = [f for f in files if f.extension == "jar" and _owned_by(f, ctx.attr.deps)]

    failures = []
    if ctx.attr.analysis == "aspect":
        if own_dirs:
            failures.append("registers its own analysis (%s) although the aspect's should do" % own_dirs[0].short_path)
        if not [f for f in partial_dirs if f.owner == ctx.attr.module.label]:
            failures.append("does not carry the aspect's analysis of %s" % ctx.attr.module.label)
    elif ctx.attr.analysis == "own":
        if len(own_dirs) != 1:
            failures.append("expected exactly one analysis of its own, got %s" % [f.short_path for f in own_dirs])
        if [f for f in partial_dirs if f.owner == ctx.attr.module.label]:
            failures.append("carries the aspect's analysis of %s although it analyzes it itself" % ctx.attr.module.label)
    elif partial_dirs:
        failures.append("carries partial results %s in single-pass mode" % [f.short_path for f in partial_dirs])

    for dep in ctx.attr.partial_results_of:
        if not [f for f in partial_dirs if f.owner == dep.label]:
            failures.append("does not carry the partial results of %s" % dep.label)
    if ctx.attr.analysis == "none":
        if not dep_jars:
            failures.append("single-pass mode needs the dependency jars in its runfiles")
    elif dep_jars:
        failures.append("carries dependency jars %s although merging reads no jars" % [f.short_path for f in dep_jars])

    outputs = lint_test[DefaultInfo].files.to_list()
    project_xml = [f for f in outputs if f.basename.endswith("_project.xml")]
    launcher = [f for f in outputs if f.basename.endswith(".cmd")]
    if len(project_xml) != 1 or len(launcher) != 1:
        failures.append("expected a project XML and a launcher among %s" % [f.short_path for f in outputs])

    lines = ["#!/bin/bash", "status=0"]
    for failure in failures:
        lines.append("echo '%s: %s'" % (lint_test.label, failure.replace("'", "")))
        lines.append("status=1")

    if project_xml:
        _add_greps(lines, project_xml[0], ctx.attr.project_xml_contains, ctx.attr.project_xml_lacks)
    if launcher:
        _add_greps(lines, launcher[0], ctx.attr.launcher_contains, ctx.attr.launcher_lacks)
    lines.append("exit $status")

    script = ctx.actions.declare_file(ctx.label.name + ".sh")
    ctx.actions.write(script, "\n".join(lines) + "\n", is_executable = True)
    return [DefaultInfo(
        executable = script,
        runfiles = ctx.runfiles(files = project_xml + launcher),
    )]

_lint_test_wiring_test = rule(
    implementation = _lint_test_wiring_test_impl,
    test = True,
    attrs = {
        "lint_test": attr.label(mandatory = True, doc = "The lint_test target under test."),
        "module": attr.label(doc = "The module the lint test reports on."),
        "analysis": attr.string(
            mandatory = True,
            values = ["aspect", "own", "none"],
            doc = "Whose analysis of module the test reports on: the aspect's shared one, " +
                  "one registered by the lint test itself, or none (single-pass mode).",
        ),
        "deps": attr.label_list(doc = "Dependencies whose jars are only expected in the runfiles in single-pass mode."),
        "partial_results_of": attr.label_list(doc = "Modules whose partial results the runfiles must carry."),
        "project_xml_contains": attr.string_list(),
        "project_xml_lacks": attr.string_list(),
        "launcher_contains": attr.string_list(),
        "launcher_lacks": attr.string_list(),
    },
)

def lint_test_wiring_test(name, **kwargs):
    """See _lint_test_wiring_test; the generated test script needs bash."""
    _lint_test_wiring_test(
        name = name,
        tags = kwargs.pop("tags", []) + ["noci:studio-win"],
        **kwargs
    )
