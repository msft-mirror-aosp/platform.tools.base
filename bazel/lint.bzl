"""This module implements the lint_test rule."""

load("@rules_java//java:defs.bzl", "JavaInfo")

script_template = """\
:<<"::CMDLITERAL"
@ECHO OFF
GOTO :CMDSCRIPT
::CMDLITERAL

{binary} {xml} {baseline} {partial_args} {extraArgs} $@
exit $?
:CMDSCRIPT

{win_binary} {win_xml} {win_baseline} {partial_args} {extraArgs} %*
EXIT /B %ERRORLEVEL%
"""

LintPartialInfo = provider(
    doc = "Lint partial-analysis state for a module and its transitive module dependencies.",
    fields = {
        "modules": "depset of struct(name, partial_dir, is_test_sources): every transitive " +
                   "analyzable module and the partial-results directory of its analysis",
        "partial_dirs": "depset of File: partial-results directories of all transitive modules",
    },
)

# Attributes along which lint partial analysis results are propagated. "library" is the
# attribute through which _maven_library wraps the kotlin_library holding its sources.
_LINT_ASPECT_ATTRS = ["deps", "exports", "library"]

# Attributes in which a rule carries its own sources: "srcs" on kotlin_library and other
# standard rules, "java_srcs"/"kotlin_srcs" on _iml_module_.
_LINT_SRC_ATTRS = ["srcs", "java_srcs", "kotlin_srcs"]

# The lint configuration lint_partial_aspect uses to analyze every module. It matches what
# the kotlin_library and iml_module macros pass to lint_test, so a module's own lint test
# can normally report on the same analysis its dependents consume; a lint_test configured
# differently (extra custom rules, extra args, ...) analyzes its own module itself, see
# _uses_aspect_analysis. Partial state (e.g. inferred thread annotations) is only recorded
# by detectors that run during analysis, so the custom checks must be present here.
_LINT_CUSTOM_RULES = [
    Label("//tools/base/lint:studio-checks.lint-rules.jar"),
    Label("//tools/base/lint/studio-checks/compose-desktop-checks"),
]

# External annotations (e.g. IntelliJ SDK threading annotations such as @RequiresEdt) are
# always passed to analysis, whether or not the lint test passes them: without them,
# dependency analysis records incomplete thread constraints.
_LINT_EXTERNAL_ANNOTATIONS = [Label("//tools/base/external-annotations:annotations.zip")]

def _aspect_dep_targets(rule_attr):
    """Returns the targets in _LINT_ASPECT_ATTRS, whether label list or single label."""
    targets = []
    for attr in _LINT_ASPECT_ATTRS:
        value = getattr(rule_attr, attr, None)
        if value == None:
            continue
        targets += value if type(value) == "list" else [value]
    return targets

def _partial_project_xml(
        module_name,
        srcs,
        classpath,
        custom_rules,
        external_annotations,
        partial_results_dir,
        dep_modules,
        is_test_sources):
    """Generates a project descriptor XML for partial analysis.

    The same shape is used for both phases (see
    ProjectInitializerTest.testIsolatedPartialAnalysisWithSingleProjectRoot, which this
    integration follows): the analyzed/reported module carries its sources, classpath and
    flattened <dep> edges, while every transitive dependency module appears as a stub that
    only names its partial-results directory. All paths are execroot-relative and resolved
    against <root dir="."/>: analyze actions run in the execroot, and the reporting test
    lays its runfiles out under their execroot paths (see the symlinks in
    _partial_lint_test), so partial results recorded at analyze time resolve unchanged at
    report time.
    """
    project_xml = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
    project_xml += "<project>\n"
    project_xml += "<root dir=\".\" />\n"

    for jar in custom_rules:
        project_xml += "<lint-checks jar=\"{0}\" />\n".format(jar.path)

    for zip in external_annotations:
        project_xml += "<annotations file=\"{0}\" />\n".format(zip.path)

    project_xml += "<module name=\"{0}\" android=\"false\" partial-results-dir=\"{1}\">\n".format(
        module_name,
        partial_results_dir,
    )

    for file in srcs:
        # TODO (b/382580568) support lint test on directories
        if not file.is_directory:
            project_xml += "  <src file=\"{0}\" ".format(file.path)
            if is_test_sources:
                project_xml += "test=\"true\" "
            project_xml += "/>\n"

    for file in classpath:
        project_xml += "  <classpath jar=\"{0}\" />\n".format(file.path)

    for module in dep_modules:
        project_xml += "  <dep module=\"{0}\" />\n".format(module.name)

    project_xml += "</module>\n"

    for module in dep_modules:
        project_xml += "<module name=\"{0}\" android=\"false\" partial-results-dir=\"{1}\" />\n".format(
            module.name,
            module.partial_dir.path,
        )

    project_xml += "</project>\n"
    return project_xml

