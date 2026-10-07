"""Exports MSVC libraries from the "VC\\Tools\\MSVC\\<version>" directory."""

load("@//tools/base/bazel/migration/toolchains/cc:rules.bzl", "cc_toolchain_import")

package(default_visibility = ["@//tools/base/bazel/migration/toolchains/cc:__subpackages__"])

cc_toolchain_import(
    name = "msvc_runtimes_x64",
    include_paths = [
        ":include",
    ],
    lib_search_paths = [
        ":lib/x64",
    ],
    support_files = glob(
        [
            "include/**",
            "lib/x64/**",
        ],
        exclude = [
            "include/cliext/**",
            "include/codeanalysis/**",
            "include/experimental/**",
            "include/maifest/**",
            "include/msclr/**",
            "lib/x64/store/**",
            "lib/x64/uwp/**",
            "lib/x64/clang_rt*",
        ],
    ),
)
