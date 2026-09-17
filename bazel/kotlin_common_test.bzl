"""Unit tests for kotlin_common.bzl."""

load("@bazel_skylib//lib:unittest.bzl", "asserts", "unittest")
load(
    ":kotlin_common.bzl",
    "LEGACY_JVM_TARGETS",
    "add_jvm_target_opts",
    "validate_jvm_target_opts_error",
)

def _kotlin_common_test_impl(ctx):
    env = unittest.begin(ctx)

    # 1. Verify LEGACY_JVM_TARGETS contains "8"
    asserts.true(env, "8" in LEGACY_JVM_TARGETS, "Expected '8' in LEGACY_JVM_TARGETS")

    # 2. Verify add_jvm_target_opts for legacy targets always uses --release / -Xjdk-release
    javac_8, kotlinc_8 = add_jvm_target_opts(None, "8", [], [])
    asserts.equals(env, ["--release", "8"], javac_8)
    asserts.equals(env, ["-Xjdk-release=1.8"], kotlinc_8)

    # 3. Verify add_jvm_target_opts for modern targets (injects -jvm-target for kotlinc)
    javac_11, kotlinc_11 = add_jvm_target_opts(None, "11", [], [])
    asserts.equals(env, [], javac_11)
    asserts.equals(env, ["-jvm-target", "11"], kotlinc_11)

    javac_17, kotlinc_17 = add_jvm_target_opts(None, "17", [], [])
    asserts.equals(env, [], javac_17)
    asserts.equals(env, ["-jvm-target", "17"], kotlinc_17)

    javac_21, kotlinc_21 = add_jvm_target_opts(None, "21", [], [])
    asserts.equals(env, [], javac_21)
    asserts.equals(env, ["-jvm-target", "21"], kotlinc_21)

    javac_25, kotlinc_25 = add_jvm_target_opts(None, "25", [], [])
    asserts.equals(env, [], javac_25)
    asserts.equals(env, ["-jvm-target", "25"], kotlinc_25)

    # 4. Verify validate_jvm_target_opts_error detects prohibited options
    asserts.true(env, validate_jvm_target_opts_error(["--release", "8"], "label") != None, "Expected error for ['--release', '8']")
    asserts.true(env, validate_jvm_target_opts_error(["--release 8"], "label") != None, "Expected error for --release 8")
    asserts.true(env, validate_jvm_target_opts_error(["--release=8"], "label") != None, "Expected error for --release=8")
    asserts.true(env, validate_jvm_target_opts_error(["-release", "8"], "label") != None, "Expected error for ['-release', '8']")
    asserts.true(env, validate_jvm_target_opts_error(["-release 8"], "label") != None, "Expected error for -release 8")
    asserts.true(env, validate_jvm_target_opts_error(["-release=8"], "label") != None, "Expected error for -release=8")
    asserts.true(env, validate_jvm_target_opts_error(["--target", "8"], "label") != None, "Expected error for ['--target', '8']")
    asserts.true(env, validate_jvm_target_opts_error(["--target 8"], "label") != None, "Expected error for --target 8")
    asserts.true(env, validate_jvm_target_opts_error(["--target=8"], "label") != None, "Expected error for --target=8")
    asserts.true(env, validate_jvm_target_opts_error(["-target", "8"], "label") != None, "Expected error for ['-target', '8']")
    asserts.true(env, validate_jvm_target_opts_error(["-target 8"], "label") != None, "Expected error for -target 8")
    asserts.true(env, validate_jvm_target_opts_error(["-target=8"], "label") != None, "Expected error for -target=8")
    asserts.true(env, validate_jvm_target_opts_error(["--source", "8"], "label") != None, "Expected error for ['--source', '8']")
    asserts.true(env, validate_jvm_target_opts_error(["--source 8"], "label") != None, "Expected error for --source 8")
    asserts.true(env, validate_jvm_target_opts_error(["--source=8"], "label") != None, "Expected error for --source=8")
    asserts.true(env, validate_jvm_target_opts_error(["-source", "8"], "label") != None, "Expected error for ['-source', '8']")
    asserts.true(env, validate_jvm_target_opts_error(["-source 8"], "label") != None, "Expected error for -source 8")
    asserts.true(env, validate_jvm_target_opts_error(["-source=8"], "label") != None, "Expected error for -source=8")
    asserts.true(env, validate_jvm_target_opts_error(["--source 8 --target 8"], "label") != None, "Expected error for --source 8 --target 8")
    asserts.true(env, validate_jvm_target_opts_error(["-source 8 -target 8"], "label") != None, "Expected error for -source 8 -target 8")
    asserts.true(env, validate_jvm_target_opts_error(["-source", "17", "-target", "17"], "label") != None, "Expected error for ['-source', '17', '-target', '17']")
    asserts.true(env, validate_jvm_target_opts_error(["-jvm-target", "1.8"], "label") != None, "Expected error for ['-jvm-target', '1.8']")
    asserts.true(env, validate_jvm_target_opts_error(["-jvm-target 1.8"], "label") != None, "Expected error for -jvm-target 1.8")
    asserts.true(env, validate_jvm_target_opts_error(["-jvm-target=1.8"], "label") != None, "Expected error for -jvm-target=1.8")
    asserts.true(env, validate_jvm_target_opts_error(["-Xjdk-release=1.8"], "label") != None, "Expected error for -Xjdk-release=1.8")
    asserts.true(env, validate_jvm_target_opts_error(["-Xjdk-release:1.8"], "label") != None, "Expected error for -Xjdk-release:1.8")

    # 6. Verify allowed options do not trigger errors
    asserts.equals(env, None, validate_jvm_target_opts_error(["-sourcepath", "/foo/bar"], "label"))
    asserts.equals(env, None, validate_jvm_target_opts_error(["-sourcepath /foo/bar"], "label"))
    asserts.equals(env, None, validate_jvm_target_opts_error(["-sourcepath=/foo/bar"], "label"))
    asserts.equals(env, None, validate_jvm_target_opts_error(["-Xsam-conversions=class"], "label"))
    asserts.equals(env, None, validate_jvm_target_opts_error(["--add-exports=foo/bar=ALL-UNNAMED"], "label"))
    asserts.equals(env, None, validate_jvm_target_opts_error(["-Werror"], "label"))
    asserts.equals(env, None, validate_jvm_target_opts_error(["-encoding", "utf-8"], "label"))

    # 7. Verify add_jvm_target_opts succeeds on valid options
    add_jvm_target_opts(None, "8", ["-Werror"], ["-Xsam-conversions=class"], label = "test")

    return unittest.end(env)

kotlin_common_test = unittest.make(_kotlin_common_test_impl)