def _lint_analyze(
        ctx,
        lint_binary,
        output_prefix,
        module_name,
        srcs,
        classpath,
        custom_rules,
        external_annotations,
        dep_modules,
        dep_dirs,
        is_test_sources,
        extra_args):
    """Registers a lint --analyze-only action for one module.

    Returns the declared partial-results directory. Lint findings never fail the action;
    they are reported by the lint tests that merge these results. Dependency partial
    results are inputs because lint loads a dependency's partial state while analyzing a
    module (LintCliClient.getPartialResults).
    """
    partial_dir = ctx.actions.declare_directory(output_prefix + ".lint_partial_results")
    project_xml = ctx.actions.declare_file(output_prefix + ".lint_partial_project.xml")
    ctx.actions.write(project_xml, _partial_project_xml(
        module_name = module_name,
        srcs = srcs,
        classpath = classpath.to_list(),
        custom_rules = custom_rules,
        external_annotations = external_annotations,
        partial_results_dir = partial_dir.path,
        dep_modules = dep_modules,
        is_test_sources = is_test_sources,
    ))
    ctx.actions.run(
        executable = lint_binary,
        arguments = ["--analyze", project_xml.path] + extra_args,
        inputs = depset(
            srcs + [project_xml] + custom_rules + external_annotations,
            transitive = [classpath, dep_dirs],
        ),
        outputs = [partial_dir],
        mnemonic = "AndroidLintAnalyze",
        progress_message = "Lint analyzing " + module_name,
    )
    return partial_dir

def _lint_partial_aspect_impl(target, ctx):
    dep_targets = _aspect_dep_targets(ctx.rule.attr)
    dep_infos = [dep[LintPartialInfo] for dep in dep_targets if LintPartialInfo in dep]
    transitive_modules = [info.modules for info in dep_infos]
    transitive_dirs = [info.partial_dirs for info in dep_infos]

    srcs = [
        file
        for attr in _LINT_SRC_ATTRS
        for file in getattr(ctx.rule.files, attr, [])
        if not file.is_directory and file.extension in ("java", "kt")
    ]

    if JavaInfo not in target or not srcs:
        # Not an analyzable module (e.g. a jar import, or a rule whose sources lint cannot
        # see); its jars still reach lint through the JavaInfo classpath of dependents.
        return [LintPartialInfo(
            modules = depset(transitive = transitive_modules),
            partial_dirs = depset(transitive = transitive_dirs),
        )]

    # Same classpath as lint_test builds: runtime jars because compile-time jars are ijars
    # that lint cannot fully resolve against, plus compile-time jars for neverlink deps.
    transitive_jars = []
    for dep in dep_targets:
        if JavaInfo in dep:
            transitive_jars.append(dep[JavaInfo].transitive_runtime_jars)
            transitive_jars.append(dep[JavaInfo].transitive_compile_time_jars)
    stdlib = getattr(ctx.rule.attr, "stdlib", None)
    if stdlib and JavaInfo in stdlib:
        transitive_jars.append(stdlib[JavaInfo].transitive_runtime_jars)
        transitive_jars.append(stdlib[JavaInfo].transitive_compile_time_jars)

    extra_args = []
    jvm_target = getattr(ctx.rule.attr, "jvm_target", "")
    if jvm_target:
        extra_args += ["--java-language-level", jvm_target]

    module_name = str(target.label)

    # Rules do not say whether their sources are test sources; testonly is the best proxy.
    # A lint_test whose is_test_sources disagrees analyzes its module itself.
    is_test_sources = getattr(ctx.rule.attr, "testonly", False)
    partial_dir = _lint_analyze(
        ctx,
        lint_binary = ctx.executable._lint_binary,
        output_prefix = ctx.label.name,
        module_name = module_name,
        srcs = srcs,
        classpath = depset(transitive = transitive_jars),
        custom_rules = ctx.files._lint_custom_rules,
        external_annotations = ctx.files._lint_external_annotations,
        dep_modules = depset(transitive = transitive_modules).to_list(),
        dep_dirs = depset(transitive = transitive_dirs),
        is_test_sources = is_test_sources,
        extra_args = extra_args,
    )

    return [LintPartialInfo(
        modules = depset(
            [struct(name = module_name, partial_dir = partial_dir, is_test_sources = is_test_sources)],
            transitive = transitive_modules,
        ),
        partial_dirs = depset([partial_dir], transitive = transitive_dirs),
    )]

