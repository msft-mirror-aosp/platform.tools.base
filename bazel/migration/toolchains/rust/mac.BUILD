load("@bazel_skylib//rules:native_binary.bzl", "native_binary")
load("@rules_rust//rust:toolchain.bzl", "rust_stdlib_filegroup", "rust_toolchain")

package(default_visibility = ["//visibility:public"])

filegroup(
    name = "rustc",
    srcs = ["bin/rustc"],
)

filegroup(
    name = "rustc_lib",
    srcs = glob(["lib/*.dylib*"]),
)

filegroup(
    name = "rustdoc",
    srcs = ["bin/rustdoc"],
)

filegroup(
    name = "clippy_driver_bin",
    srcs = ["bin/clippy-driver"],
)

filegroup(
    name = "cargo",
    srcs = ["bin/cargo"],
)

filegroup(
    name = "rustfmt_bin",
    srcs = ["bin/rustfmt"],
)

native_binary(
    name = "rustfmt",
    src = ":rustfmt_bin",
    out = "rustfmt",
)

rust_stdlib_filegroup(
    name = "rust_std_arm64",
    srcs = glob(["lib/rustlib/aarch64-apple-darwin/lib/**"]),
)

rust_stdlib_filegroup(
    name = "rust_std_x64",
    srcs = glob(["lib/rustlib/x86_64-apple-darwin/lib/**"]),
)

rust_toolchain(
    name = "mac_arm64",
    allocator_library = "@rules_rust//ffi/cc/allocator_library",
    binary_ext = "",
    cargo = ":cargo",
    clippy_driver = ":clippy_driver_bin",
    dylib_ext = ".dylib",
    exec_triple = "x86_64-apple-darwin",
    experimental_use_cc_common_link = "@//tools/base/bazel/migration/toolchains/rust:use_cc_common_link",
    extra_exec_rustc_flags = [],
    extra_rustc_flags = [],
    global_allocator_library = "@rules_rust//ffi/cc/global_allocator_library",
    llvm_cov = "@clang_mac_all//:llvm_cov",
    llvm_profdata = "@clang_mac_all//:llvm_profdata",
    rust_doc = ":rustdoc",
    rust_std = ":rust_std_arm64",
    rustc = ":rustc",
    rustc_lib = ":rustc_lib",
    rustfmt = ":rustfmt_bin",
    staticlib_ext = ".a",
    stdlib_linkflags = [
        "-lSystem",
        "-lresolv",
    ],
    target_triple = "aarch64-apple-darwin",
)

toolchain(
    name = "mac_arm64_toolchain",
    exec_compatible_with = [
        "@platforms//os:macos",
    ],
    target_compatible_with = [
        "@platforms//cpu:arm64",
        "@platforms//os:macos",
    ],
    toolchain = ":mac_arm64",
    toolchain_type = "@rules_rust//rust:toolchain_type",
    visibility = ["//visibility:public"],
)

rust_toolchain(
    name = "mac_x64",
    allocator_library = "@rules_rust//ffi/cc/allocator_library",
    binary_ext = "",
    cargo = ":cargo",
    clippy_driver = ":clippy_driver_bin",
    dylib_ext = ".dylib",
    exec_triple = "x86_64-apple-darwin",
    experimental_use_cc_common_link = "//tools/base/bazel/migration/toolchains/rust:use_cc_common_link",
    extra_exec_rustc_flags = [],
    extra_rustc_flags = [],
    global_allocator_library = "@rules_rust//ffi/cc/global_allocator_library",
    llvm_cov = "@clang_mac_all//:llvm_cov",
    llvm_profdata = "@clang_mac_all//:llvm_profdata",
    rust_doc = ":rustdoc",
    rust_std = ":rust_std_x64",
    rustc = ":rustc",
    rustc_lib = ":rustc_lib",
    rustfmt = ":rustfmt_bin",
    staticlib_ext = ".a",
    stdlib_linkflags = [
        "-lSystem",
        "-lresolv",
    ],
    target_triple = "x86_64-apple-darwin",
)

toolchain(
    name = "mac_x64_toolchain",
    exec_compatible_with = [
        "@platforms//os:macos",
    ],
    target_compatible_with = [
        "@platforms//cpu:x86_64",
        "@platforms//os:macos",
    ],
    toolchain = ":mac_x64",
    toolchain_type = "@rules_rust//rust:toolchain_type",
    visibility = ["//visibility:public"],
)
