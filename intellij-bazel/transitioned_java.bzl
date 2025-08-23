"""
 A set of rules to change default intellij platform i.e. "studio-sdk" .
"""

DEFAULT_INTELLIJ_PLATFORM = "studio-sdk"

def _fixed_intellij_platform_transition_impl(_settings, attr):
    return {"//tools/base/intellij-bazel:intellij_platform": attr.platform}

fixed_intellij_platform_transition = transition(
    implementation = _fixed_intellij_platform_transition_impl,
    inputs = [],
    outputs = ["//tools/base/intellij-bazel:intellij_platform"],
)

# Transition the java target to be built under the different platform
# See https://bazel.build/rules/lib/builtins/transition
def _transitioned_java_impl(ctx):
    return [ctx.attr.target[DefaultInfo], ctx.attr.target[JavaInfo]]

transitioned_java = rule(
    attrs = {
        "target": attr.label(providers = [JavaInfo]),
        "platform": attr.string(),
    },
    implementation = _transitioned_java_impl,
    cfg = fixed_intellij_platform_transition,
)