lint_partial_aspect = aspect(
    implementation = _lint_partial_aspect_impl,
    attr_aspects = _LINT_ASPECT_ATTRS,
    doc = "Runs lint in --analyze-only mode on every module in the dependency graph, " +
          "propagating the per-module partial results for a lint_test to report on.",
    attrs = {
        "_lint_binary": attr.label(
            executable = True,
            cfg = "exec",
            default = Label("//tools/base/bazel:BazelLintWrapper"),
        ),
        "_lint_custom_rules": attr.label_list(allow_files = True, default = _LINT_CUSTOM_RULES),
        "_lint_external_annotations": attr.label_list(allow_files = True, default = _LINT_EXTERNAL_ANNOTATIONS),
    },
)

def _write_launcher(ctx, partial_args):
    ctx.actions.write(
        output = ctx.outputs.launcher_script,
        content = script_template.format(
            binary = ctx.executable._binary.short_path,
            win_binary = ctx.executable._binary.short_path.replace("/", "\\"),
            xml = ctx.outputs.project_xml.short_path,
            win_xml = ctx.outputs.project_xml.short_path.replace("/", "\\"),
            baseline = "--lint-baseline " + ctx.file.baseline.path if ctx.file.baseline else "",
            win_baseline = "--lint-baseline " + ctx.file.baseline.path.replace("/", "\\") if ctx.file.baseline else "",
            partial_args = partial_args,
            extraArgs = " ".join(ctx.attr.extra_args),
        ),
        is_executable = True,
    )

def _uses_aspect_analysis(ctx, module):
    """Whether the aspect's analysis of the tested module is what this test would produce.

    The aspect analyzes with the default configuration (see _LINT_CUSTOM_RULES); a test
    with extra custom rules, extra lint arguments, extra partial_deps on its classpath or a
    different is_test_sources would report on an analysis that ran different checks, so it
    analyzes its module itself instead (at the cost of analyzing that module twice: once
    for its own report, once for its dependents' reports). Note that its dependencies were
    still analyzed with the default configuration: enabling an issue that is off by default
    makes lint report CannotEnableHidden for each of them (as in Gradle); disabling issues
    is fine.
    """
    if ctx.attr.partial_deps:
        return False
    if ctx.attr.is_test_sources != module.is_test_sources:
        return False
    if sorted([f.path for f in ctx.files.custom_rules]) != sorted([f.path for f in ctx.files._lint_custom_rules]):
        return False
    args = [a for a in ctx.attr.extra_args]

    # The aspect derives --java-language-level from the module's jvm_target attribute.
    for i in reversed(range(len(args) - 1)):
        if args[i] == "--java-language-level":
            args = args[:i] + args[i + 2:]
    return not args

