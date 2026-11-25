load(
    "@//tools/base/bazel/migration/toolchains/cc:actions.bzl",
    "ARCHIVER_ACTIONS",
    "ASSEMBLE_ACTIONS",
    "CPP_COMPILE_ACTIONS",
    "C_COMPILE_ACTIONS",
    "LINK_ACTIONS",
    "OBJC_COMPILE_ACTIONS",
)
load(
    "@//tools/base/bazel/migration/toolchains/cc:rules.bzl",
    "cc_tool",
    "cc_toolchain_import",
)
load("@bazel_tools//tools/build_defs/cc:action_names.bzl", "ACTION_NAMES")

package(default_visibility = ["@//tools/base/bazel/migration/toolchains/cc:__subpackages__"])

filegroup(
    name = "llvm_cov",
    srcs = ["bin/llvm-cov"],
    visibility = ["//visibility:public"],
)

filegroup(
    name = "llvm_profdata",
    srcs = ["bin/llvm-profdata"],
    visibility = ["//visibility:public"],
)

cc_tool(
    name = "clang",
    applied_actions = C_COMPILE_ACTIONS + OBJC_COMPILE_ACTIONS + ASSEMBLE_ACTIONS + LINK_ACTIONS,
    runfiles = glob(
        ["bin/*"],
        exclude = [
            "bin/clang-check",
            "bin/clangd",
            "bin/*clang-format",
            "bin/clang-tidy*",
            "bin/lldb*",
            "bin/llvm-bolt",
            "bin/llvm-config",
            "bin/llvm-cfi-verify",
        ],
    ),
    tool = ":bin/clang",
)

cc_tool(
    name = "clang++",
    applied_actions = CPP_COMPILE_ACTIONS,
    runfiles = glob(
        ["bin/*"],
        exclude = [
            "bin/clang-check",
            "bin/clangd",
            "bin/*clang-format",
            "bin/clang-tidy*",
            "bin/lldb*",
            "bin/llvm-bolt",
            "bin/llvm-config",
            "bin/llvm-cfi-verify",
        ],
    ),
    tool = ":bin/clang++",
)

cc_tool(
    name = "archiver",
    applied_actions = ARCHIVER_ACTIONS,
    tool = ":bin/llvm-ar",
)

cc_tool(
    name = "strip",
    applied_actions = [ACTION_NAMES.strip],
    runfiles = [":bin/llvm-objcopy"],
    tool = ":bin/llvm-strip",
)

cc_toolchain_import(
    name = "compiler_rt",
    include_paths = glob(
        [
            "lib/clang/*/include",
        ],
        exclude_directories = 0,
    ),
    lib_search_paths = glob(
        [
            "lib/clang/*/lib/darwin",
        ],
        exclude_directories = 0,
    ),
    support_files = glob(
        [
            "lib/clang/*/include/**",
            "lib/clang/*/lib/darwin/*",
        ],
    ),
)
