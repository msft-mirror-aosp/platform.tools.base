"""Aspect to validate exec_properties."""

# Bazel targets allowed to set dockerNetwork to "standard".
DOCKER_NETWORK_ALLOWLIST = [
    Label("//tools/adt/idea/android/integration:BuildAndRunTest_linux"),
    Label("//tools/adt/idea/android/integration:BuildAndRunTest_windows"),
    Label("//tools/adt/idea/android/integration:BuildAndRunTest_mac"),
]

LARGE_MACHINE_ALLOWLIST = [
    # Issue b/228456598
    # These targets requires a large (min 16 GB) amount of memory to run.
    Label("//tools/adt/idea/sync-memory-tests:intellij.android.sync-memory-tests_tests__Benchmark2000Memory"),
    # This one runs mutually exclusively with the above in the perfgate-linux target
    Label("//tools/adt/idea/sync-memory-tests:intellij.android.sync-memory-tests_tests__Benchmark2000MemoryReleaseBranch"),
    Label("//tools/adt/idea/sync-memory-tests:intellij.android.sync-memory-tests_tests__Benchmark2000Cpu"),
    Label("//tools/adt/idea/sync-memory-tests:intellij.android.sync-memory-tests_tests__Benchmark2000CpuLatestGradleTest"),
    Label("//tools/adt/idea/sync-memory-tests:intellij.android.sync-memory-tests_tests__Benchmark2000CpuReleaseBranch"),
    Label("//tools/adt/idea/sync-memory-tests:intellij.android.sync-memory-tests_tests__Benchmark2000MemoryLatestGradleTest"),
    Label("//tools/adt/idea/sync-memory-tests:intellij.android.sync-memory-tests_tests__Benchmark2000CpuLatestKotlinTest"),
    Label("//tools/adt/idea/sync-memory-tests:intellij.android.sync-memory-tests_tests__Benchmark2000MemoryLatestKotlinTest"),
]

LARGE_MACHINE_FAILURE_MESSAGE = """'{}' is trying to use large machines.
Only approved targets can depend on large machine types.
If this is intentional, contact android-devtools-infra@ to approve the target."""

DOCKER_NETWORK_FAILURE_MESSAGE = """'{}' is trying to use dockerNetwork.
Only approved targets set dockerNetwork to "standard".
If this is intentional add the target to DOCKER_NETWORK_ALLOWLIST in //tools/base/bazel/validations/exec_props.bzl."""

def _limit_exec_properties_impl(target, ctx):
    _ = target  # unused  # buildifier: disable=unused-variable
    if not hasattr(ctx.rule.attr, "exec_properties"):
        return []
    if not ctx.rule.attr.exec_properties:
        return []
    if "manual" in ctx.rule.attr.tags:
        return []
    _check_machine_size(ctx.label, ctx.rule)
    _check_docker_network(ctx.label, ctx.rule)
    return []

def _is_eval(rule):
    tags = rule.attr.tags
    return "eval" in tags and "noci:studio-linux" in tags and "noci:studio-win" in tags

def _check_machine_size(label, rule):
    exec_properties = rule.attr.exec_properties
    machine_size = exec_properties.get("label:machine-size")

    # We do not want to run evals on presubmit
    is_allowed = label in LARGE_MACHINE_ALLOWLIST or _is_eval(rule)
    if machine_size == "large" and not is_allowed:
        fail(LARGE_MACHINE_FAILURE_MESSAGE.format(str(label)))

def _check_docker_network(label, rule):
    exec_properties = rule.attr.exec_properties

    docker_network = exec_properties.get("dockerNetwork")
    is_allowed = label in DOCKER_NETWORK_ALLOWLIST or _is_eval(rule)
    if docker_network == "standard" and not is_allowed:
        fail(DOCKER_NETWORK_FAILURE_MESSAGE.format(str(label)))

limit_exec_properties = aspect(
    implementation = _limit_exec_properties_impl,
    attr_aspects = [],
)
