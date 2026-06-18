"""Aspect to validate that only the specified targets use eternal timeout.

When bazel is invoked with this aspect attached, it validates that only
the allowlisted targets below can use the eternal timeout.
"""
APPROVED_ETERNAL_TESTS = [
    "@@//tools/adt/idea/sync-memory-tests:intellij.android.sync-memory-tests-mac_tests__Benchmark2000Cpu",
    "@@//tools/adt/idea/sync-memory-tests:intellij.android.sync-memory-tests-mac_tests__Benchmark2000Memory",
]

EXEMPT_TARGET_PREFIXES = [
    # We don't have control over IntelliJ tests under tools/idea (and we don't run them anyway).
    "@@community+//",
]

FAILURE_MESSAGE = """Test target {} has timeout set to eternal.
We do not want any new target with eternal timeout (b/162943254).
If this is intentional, contact android-devtools-infra@ to relax the restriction on the target."""

IGNORE_TAG = ["ci:perfgate-linux", "ci:perfgate-win", "perfgate-release"]

def _has_intersect(this, other):
    for item in this:
        if item in other:
            return True
    return False

def _no_eternal_tests_impl(target, ctx):
    if not ctx.rule.kind.endswith("_test"):
        return []
    if ctx.rule.attr.timeout != "eternal":
        return []
    if str(ctx.label) in APPROVED_ETERNAL_TESTS:
        return []
    if any([str(ctx.label).startswith(prefix) for prefix in EXEMPT_TARGET_PREFIXES]):
        return []
    if _has_intersect(IGNORE_TAG, ctx.rule.attr.tags):
        return []
    fail(FAILURE_MESSAGE.format(str(ctx.label)))

no_eternal_tests = aspect(
    implementation = _no_eternal_tests_impl,
    attr_aspects = [],
)
