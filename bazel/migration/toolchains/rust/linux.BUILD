load("@bazel_skylib//rules:native_binary.bzl", "native_binary")
load("@rules_rust//rust:toolchain.bzl", "rust_stdlib_filegroup", "rust_toolchain")

package(default_visibility = ["//visibility:public"])

filegroup(
    name = "rustc",
    srcs = ["bin/rustc"],
)

filegroup(
    name = "rustc_lib",
    srcs = glob([
        "lib/*.so*",
        "lib/rustlib/x86_64-unknown-linux-gnu/lib/*.so*",
    ]),
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
    name = "rust_std_x64",
    srcs = glob(["lib/rustlib/x86_64-unknown-linux-gnu/lib/**"]),
)

rust_toolchain(
    name = "linux_x64",
    allocator_library = "@rules_rust//ffi/cc/allocator_library",
    binary_ext = "",
    cargo = ":cargo",
    clippy_driver = ":clippy_driver_bin",
    dylib_ext = ".so",
    exec_triple = "x86_64-unknown-linux-gnu",
    experimental_use_cc_common_link = "@//tools/base/bazel/migration/toolchains/rust:use_cc_common_link",
    extra_exec_rustc_flags = [],
    extra_rustc_flags = [],
    global_allocator_library = "@rules_rust//ffi/cc/global_allocator_library",
    llvm_cov = "@clang_linux_x64//:llvm_cov",
    llvm_profdata = "@clang_linux_x64//:llvm_profdata",
    rust_doc = ":rustdoc",
    rust_std = ":rust_std_x64",
    rustc = ":rustc",
    rustc_lib = ":rustc_lib",
    rustfmt = ":rustfmt_bin",
    staticlib_ext = ".a",
    stdlib_linkflags = [
        "-ldl",
        "-lpthread",
    ],
    target_triple = "x86_64-unknown-linux-gnu",
)

toolchain(
    name = "linux_x64_toolchain",
    exec_compatible_with = [
        "@platforms//cpu:x86_64",
        "@platforms//os:linux",
    ],
    target_compatible_with = [
        "@platforms//cpu:x86_64",
        "@platforms//os:linux",
    ],
    toolchain = ":linux_x64",
    toolchain_type = "@rules_rust//rust:toolchain_type",
    visibility = ["//visibility:public"],
)
