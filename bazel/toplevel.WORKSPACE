load("//tools/base/bazel:repositories.bzl", "setup_external_repositories", "vendor_repository")

setup_external_repositories()

# BEGIN Cc toolchain dependencies
# TODO(b/340640065): Symlinks fail due to existing file when moved to bzlmod.
new_local_repository(
    name = "clang_linux_x64",
    build_file = "//build/bazel/toolchains/cc/linux_clang:clang.BUILD",
    path = "prebuilts/clang/host/linux-x86/clang-r536225",
)

new_local_repository(
    name = "clang_mac_all",
    build_file = "//build/bazel/toolchains/cc/mac_clang:clang.BUILD",
    path = "prebuilts/clang/host/darwin-x86/clang-r536225",
)

new_local_repository(
    name = "clang_win_x64",
    build_file = "//build/bazel/toolchains/cc/windows_clang:clang.BUILD",
    path = "prebuilts/clang/host/windows-x86/clang-r536225",
)

new_local_repository(
    name = "gcc_lib",
    build_file = "//build/bazel/toolchains/cc/linux_clang:gcc_lib.BUILD",
    path = "prebuilts/gcc/linux-x86/host/x86_64-linux-glibc2.17-4.8",
)
# END Cc toolchain dependencies

# rules_android_ndk must come before loading vendor.bzl, because it is
# configured as a vendor dependency and can only be configured with a valid
# ANDROID_NDK path.
local_repository(
    name = "rules_android_ndk",
    path = "prebuilts/tools/common/external-src-archives/bazelbuild-rules_android_ndk/1ed5be3",
)

vendor_repository(
    name = "vendor",
    bzl = "@//tools/base/bazel:vendor.bzl",
    function = "setup_vendor_repositories",
)

load("@vendor//:vendor.bzl", "setup_vendor_repositories")

setup_vendor_repositories()

# An empty local repository which must be overridden according to the instructions at
# go/agp-profiled-benchmarks if running the "_profiled" AGP build benchmarks.
new_local_repository(
    name = "yourkit_controller",
    build_file = "//tools/base:yourkit-controller/yourkit.BUILD",
    path = "tools/base/yourkit-controller",
)

new_local_repository(
    name = "maven",
    build_file = "//tools/base/bazel:maven/BUILD.maven",
    path = "prebuilts/tools/common/m2",
)

new_local_repository(
    name = "jar_jar",
    build_file = "//tools/base/bazel/jarjar:jarjar.BUILD",
    path = "external/jarjar",
)

load("//tools/base/intellij-bazel:platforms.bzl", "setup_intellij_platforms")

setup_intellij_platforms()

vendor_repository(
    name = "aswb_test_deps",
    bzl = "@//tools/adt/idea/aswb/testing/test_deps:deps.bzl",
    function = "aswb_test_deps_dependencies",
)

load("@aswb_test_deps//:vendor.bzl", "aswb_test_deps_dependencies")

aswb_test_deps_dependencies()
