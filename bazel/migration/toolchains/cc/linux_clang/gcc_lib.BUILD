load("@//tools/base/bazel/migration/toolchains/cc:rules.bzl", "cc_toolchain_import", "sysroot")
load("@rules_cc//cc:defs.bzl", "cc_import")

package(default_visibility = [
    "@//tools/base/bazel/migration/toolchains/cc:__subpackages__",
    "@clang_linux_x64//:__subpackages__",
])

cc_toolchain_import(
    name = "start_libs",
    support_files = [
        ":sysroot/usr/lib/Scrt1.o",
        ":sysroot/usr/lib/crti.o",
        ":sysroot/usr/lib/crtn.o",
    ],
)

cc_toolchain_import(
    name = "linker",
    support_files = [
        ":sysroot/usr/lib/ld-linux-x86-64.so.2",
    ],
)

cc_toolchain_import(
    name = "libs",
    include_paths = [
        ":sysroot/usr/include",
        ":sysroot/usr/include/x86_64-linux-gnu",
    ],
    support_files = glob([
        "sysroot/usr/include/*.h",
        "sysroot/usr/include/**/*.h",
        "sysroot/usr/include/x86_64-linux-gnu/**",
    ]) + [
        # keep sorted
        ":sysroot/usr/lib/libc.so",
        ":sysroot/usr/lib/libc.so.6",
        ":sysroot/usr/lib/libc-2.17.so",
        ":sysroot/usr/lib/libc_nonshared.a",
        ":sysroot/usr/lib/libdl.so",
        ":sysroot/usr/lib/libdl.so.2",
        ":sysroot/usr/lib/libdl-2.17.so",
        ":sysroot/usr/lib/libm.so",
        ":sysroot/usr/lib/libm.so.6",
        ":sysroot/usr/lib/libm-2.17.so",
        ":sysroot/usr/lib/libpthread.so",
        ":sysroot/usr/lib/libpthread.so.0",
        ":sysroot/usr/lib/libpthread-2.17.so",
        ":sysroot/usr/lib/libpthread_nonshared.a",
        ":sysroot/usr/lib/libresolv.so",
        ":sysroot/usr/lib/libresolv.so.2",
        ":sysroot/usr/lib/libresolv-2.17.so",
        ":sysroot/usr/lib/librt.so",
        ":sysroot/usr/lib/librt.so.1",
        ":sysroot/usr/lib/librt-2.17.so",
        ":sysroot/usr/lib/libutil.so",
        ":sysroot/usr/lib/libutil-2.17.so",
    ],
    deps = [":linker"],
)

cc_import(
    name = "libpulse",
    interface_library = ":sysroot/usr/lib/libpulse.so",
    shared_library = ":sysroot/usr/lib/libpulse.so.0.15.3",
    visibility = ["//visibility:public"],
)

cc_import(
    name = "libutil",
    interface_library = ":sysroot/usr/lib/libutil.so",
    shared_library = ":sysroot/usr/lib/libutil-2.17.so",
    visibility = ["//visibility:public"],
)

sysroot(
    name = "sysroot",
    path = "sysroot",
)

exports_files(
    srcs = ["lib/gcc/x86_64-linux/4.8.3"],
)
