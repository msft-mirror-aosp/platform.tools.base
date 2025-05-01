load("//tools/base/bazel:repositories.bzl", "setup_external_repositories", "vendor_repository")

setup_external_repositories()

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
