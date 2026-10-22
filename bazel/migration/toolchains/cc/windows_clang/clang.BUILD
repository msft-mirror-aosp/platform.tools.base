load(
    "@//tools/base/bazel/migration/toolchains/cc:actions.bzl",
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
load("@rules_cc//cc:action_names.bzl", "ACTION_NAMES")

package(default_visibility = ["@//tools/base/bazel/migration/toolchains/cc:__subpackages__"])

filegroup(
    name = "llvm_cov",
    srcs = ["bin/llvm-cov.exe"],
    visibility = ["//visibility:public"],
)

filegroup(
    name = "llvm_profdata",
    srcs = ["bin/llvm-profdata.exe"],
    visibility = ["//visibility:public"],
)

cc_tool(
    name = "clang-cl",
    applied_actions = C_COMPILE_ACTIONS + OBJC_COMPILE_ACTIONS + CPP_COMPILE_ACTIONS + ASSEMBLE_ACTIONS,
    runfiles = glob(
        ["bin/clang*"],
        exclude = [
            "bin/clang-check.exe",
            "bin/clang-format.exe",
            "bin/clang-scan-deps.exe",
            "bin/clang-tidy.exe",
        ],
    ) + [
        ":bin/libwinpthread-1.dll",
    ],
    tool = ":bin/clang-cl.exe",
)

cc_tool(
    name = "link",
    applied_actions = LINK_ACTIONS,
    runfiles = glob(["bin/*lld.exe"]) + [
        ":bin/libwinpthread-1.dll",
        ":bin/libxml2.dll",
    ],
    tool = ":bin/lld-link.exe",
)

cc_tool(
    name = "archiver",
    applied_actions = [ACTION_NAMES.cpp_link_static_library],
    runfiles = [
        ":bin/llvm-ar.exe",
        ":bin/libwinpthread-1.dll",
    ],
    tool = ":bin/llvm-lib.exe",
)

cc_toolchain_import(
    name = "compiler_runtime",
    include_paths = [
        ":lib/clang/19/include",
    ],
    support_files = glob(["lib/clang/19/include/**"]),
)
