"""A drop-in wrapper for the rust_proc_macro rule which forces the target
platform to mac-x64 when building for mac-arm64.

This wrapper exists because a rust_proc_macro is a compiler extension, and must
be "binary compatible" with the the rust compiler loading it. On an arm64 mac,
we are running a x64 rustc binary via Rosetta (as of 2024/07). So even if
everything else is being built for mac-arm64, a rust_proc_macro crate and its
dependencies must still be built for mac-x64.

Note: this wrapper will brake if we switch to a native arm64 toolchain on mac-
arm64.
"""

load("@rules_rust//rust:defs.bzl", _rust_proc_macro = "rust_proc_macro")
load("@rules_rust//rust:rust_common.bzl", "COMMON_PROVIDERS")
load("@with_cfg.bzl", "with_cfg")

rust_proc_macro_mac_x64, _rust_proc_macro_mac_x64_internal = with_cfg(
    _rust_proc_macro,
    extra_providers = [p for p in COMMON_PROVIDERS if p != DefaultInfo],
).set("platforms", [Label("//tools/base/bazel/migration/platforms:macos_x64")]).build()

def rust_proc_macro(*, name, **kwargs):
    kwargs["crate_name"] = name.replace("-", "_")
    rust_proc_macro_mac_x64(
        name = "{}_mac_x64".format(name),
        target_compatible_with = ["@platforms//os:macos"],
        **kwargs
    )
    _rust_proc_macro(
        name = "{}_actual".format(name),
        target_compatible_with = select({
            "@platforms//os:macos": ["@platforms//:incompatible"],
            "//conditions:default": [],
        }),
        **kwargs
    )
    native.alias(
        name = name,
        actual = select({
            "@platforms//os:macos": ":{}_mac_x64".format(name),
            "//conditions:default": ":{}_actual".format(name),
        }),
    )
