"""Exports MinGW64 libraries."""

load("@//tools/base/bazel/migration/toolchains/cc:rules.bzl", "cc_toolchain_import")

package(default_visibility = ["@//tools/base/bazel/migration/toolchains/cc:__subpackages__"])

cc_toolchain_import(
    name = "mingw_libs_x64",
    lib_search_paths = [
        ":lib/gcc/x86_64-w64-mingw32/4.8.3",
        ":x86_64-w64-mingw32/lib",
    ],
    support_files = [
        # keep sorted
        ":lib/gcc/x86_64-w64-mingw32/4.8.3/libgcc.a",
        ":lib/gcc/x86_64-w64-mingw32/4.8.3/libgcc_eh.a",
        ":x86_64-w64-mingw32/lib/libadvapi32.a",
        ":x86_64-w64-mingw32/lib/libkernel32.a",
        ":x86_64-w64-mingw32/lib/libmingw32.a",
        ":x86_64-w64-mingw32/lib/libmingwex.a",
        ":x86_64-w64-mingw32/lib/libmsvcrt.a",
        ":x86_64-w64-mingw32/lib/libntdll.a",
        ":x86_64-w64-mingw32/lib/libpthread.a",
        ":x86_64-w64-mingw32/lib/libuser32.a",
        ":x86_64-w64-mingw32/lib/libuserenv.a",
        ":x86_64-w64-mingw32/lib/libws2_32.a",
    ],
)

exports_files(
    [
        "x86_64-w64-mingw32/lib/libgcc_s_seh-1.dll",
        "x86_64-w64-mingw32/bin/libwinpthread-1.dll",
    ],
    visibility = ["@rust_windows//:__pkg__"],
)
