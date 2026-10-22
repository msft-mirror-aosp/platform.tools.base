load("@bazel_skylib//rules:copy_file.bzl", "copy_file")
load("@bazel_skylib//rules:native_binary.bzl", "native_binary")
load("@rules_rust//rust:toolchain.bzl", "rust_stdlib_filegroup", "rust_toolchain")

filegroup(
    name = "rustc",
    srcs = ["bin/rustc.exe"],
    visibility = ["//visibility:public"],
)

copy_file(
    name = "libgcc_s_seh",
    src = "@mingw64//:x86_64-w64-mingw32/lib/libgcc_s_seh-1.dll",
    out = "bin/libgcc_s_seh-1.dll",
    allow_symlink = True,
)

copy_file(
    name = "libwinpthread",
    src = "@mingw64//:x86_64-w64-mingw32/bin/libwinpthread-1.dll",
    out = "bin/libwinpthread-1.dll",
    allow_symlink = True,
)

filegroup(
    name = "rustc_lib",
    srcs = glob(["bin/*.dll"]) + [
        ":libgcc_s_seh",
        ":libwinpthread",
    ],
    visibility = ["//visibility:public"],
)

filegroup(
    name = "rustdoc",
    srcs = ["bin/rustdoc.exe"],
    visibility = ["//visibility:public"],
)

filegroup(
    name = "clippy_driver_bin",
    srcs = ["bin/clippy-driver.exe"],
    visibility = ["//visibility:public"],
)

filegroup(
    name = "cargo",
    srcs = ["bin/cargo.exe"],
    visibility = ["//visibility:public"],
)

filegroup(
    name = "rustfmt_bin",
    srcs = ["bin/rustfmt.exe"],
    visibility = ["//visibility:public"],
)

native_binary(
    name = "rustfmt",
    src = ":rustfmt_bin",
)

rust_stdlib_filegroup(
    name = "rust_std_x64",
    srcs = glob(
        [
            "lib/rustlib/x86_64-pc-windows-gnu/lib/*.rlib",
            "lib/rustlib/x86_64-pc-windows-gnu/lib/*.dll*",
            "lib/rustlib/x86_64-pc-windows-gnu/lib/*.o",
            "lib/rustlib/x86_64-pc-windows-gnu/lib/self-contained/**",
        ],
    ),
    visibility = ["//visibility:public"],
)

rust_toolchain(
    name = "windows_x64",
    allocator_library = "@rules_rust//ffi/cc/allocator_library",
    binary_ext = ".exe",
    cargo = "@rust_windows//:cargo",
    clippy_driver = "@rust_windows//:clippy_driver_bin",
    dylib_ext = ".dll",
    exec_triple = "x86_64-pc-windows-gnu",
    extra_exec_rustc_flags = ["-v"],
    extra_rustc_flags = [],
    global_allocator_library = "@rules_rust//ffi/cc/global_allocator_library",
    llvm_cov = "@clang_win_x64//:llvm_cov",
    llvm_profdata = "@clang_win_x64//:llvm_profdata",
    rust_doc = "@rust_windows//:rustdoc",
    rust_std = "@rust_windows//:rust_std_x64",
    rustc = "@rust_windows//:rustc",
    rustc_lib = "@rust_windows//:rustc_lib",
    rustfmt = "@rust_windows//:rustfmt_bin",
    staticlib_ext = ".lib",
    stdlib_linkflags = [],
    target_triple = "x86_64-pc-windows-gnu",
)

toolchain(
    name = "windows_x64_toolchain",
    exec_compatible_with = [
        "@platforms//cpu:x86_64",
        "@platforms//os:windows",
    ],
    target_compatible_with = [
        "@platforms//cpu:x86_64",
        "@platforms//os:windows",
    ],
    toolchain = ":windows_x64",
    toolchain_type = "@rules_rust//rust:toolchain_type",
    visibility = ["//visibility:public"],
)
