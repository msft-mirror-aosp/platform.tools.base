load("@bazel_tools//tools/build_defs/repo:http.bzl", "http_archive")
load("//tools/base/bazel:repositories.bzl", "setup_external_repositories", "vendor_repository")

http_archive(
    name = "perfetto",
    patch_args = ["-p1"],
    sha256 = "8bfada46f61b938ec26a777db3c8facf562af8aa22049d478f169f3105f53b4c",
    strip_prefix = "perfetto-52.0",
    url = "https://github.com/google/perfetto/archive/refs/tags/v52.0.zip",
)

# TODO(b/415841192) Migrate @perfetto_repo to @perfetto.
# This repo exposes only proto libraries, and does not need the patch applied
# to the @perfetto repo.
http_archive(
    name = "perfetto_repo",
    build_file = "@//tools/base/profiler:native/external/perfetto.BUILD",
    sha256 = "8bfada46f61b938ec26a777db3c8facf562af8aa22049d478f169f3105f53b4c",
    strip_prefix = "perfetto-52.0",
    url = "https://github.com/google/perfetto/archive/refs/tags/v52.0.zip",
)

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
