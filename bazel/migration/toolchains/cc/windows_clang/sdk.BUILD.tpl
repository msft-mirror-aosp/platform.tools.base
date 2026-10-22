"""Exports Windows SDK libraries and tools from the "Windows Kits\\<os major>" directory."""

load("@//tools/base/bazel/migration/toolchains/cc:rules.bzl", "cc_toolchain_import")
load(
    "@//tools/base/bazel/migration/toolchains/cc/windows_clang:sdk_tools.bzl",
    "windows_resource_compiler_toolchain",
)

package(default_visibility = ["@//tools/base/bazel/migration/toolchains/cc:__subpackages__"])

cc_toolchain_import(
    name = "sdk_libs_x64",
    include_paths = [
        ":include/%{sdk_version}/ucrt",
        ":include/%{sdk_version}/shared",
        ":include/%{sdk_version}/um",
        ":include/%{sdk_version}/winrt",
        ":include/%{sdk_version}/cppwinrt",
    ],
    lib_search_paths = [
        ":lib/%{sdk_version}/ucrt/x64",
        ":lib/%{sdk_version}/um/x64",
    ],
    support_files = glob(
        [
            "include/%{sdk_version}/**",
            "lib/%{sdk_version}/ucrt/x64/**",
            "lib/%{sdk_version}/um/x64/**",
        ],
    ),
)

windows_resource_compiler_toolchain(
    name = "resource_compiler_toolchain_x64",
    rc_exe = ":bin/%{sdk_version}/x64/rc.exe",
)
