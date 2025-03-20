"""
Configure vendor specific repositories.

These are repositories that may only exist or work with internal sources,
and need to be substituted/supplied with vendor specific replacements.
"""

load("@rules_android_ndk//:rules.bzl", "android_ndk_repository")

# buildifier: disable=unnamed-macro
# TODO(b/340640065): Migrate to rules_android and rules_android_ndk.
def setup_vendor_repositories():
    native.android_sdk_repository(
        name = "androidsdk",
        build_tools_version = "34.0.0",
        api_level = 34,
    )

    android_ndk_repository(
        name = "androidndk",
        api_level = 27,
    )
    native.register_toolchains("@androidndk//:all")