def _partial_lint_test(ctx):
    # The aspect over partial_module (and any extra partial_deps) has registered one analyze
    # action per module in the dependency graph, the tested module included; the same
    # per-module results are shared between the lint tests of a module and its dependents.
    # The test itself only merges partial results and reports (BazelLintWrapper runs lint
    # with --report-only). Definite incidents of dependency modules are skipped at report
    # time (--XskipDefiniteIncidentsFromDeps): they are reported by those modules' own
    # lint tests.
    module_name = str(ctx.attr.partial_module.label)
    infos = [
        dep[LintPartialInfo]
        for dep in [ctx.attr.partial_module] + ctx.attr.partial_deps
        if LintPartialInfo in dep
    ]
    all_modules = depset(transitive = [info.modules for info in infos]).to_list()
    self_modules = [m for m in all_modules if m.name == module_name]
    if not self_modules:
        # partial_module has no analyzable sources; fall back to single-pass mode.
        return None
    dep_modules = [m for m in all_modules if m.name != module_name]
    dep_dirs = depset([m.partial_dir for m in dep_modules])

    if _uses_aspect_analysis(ctx, self_modules[0]):
        partial_dir = self_modules[0].partial_dir
    else:
        transitive_jars = []
        for dep in ctx.attr.deps:
            if JavaInfo in dep:
                transitive_jars.append(dep[JavaInfo].transitive_runtime_jars)
                transitive_jars.append(dep[JavaInfo].transitive_compile_time_jars)
        partial_dir = _lint_analyze(
            ctx,
            lint_binary = ctx.executable._analyze_binary,
            output_prefix = ctx.label.name,
            module_name = module_name,
            srcs = [f for f in ctx.files.srcs if not f.is_directory and f.extension in ("java", "kt")],
            classpath = depset(transitive = transitive_jars),
            custom_rules = ctx.files.custom_rules,
            external_annotations = depset(ctx.files.external_annotations + ctx.files._lint_external_annotations).to_list(),
            dep_modules = dep_modules,
            dep_dirs = dep_dirs,
            is_test_sources = ctx.attr.is_test_sources,
            extra_args = ctx.attr.extra_args,
        )

    # Merging (LintDriver.mergeOnly) reads no jars and parses no sources, so the report
    # descriptor names only the module's own sources (for locations and the baseline) and
    # the partial results.
    ctx.actions.write(
        output = ctx.outputs.project_xml,
        content = _partial_project_xml(
            module_name = module_name,
            srcs = ctx.files.srcs,
            classpath = [],
            custom_rules = ctx.files.custom_rules,
            external_annotations = ctx.files.external_annotations,
            partial_results_dir = partial_dir.path,
            dep_modules = dep_modules,
            is_test_sources = ctx.attr.is_test_sources,
        ),
    )

    _write_launcher(ctx, "--report-only")

    # Partial results record locations relative to the execroot, where the analyze actions
    # ran. Lay the runfiles out under the same execroot-relative paths (which differ from
    # the default runfiles layout for generated files, e.g. bazel-out/.../gen.kt) so those
    # locations resolve unchanged at report time.
    runfiles = ctx.runfiles(
        files = [ctx.outputs.project_xml],
        symlinks = {
            f.path: f
            for f in depset(
                ctx.files.srcs +
                ctx.files.custom_rules +
                ctx.files.external_annotations +
                [partial_dir] +
                ([ctx.file.baseline] if ctx.file.baseline else []),
                transitive = [dep_dirs],
            ).to_list()
        },
    ).merge(ctx.attr._binary[DefaultInfo].default_runfiles)

    return [DefaultInfo(executable = ctx.outputs.launcher_script, runfiles = runfiles)]

