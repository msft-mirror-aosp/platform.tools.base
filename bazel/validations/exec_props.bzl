"""Aspect to validate exec_properties."""

# Bazel targets allowed to set dockerNetwork to "standard".
DOCKER_NETWORK_ALLOWLIST = [
    Label("//tools/adt/idea/android/integration:BuildAndRunTest_linux"),
    Label("//tools/adt/idea/android/integration:BuildAndRunTest_windows"),
    Label("//tools/adt/idea/android/integration:BuildAndRunTest_mac"),
]

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
    _check_docker_network(ctx.label, ctx.rule)
    return []

def _is_eval(rule):
    tags = rule.attr.tags
    return "eval" in tags and "noci:studio-linux" in tags and "noci:studio-win" in tags

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