def _lint_test_impl(ctx):
    if ctx.attr.partial_module:
        result = _partial_lint_test(ctx)
        if result != None:
            return result

    transitive = []
    for dep in ctx.attr.deps:
        if JavaInfo in dep:
            transitive.append(dep[JavaInfo].transitive_runtime_jars)
            transitive.append(dep[JavaInfo].transitive_compile_time_jars)
    classpath = depset(transitive = transitive)

    # Create project XML:
    project_xml = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
    project_xml += "<project>\n"

    for jar in ctx.files.custom_rules:
        project_xml += "<lint-checks jar=\"{0}\" />\n".format(jar.short_path)

    for zip in ctx.files.external_annotations:
        project_xml += "<annotations file=\"{0}\" />\n".format(zip.short_path)

    project_xml += "<module name=\"{0}\" android=\"false\">\n".format(ctx.label.name)

    for file in ctx.files.srcs:
        # TODO (b/382580568) support lint test on directories
        if not file.is_directory:
            project_xml += "  <src file=\"{0}\" ".format(file.path)
            if ctx.attr.is_test_sources:
                project_xml += "test=\"true\" "
            project_xml += "/>\n"

    for file in classpath.to_list():
        project_xml += "  <classpath jar=\"{0}\" />\n".format(file.short_path)

    project_xml += "</module>\n"
    project_xml += "</project>\n"

    ctx.actions.write(output = ctx.outputs.project_xml, content = project_xml)

    # Create the launcher script:
    _write_launcher(ctx, "")

    # Compute runfiles:
    runfiles = ctx.runfiles(
        files = (
            [ctx.outputs.project_xml] +
            ([ctx.file.baseline] if ctx.file.baseline else []) +
            ctx.files.srcs +
            ctx.files.custom_rules +
            ctx.files.external_annotations
        ),
        transitive_files = depset(
            transitive = [
                ctx.attr._binary[DefaultInfo].default_runfiles.files,
                classpath,
            ],
        ),
    )

    return [DefaultInfo(executable = ctx.outputs.launcher_script, runfiles = runfiles)]

lint_test = rule(
    attrs = {
        "srcs": attr.label_list(allow_files = True),
        "custom_rules": attr.label_list(allow_files = True),
        "external_annotations": attr.label_list(allow_files = True),
        "deps": attr.label_list(allow_files = True),
        "baseline": attr.label(allow_single_file = True),
        "is_test_sources": attr.bool(),
        "extra_args": attr.string_list(),
        # If set, lint runs with partial analysis: every module in the dependency graph of
        # partial_module (that target itself included) is analyzed in isolation at build
        # time (results are cached per module and shared between the lint tests of the
        # module and its dependents) and the test only merges partial results and reports.
        # Cross-module detectors (e.g. inferred thread annotations) see dependency state
        # that the single-pass mode cannot see. Macros pass the library target they wrap.
        "partial_module": attr.label(aspects = [lint_partial_aspect]),
        # Extra module dependencies to analyze that are not reachable from partial_module's
        # deps/exports (e.g. lint_classpath additions).
        "partial_deps": attr.label_list(allow_files = True, aspects = [lint_partial_aspect]),
        # The configuration lint_partial_aspect analyzes with; see _uses_aspect_analysis.
        "_lint_custom_rules": attr.label_list(allow_files = True, default = _LINT_CUSTOM_RULES),
        "_lint_external_annotations": attr.label_list(allow_files = True, default = _LINT_EXTERNAL_ANNOTATIONS),
        "_binary": attr.label(
            executable = True,
            cfg = "target",
            default = Label("//tools/base/bazel:BazelLintWrapper"),
        ),
        # The same wrapper, for the analyze action a test registers itself (see
        # _uses_aspect_analysis); build actions run tools in the exec configuration.
        "_analyze_binary": attr.label(
            executable = True,
            cfg = "exec",
            default = Label("//tools/base/bazel:BazelLintWrapper"),
        ),
    },
    outputs = {
        "launcher_script": "%{name}.cmd",
        "project_xml": "%{name}_project.xml",
    },
    implementation = _lint_test_impl,
    test = True,
)
